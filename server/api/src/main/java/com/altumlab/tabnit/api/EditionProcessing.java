package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.CoreStorageException;
import com.altumlab.tabnit.core.ImpactDirection;
import com.altumlab.tabnit.core.ImpactQuery;
import com.altumlab.tabnit.core.ImpactRequest;
import com.altumlab.tabnit.core.ImpactScope;
import com.altumlab.tabnit.core.UnknownImpactDirectionException;
import com.altumlab.tabnit.link.ChangeEvent;
import com.altumlab.tabnit.link.UnprocessedChangeEvents;
import com.altumlab.tabnit.link.ChangeEventStatus;
import com.altumlab.tabnit.link.ChangeFailureDetail;
import com.altumlab.tabnit.link.ChangeEvents;
import com.altumlab.tabnit.link.ChangeFailedStage;
import com.altumlab.tabnit.link.Edition;
import com.altumlab.tabnit.link.EditionIngest;
import com.altumlab.tabnit.link.EditionRecords;
import com.altumlab.tabnit.link.IngestOutcome;
import com.altumlab.tabnit.link.IngestResult;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.pack.RelationTypeDefinition;
import com.altumlab.tabnit.pack.RuleChangeKind;
import com.altumlab.tabnit.rules.ActionGeneration;
import com.altumlab.tabnit.rules.ActionGenerator;
import com.altumlab.tabnit.rules.ActionStorageException;
import com.altumlab.tabnit.rules.BasisEdition;
import com.altumlab.tabnit.rules.RuleChange;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * 판을 투입하고 그 변동을 조치까지 잇는다 (D2 AD-44, AD-233 ~ AD-236).
 *
 * <p>순서만 잡는다: link 의 투입 → 변동 이벤트마다 core 의 영향 범위 계산 → rules 의 조치 생성과 저장 →
 * link 에 처리됨이나 실패로 표시. 계산과 판단은 각 모듈이 한다.
 *
 * <p>한 Pack 에서는 "투입과 그 변동의 처리"를 한 번에 하나만 한다. 결과는 투입한 차례로만 정해진다 (D2 AD-119).
 * 현재 시각은 여기서 읽어 엔진에 값으로 넘긴다.
 *
 * <p>잠금을 잡은 연결 하나를 쥔 채로 다른 연결로 일을 한다. 연결 풀에는 연결이 둘 이상 있어야 한다.
 * 판의 투입은 반드시 이 서비스로 한다: link 의 투입을 직접 부르면 처리 중인 변동과 섞인다.
 * 조치 생성은 트랜잭션 밖에서 부른다 (rules 가 자기 트랜잭션을 연다).
 */
class EditionProcessing {

	private static final Logger log = LoggerFactory.getLogger(EditionProcessing.class);

	/** 처리 결과를 변동 이벤트에 표시하지 못했다. 변동 이벤트에는 적히지 않고 돌려주는 결과에만 담는다. */
	static final String NOT_RECORDED = "NOT_RECORDED";

	/** Pack 마다 한 번에 하나만 하게 하는 잠금의 이름 앞머리. link 의 입력 자료 잠금과 겹치지 않는다. */
	private static final String LOCK_PREFIX = "tabnit:edition-processing:";

	private final DataSource dataSource;

	private final Clock clock;

	private final PackDefinitions packs;

	private final EditionIngest ingest;

	private final EditionRecords editions;

	private final ChangeEvents changeEvents;

	private final ImpactQuery impact;

	private final ActionGenerator actions;

	private final long maxFileBytes;

	/**
	 * 이 프로세스 안에서 Pack 마다 기다리게 하는 잠금. 정해진 수만 두고 Pack 의 식별자로 고른다: 없는 Pack 의
	 * 식별자가 아무리 들어와도 늘지 않는다. 다른 Pack 이 같은 잠금에 걸리면 서로 기다릴 뿐 결과는 같다.
	 */
	private final ReentrantLock[] localLocks = new ReentrantLock[64];

	/** 이 스레드가 지금 처리하고 있는 Pack. 겹쳐 부른 것을 알아보는 데 쓴다. */
	private final ThreadLocal<String> current = new ThreadLocal<>();

	EditionProcessing(DataSource dataSource, Clock clock) {
		this(dataSource, clock, EditionIngest.DEFAULT_MAX_FILE_BYTES);
	}

	/**
	 * @param maxFileBytes 받는 파일 크기의 한도 (바이트). 설치 설정에서 읽어 넘긴다
	 */
	EditionProcessing(DataSource dataSource, Clock clock, long maxFileBytes) {
		this.maxFileBytes = maxFileBytes;
		this.dataSource = Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
		this.clock = Objects.requireNonNull(clock, "시계는 반드시 있어야 합니다");
		this.packs = new PackDefinitions(dataSource);
		this.ingest = new EditionIngest(dataSource, maxFileBytes);
		this.editions = new EditionRecords(dataSource);
		this.changeEvents = new ChangeEvents(dataSource);
		this.impact = new ImpactQuery(dataSource);
		this.actions = new ActionGenerator(dataSource);
		for (int i = 0; i < this.localLocks.length; i++) {
			this.localLocks[i] = new ReentrantLock();
		}
	}

	/** 받는 파일 크기의 한도 (바이트). */
	long maxFileBytes() {
		return this.maxFileBytes;
	}

	/**
	 * 파일 하나를 새 판으로 투입하고, 이어서 그 Pack 의 처리되지 않은 변동 이벤트를 모두 처리한다.
	 *
	 * <p>판을 받아들인 뒤 변동의 처리를 시작하지 못하면 (저장소 오류 등) 예외를 던지지 않고 투입의 결과와 함께
	 * 그 사실을 돌려준다: 판은 이미 저장되어 있다.
	 *
	 * <p>투입이 받아들여지지 않으면 (내용이 같거나 거부) 변동을 처리하지 않는다. 앞서 실패해 남아 있던 변동은
	 * 다음에 받아들여진 투입이나 {@link #processPending} 에서 다시 시도된다.
	 *
	 * @param fileName 받은 파일의 이름. 없으면 null
	 * @throws ApiProblemException Pack 이나 입력 자료가 없거나 적용일이 틀렸을 때
	 * @throws com.altumlab.tabnit.link.IngestFailedException 판을 저장하다가 실패했을 때
	 */
	EditionProcessingResult submit(String packId, String inputSourceId, String fileName, byte[] content,
			LocalDate effectiveDate) {
		Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
		Objects.requireNonNull(inputSourceId, "입력 자료의 식별자는 반드시 있어야 합니다");
		Objects.requireNonNull(content, "파일의 내용은 반드시 있어야 합니다");
		Objects.requireNonNull(effectiveDate, "적용일은 반드시 있어야 합니다");
		// link 가 받지 않는 값은 여기서 먼저 가린다. 무엇이 틀렸는지를 api 의 문구로 알린다.
		if (effectiveDate.isBefore(LocalDate.of(1, 1, 1)) || effectiveDate.isAfter(LocalDate.of(9999, 12, 31))) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_EDITION", "effectiveDate",
					"적용일은 0001-01-01 부터 9999-12-31 까지만 받습니다. 적용일을 확인해 다시 보내세요.");
		}
		if (fileName != null && fileName.indexOf('\u0000') >= 0) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_EDITION", "file",
					"파일의 이름에 쓸 수 없는 글자가 있습니다. 파일의 이름을 고쳐 다시 올리세요.");
		}
		return oneAtATime(packId, () -> {
			PackDefinition pack = pack(packId);
			if (pack.mappings().stream().noneMatch(mapping -> mapping.inputSourceId().equals(inputSourceId))) {
				throw new ApiProblemException(HttpStatus.NOT_FOUND, "INPUT_SOURCE_NOT_FOUND", "inputSourceId",
						"Pack " + packId + " 에 입력 자료 " + inputSourceId + " 가 없습니다. 입력 자료의 식별자를 확인하세요.");
			}
			// 받은 때는 잠금을 잡은 뒤에 읽는다. 받은 차례와 처리하는 차례가 같아진다.
			IngestResult result = this.ingest.ingest(pack, inputSourceId, fileName, content, effectiveDate,
					this.clock.instant());
			if (result.outcome() != IngestOutcome.ACCEPTED) {
				// 판이 생기지 않았다 (내용이 같거나 거부). 새로 처리할 변동이 없다.
				return new EditionProcessingResult(result, List.of(), false, List.of());
			}
			List<ProcessedChange> done;
			try {
				done = process(pack);
			}
			catch (RuntimeException e) {
				// 판은 이미 받아들여졌다. 투입의 결과를 잃지 않게 돌려주고, 변동의 처리를 하지 못했다고 알린다.
				// 변동은 대기로 남아 있다: 다음에 받아들여진 투입이나 남은 변동의 처리에서 처리된다.
				log.error("판 {} 은 받아들였지만 변동의 처리를 시작하지 못했습니다.", result.editionId(), e);
				return new EditionProcessingResult(result, List.of(), true, pendingEvents(result.editionId()));
			}
			// 그 판의 변동 이벤트는 잠금을 놓기 전에 읽는다. 뒤에 온 요청이 상태를 바꾸기 전의 것이다.
			// 읽지 못해도 처리한 결과는 그대로 돌려준다.
			return new EditionProcessingResult(result, done, false, pendingEvents(result.editionId()));
		});
	}

	/** 판의 변동 이벤트. 읽지 못하면 까닭을 기록에 남기고 빈 목록을 돌려준다: 판은 이미 받아들여졌다. */
	private List<ChangeEvent> pendingEvents(String editionId) {
		try {
			return this.editions.changeEvents(editionId);
		}
		catch (RuntimeException e) {
			log.error("판 {} 의 변동 이벤트를 읽지 못했습니다.", editionId, e);
			return List.of();
		}
	}

	/**
	 * 그 Pack 의 처리되지 않은 변동 이벤트(대기, 실패)를 정해진 차례로 모두 처리한다. 실패했던 것은 지금의 자료와
	 * 지금의 규칙으로 처음부터 다시 계산한다 (D2 AD-154).
	 *
	 * @return 처리한 변동 이벤트. 처리한 차례다. 없으면 빈 목록
	 * @throws ApiProblemException Pack 이 없을 때
	 */
	List<ProcessedChange> processPending(String packId) {
		Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
		return oneAtATime(packId, () -> process(pack(packId)));
	}

	/**
	 * 그 Pack 의 처리되지 않은 변동 이벤트(대기, 실패)를 그 판과 실패의 까닭과 함께 읽는다. 처리하는 차례다 (D2 AD-207).
	 * 읽기만 한다: 판 처리를 기다리지 않는다.
	 *
	 * @throws ApiProblemException Pack 이 없을 때
	 */
	List<UnprocessedChange> unprocessed(String packId) {
		Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
		pack(packId);
		// 변동 이벤트와 까닭은 한 시점의 것으로 함께 읽는다. 그 사이에 다시 처리가 끼어들어도 서로 어긋나지 않는다.
		UnprocessedChangeEvents read = this.changeEvents.unprocessedWithFailureDetails(packId);
		// 판은 식별자로 찾는 데만 쓴다.
		Map<String, Edition> editionsById = new TreeMap<>();
		List<UnprocessedChange> found = new ArrayList<>();
		for (ChangeEvent event : read.events()) {
			Edition edition = editionsById.computeIfAbsent(event.editionId(),
					id -> this.editions.findEdition(id).orElse(null));
			if (edition == null) {
				// 저장소가 어긋났다. 변동 이벤트는 판을 가리키므로 생기지 않아야 한다.
				throw new IllegalStateException("변동 이벤트 " + event.id() + " 의 판 " + event.editionId()
						+ " 이 없습니다. 저장소의 상태를 확인하세요.");
			}
			// 실패가 아닌 것(대기)에는 까닭이 없다.
			found.add(new UnprocessedChange(event, edition,
					event.status() == ChangeEventStatus.FAILED ? read.failuresOf(event.id()) : List.of()));
		}
		return found;
	}

	/** 처리되지 않은 변동 이벤트 하나와 그 판, 실패의 까닭. */
	record UnprocessedChange(ChangeEvent event, Edition edition, List<ChangeFailureDetail> failures) {
	}

	private PackDefinition pack(String packId) {
		// NUL 이 든 식별자의 Pack 은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		return (packId.indexOf('\u0000') >= 0 ? Optional.<PackDefinition>empty() : this.packs.find(packId))
			.orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "packId",
					"Pack " + packId + " 이 적재되어 있지 않습니다. Pack 의 식별자를 확인하거나 먼저 적재하세요."));
	}

	/** 변동 하나가 실패해도 나머지는 계속 처리한다 (D2 AD-236). */
	private List<ProcessedChange> process(PackDefinition pack) {
		// 영향 방향과 최대 깊이의 기본값은 Pack 에서 읽어 core 에 넘긴다 (D2 AD-66). 이름으로 찾는 데만 쓴다.
		Map<String, ImpactDirection> directions = new TreeMap<>();
		for (RelationTypeDefinition type : pack.relationTypes()) {
			directions.put(type.name(), ImpactDirection.valueOf(type.impactDirection().name()));
		}
		List<ProcessedChange> done = new ArrayList<>();
		for (ChangeEvent event : this.changeEvents.unprocessed(pack.packId())) {
			try {
				done.add(processOne(pack, directions, event));
			}
			catch (RuntimeException e) {
				// 처리됨이나 실패의 표시 자체를 하지 못했다 (저장소 오류 등). 그 변동의 상태는 바뀌지 않았다: 다음 처리에서
				// 다시 시도된다. 조치가 이미 저장되어 있었다면 그때 표시만 된다 (D2 AD-228). 나머지는 계속 처리한다.
				log.error("변동 이벤트 {} 의 처리 결과를 표시하지 못했습니다.", event.id(), e);
				done.add(new ProcessedChange(event.id(), event.editionId(), event.itemKey(), false, null, NOT_RECORDED, 0));
			}
		}
		return done;
	}

	private ProcessedChange processOne(PackDefinition pack, Map<String, ImpactDirection> directions, ChangeEvent event) {
		Edition edition = this.editions.findEdition(event.editionId()).orElse(null);
		if (edition == null) {
			// 저장소가 어긋났다. 변동 이벤트는 판을 가리키므로 생기지 않아야 한다. 이 변동만 실패로 표시한다.
			return failed(event, ChangeFailedStage.IMPACT, "EDITION_NOT_FOUND", new IllegalStateException(
					"변동 이벤트 " + event.id() + " 의 판 " + event.editionId() + " 이 없습니다. 저장소의 상태를 확인하세요."));
		}
		LocalDate effective = edition.effectiveDate();
		ImpactScope scope;
		try {
			// 적용일 전날과 적용일 두 날짜로 본다 (D2 AD-100). 합치는 일은 core 가 한다.
			scope = this.impact.impactScope(new ImpactRequest(event.targetEntityIds(),
					List.of(effective.minusDays(1), effective), directions, pack.defaultMaxDepth()));
		}
		catch (UnknownImpactDirectionException e) {
			return failed(event, ChangeFailedStage.IMPACT, "UNKNOWN_IMPACT_DIRECTION", e);
		}
		catch (CoreStorageException e) {
			return failed(event, ChangeFailedStage.IMPACT, "STORAGE_FAILURE", e);
		}
		catch (RuntimeException e) {
			// 그 밖의 모든 오류. 프로그램의 잘못일 수도 있다. 까닭은 기록에 남고, 변동은 실패로 남아 다시 시도된다.
			return failed(event, ChangeFailedStage.IMPACT, "IMPACT_REJECTED", e);
		}
		ActionGeneration generation;
		try {
			// 저장하는 때는 변동마다 여기서 읽어 엔진에 값으로 넘긴다. 엔진은 시계를 읽지 않는다.
			Instant now = this.clock.instant();
			generation = this.actions.generate(pack,
					// 규칙이 입력 자료와 그 행에서 바뀐 것을 가릴 수 있게 함께 넘긴다 (D2 AD-290, AD-291).
					// 바뀐 것을 남기기 전에 만들어진 변동 이벤트는 그것을 모른다 (null): 그것을 가리는 규칙은 실패로 알려진다.
					new RuleChange(event.id(), RuleChangeKind.valueOf(event.kind().name()), event.primaryEntityId(),
							event.targetEntityIds(), effective, edition.inputSourceId(), event.changedAttributes(),
							event.changedRelationTypes()),
					new BasisEdition(edition.inputSourceId(), edition.editionNo()), scope, pack.defaultMaxDepth(), now);
		}
		catch (ActionStorageException e) {
			return failed(event, ChangeFailedStage.RULES, "STORAGE_FAILURE", e);
		}
		catch (RuntimeException e) {
			return failed(event, ChangeFailedStage.RULES, "RULES_REJECTED", e);
		}
		if (generation.outcome() == ActionGeneration.Outcome.RULE_FAILED) {
			// 평가에 실패한 규칙이 있다. 아무것도 저장되지 않았다 (D2 AD-227).
			log.warn("변동 이벤트 {} 의 규칙 실행이 실패했습니다. 실패한 규칙: {}", event.id(), generation.failures());
			// 어느 규칙이 왜 실패했는지를 변동 이벤트에 함께 남긴다 (D2 AD-321, AD-345). 규칙 번호 순 그대로다.
			List<ChangeFailureDetail> details = generation.failures()
				.stream()
				.map(failure -> new ChangeFailureDetail(failure.ruleNo(), failure.code(), failure.entityId(),
						failure.attributeName(), failure.value()))
				.toList();
			this.changeEvents.markFailed(event.id(), ChangeFailedStage.RULES, "RULE_FAILED", details);
			return new ProcessedChange(event.id(), event.editionId(), event.itemKey(), false, ChangeFailedStage.RULES.name(),
					"RULE_FAILED", 0, details);
		}
		// 저장했거나 이미 저장되어 있었다. 저장한 뒤 표시하기 전에 멈췄던 변동은 여기서 표시만 된다 (D2 AD-228).
		this.changeEvents.markProcessed(event.id());
		return new ProcessedChange(event.id(), event.editionId(), event.itemKey(), true, null, null,
				generation.actions().size());
	}

	/** 실패로 표시한다. 까닭은 기록(로그)에 남기고, 변동 이벤트에는 단계와 이유의 코드를 적는다. */
	private ProcessedChange failed(ChangeEvent event, ChangeFailedStage stage, String code, RuntimeException cause) {
		log.warn("변동 이벤트 {} 의 처리가 {} 단계에서 실패했습니다 ({}).", event.id(), stage, code, cause);
		this.changeEvents.markFailed(event.id(), stage, code);
		return new ProcessedChange(event.id(), event.editionId(), event.itemKey(), false, stage.name(), code, 0);
	}

	/**
	 * Pack 하나에 한 번에 하나만 한다 (D2 AD-235).
	 *
	 * <p>먼저 이 프로세스 안의 잠금에서 기다린다. 기다리는 동안에는 연결을 쥐지 않는다: 같은 Pack 에 호출이 몰려도
	 * 기다리는 쪽이 연결 풀을 비우지 않는다. 그다음 데이터베이스의 권고 잠금을 잡는다: 앱이 여럿 떠 있어도 성립한다.
	 * 권고 잠금은 트랜잭션의 것으로 잡는다. 그 연결의 트랜잭션이 끝나면 (되돌리거나, 연결이 끊기거나, 풀로 돌아가면서)
	 * 반드시 풀린다. 풀리지 않은 잠금이 연결에 남아 풀로 돌아가는 일이 없다.
	 */
	private <T> T oneAtATime(String packId, Supplier<T> work) {
		ReentrantLock local = this.localLocks[Math.floorMod(packId.hashCode(), this.localLocks.length)];
		String running = this.current.get();
		if (running != null) {
			// 처리하는 도중에 처리를 다시 부르면 (같은 Pack 이면) 자기 자신을 끝없이 기다리거나, (다른 Pack 이면) 잠금을
			// 잡는 차례가 엇갈려 서로 기다릴 수 있다. 겹쳐 부르지 않는다.
			throw new IllegalStateException("Pack " + running + " 을 처리하는 도중에 Pack " + packId
					+ " 의 처리를 다시 불렀습니다. 처리는 겹쳐 부를 수 없습니다. 부르는 코드를 확인하세요.");
		}
		local.lock();
		this.current.set(packId);
		try {
			Connection connection;
			try {
				connection = this.dataSource.getConnection();
			}
			catch (SQLException e) {
				throw lockFailed(packId, e);
			}
			RuntimeException failure = null;
			try {
				try {
					connection.setAutoCommit(false);
					try (PreparedStatement statement = connection
						.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
						statement.setString(1, LOCK_PREFIX + packId);
						statement.execute();
					}
				}
				catch (SQLException e) {
					throw lockFailed(packId, e);
				}
				return work.get();
			}
			catch (RuntimeException e) {
				failure = e;
				throw e;
			}
			finally {
				try {
					// 트랜잭션을 끝내 잠금을 푼다. 이 연결로는 잠금 말고 아무것도 하지 않았다.
					connection.rollback();
					connection.setAutoCommit(true);
				}
				catch (SQLException e) {
					// 끝내지 못한 연결은 깨진 것이다. 닫으면 풀이 버리고, 세션이 끝나면서 잠금도 풀린다. 일의 결과는 그대로 돌려준다.
					log.warn("Pack {} 의 처리 차례를 놓는 데 실패했습니다. 연결이 끊긴 것으로 보입니다.", packId, e);
					if (failure != null) {
						failure.addSuppressed(e);
					}
				}
				try {
					connection.close();
				}
				catch (SQLException e) {
					log.warn("Pack {} 의 처리 차례를 잡았던 연결을 닫지 못했습니다.", packId, e);
				}
			}
		}
		finally {
			this.current.remove();
			local.unlock();
		}
	}

	private static IllegalStateException lockFailed(String packId, SQLException cause) {
		return new IllegalStateException("Pack " + packId + " 의 처리 차례를 잡지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", cause);
	}
}
