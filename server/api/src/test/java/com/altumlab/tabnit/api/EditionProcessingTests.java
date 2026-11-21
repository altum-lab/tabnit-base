package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altumlab.tabnit.link.ChangeEvent;
import com.altumlab.tabnit.link.ChangeEventStatus;
import com.altumlab.tabnit.link.ChangeEvents;
import com.altumlab.tabnit.link.EditionRecords;
import com.altumlab.tabnit.link.IngestOutcome;
import com.altumlab.tabnit.pack.PackLoader;
import com.altumlab.tabnit.rules.Action;
import com.altumlab.tabnit.rules.ActionRecords;
import com.altumlab.tabnit.rules.ActionStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.yaml.snakeyaml.Yaml;

/**
 * C-06 수용 기준: 새 판 적재가 영향 범위 계산과 조치 생성으로 이어진다.
 *
 * <p>시험용 Pack 의 시나리오를 처음부터 끝까지 돌린다: 파일 투입 → 판 비교 → 변동 이벤트 → 영향 범위 계산 →
 * 조치 생성과 저장 → 처리됨 표시. 조치는 그 Pack 의 검증 질문(조치)의 정답과 견준다.
 * 시험마다 Pack 의 식별자를 바꾼 사본을 적재해 서로 섞이지 않게 한다. 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EditionProcessingTests {

	private static final Path PACK = Path.of("../../packs/test-mini");

	private static final Instant START = Instant.parse("2026-07-01T00:00:00Z");

	@Autowired
	private DataSource dataSource;

	@TempDir
	private Path temp;

	@Test
	void 시나리오의_판을_차례로_투입하면_조치_질문의_정답과_같은_조치가_저장된다() throws Exception {
		String packId = "c06-scenario";
		Path pack = loadCopy(packId, text -> text);
		EditionProcessing processing = new EditionProcessing(this.dataSource, new TickingClock());
		Map<String, Object> file = questions();
		Map<Integer, String> editionOfStep = new TreeMap<>();

		for (Map<String, Object> step : steps(file)) {
			EditionProcessingResult result = processing.submit(packId, (String) step.get("inputSource"), null,
					Files.readAllBytes(pack.resolve((String) step.get("file"))), date(step.get("effectiveDate")));
			assertThat(result.ingest().outcome()).as("단계 %s", step.get("step")).isEqualTo(IngestOutcome.ACCEPTED);
			// 그 호출이 끝나면 그 판의 변동은 모두 처리되어 있다.
			assertThat(result.changes()).as("단계 %s", step.get("step")).allSatisfy(change -> {
				assertThat(change.processed()).isTrue();
				assertThat(change.editionId()).isEqualTo(result.ingest().editionId());
			});
			editionOfStep.put((Integer) step.get("step"), result.ingest().editionId());
		}

		assertThat(new ChangeEvents(this.dataSource).unprocessed(packId)).isEmpty();
		EditionRecords editions = new EditionRecords(this.dataSource);
		ActionRecords actions = new ActionRecords(this.dataSource);
		int compared = 0;
		for (Map<String, Object> question : list(file.get("questions"))) {
			if (!"actions".equals(question.get("kind"))) {
				continue;
			}
			Map<String, Object> change = map(question.get("change"));
			String itemKey = String.join("|", strings(change.get("itemKey")));
			ChangeEvent event = editions.changeEvents(editionOfStep.get((Integer) change.get("step")))
				.stream()
				.filter(candidate -> candidate.itemKey().equals(itemKey))
				.findFirst()
				.orElseThrow();
			assertThat(event.status()).isEqualTo(ChangeEventStatus.PROCESSED);
			List<String> expected = new ArrayList<>();
			for (Map<String, Object> action : list(map(question.get("expect")).get("actions"))) {
				Map<String, Object> basis = map(action.get("basis"));
				expected.add(String.join(" / ", (String) action.get("rule"), String.valueOf(action.get("priority")),
						entityId(packId, action.get("target")), date(action.get("dueDate")).toString(),
						entityId(packId, basis.get("entity")), (String) basis.get("inputSource"),
						String.valueOf(basis.get("edition"))));
			}
			List<String> stored = new ArrayList<>();
			for (Action action : actions.actions(event.id())) {
				assertThat(action.status()).isEqualTo(ActionStatus.PENDING);
				stored.add(String.join(" / ", action.ruleNo(), String.valueOf(action.priority()), action.targetEntityId(),
						action.dueDate().toString(), action.basisEntityId(), action.basisInputSourceId(),
						String.valueOf(action.basisEditionNo())));
			}
			// 내용과 차례가 모두 같아야 한다.
			assertThat(stored).as("질문 %s", question.get("id")).containsExactlyElementsOf(expected);
			assertThat(actions.impactScope(event.id())).as("질문 %s 의 영향 범위", question.get("id")).isPresent();
			compared++;
		}
		// 질문이 하나도 없어 아무것도 견주지 않은 채 통과하지 않게 한다.
		assertThat(compared).isPositive();
	}

	@Test
	void 받아들여지지_않은_투입은_변동을_만들지_않고_같은_파일을_다시_넣어도_조치가_늘지_않는다() throws Exception {
		String packId = "c06-again";
		Path pack = loadCopy(packId, text -> text);
		EditionProcessing processing = new EditionProcessing(this.dataSource, new TickingClock());
		List<Map<String, Object>> steps = steps(questions());
		for (Map<String, Object> step : steps) {
			processing.submit(packId, (String) step.get("inputSource"), null,
					Files.readAllBytes(pack.resolve((String) step.get("file"))), date(step.get("effectiveDate")));
		}
		int before = count("action", packId);
		assertThat(before).isPositive();
		Map<String, Object> last = steps.get(steps.size() - 1);

		// 같은 파일을 다시: 판이 생기지 않는다.
		EditionProcessingResult same = processing.submit(packId, (String) last.get("inputSource"), null,
				Files.readAllBytes(pack.resolve((String) last.get("file"))), date(last.get("effectiveDate")));
		// 읽을 수 없는 파일: 거부된다.
		EditionProcessingResult broken = processing.submit(packId, (String) last.get("inputSource"), null,
				"머리글도 아닌 것\n".getBytes(StandardCharsets.UTF_8), date(last.get("effectiveDate")));
		// 다시 처리할 것이 없다.
		List<ProcessedChange> again = processing.processPending(packId);

		assertThat(same.ingest().outcome()).isEqualTo(IngestOutcome.UNCHANGED);
		assertThat(same.changes()).isEmpty();
		assertThat(broken.ingest().outcome()).isEqualTo(IngestOutcome.REJECTED);
		assertThat(broken.changes()).isEmpty();
		assertThat(again).isEmpty();
		assertThat(count("action", packId)).isEqualTo(before);
	}

	@Test
	void 변동_하나가_실패해도_나머지는_처리하고_고친_뒤_다시_처리하면_처리된다() throws Exception {
		String packId = "c06-failed";
		// 규칙 하나의 기한을 날짜로 담을 수 없게 고친 사본. 그 규칙에 걸리는 변동만 실패한다.
		Path pack = loadCopy(packId, text -> text.replace("tabnit:dueInDays 14 .", "tabnit:dueInDays 2147483647 ."));
		EditionProcessing processing = new EditionProcessing(this.dataSource, new TickingClock());
		List<ProcessedChange> all = new ArrayList<>();
		for (Map<String, Object> step : steps(questions())) {
			all.addAll(processing.submit(packId, (String) step.get("inputSource"), null,
					Files.readAllBytes(pack.resolve((String) step.get("file"))), date(step.get("effectiveDate")))
				.changes());
		}

		// 실패한 것과 처리된 것이 함께 있다. 실패한 것에는 단계와 이유의 코드가 있고 조치가 없다.
		List<ProcessedChange> failed = all.stream().filter(change -> !change.processed()).toList();
		assertThat(failed).isNotEmpty();
		assertThat(all.stream().filter(ProcessedChange::processed)).isNotEmpty();
		ActionRecords actions = new ActionRecords(this.dataSource);
		assertThat(failed).allSatisfy(change -> {
			assertThat(change.failedStage()).isEqualTo("RULES");
			assertThat(change.failureCode()).isEqualTo("RULE_FAILED");
			assertThat(change.actionCount()).isZero();
			assertThat(actions.impactScope(change.changeEventId())).isEmpty();
		});
		ChangeEvents events = new ChangeEvents(this.dataSource);
		assertThat(events.unprocessed(packId)).extracting(ChangeEvent::status).containsOnly(ChangeEventStatus.FAILED);
		// 뒤의 투입 때마다 다시 시도되어 같은 변동이 여러 번 실패로 나올 수 있다. 남은 것은 변동마다 하나다.
		int remaining = events.unprocessed(packId).size();

		// 규칙을 고친 Pack 을 다시 적재하고 다시 처리한다.
		loadCopy(packId, text -> text);
		List<ProcessedChange> retried = processing.processPending(packId);

		assertThat(retried).hasSize(remaining).allSatisfy(change -> assertThat(change.processed()).isTrue());
		assertThat(events.unprocessed(packId)).isEmpty();
		assertThat(retried.stream().mapToInt(ProcessedChange::actionCount).sum()).isPositive();
	}

	@Test
	void 같은_Pack_에_함께_들어온_투입은_차례로_처리되어_처리되지_않은_변동이_남지_않는다() throws Exception {
		String packId = "c06-together";
		Path pack = loadCopy(packId, text -> text);
		EditionProcessing processing = new EditionProcessing(this.dataSource, new TickingClock());
		List<Map<String, Object>> steps = steps(questions());
		// 처음 자료(첫 판들)는 차례로 넣는다.
		List<Map<String, Object>> later = new ArrayList<>();
		for (Map<String, Object> step : steps) {
			if (date(step.get("effectiveDate")).equals(date(steps.get(0).get("effectiveDate")))) {
				processing.submit(packId, (String) step.get("inputSource"), null,
						Files.readAllBytes(pack.resolve((String) step.get("file"))), date(step.get("effectiveDate")));
			}
			else if (later.stream().noneMatch(other -> other.get("inputSource").equals(step.get("inputSource")))) {
				// 입력 자료마다 다음 판 하나씩. 서로 다른 입력 자료의 것이라 함께 넣을 수 있다.
				later.add(step);
			}
		}
		assertThat(later.size()).isGreaterThan(1);

		ExecutorService pool = Executors.newFixedThreadPool(later.size());
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<EditionProcessingResult>> futures = new ArrayList<>();
			for (Map<String, Object> step : later) {
				byte[] content = Files.readAllBytes(pack.resolve((String) step.get("file")));
				futures.add(pool.submit(() -> {
					go.await();
					return processing.submit(packId, (String) step.get("inputSource"), null, content,
							date(step.get("effectiveDate")));
				}));
			}
			go.countDown();
			for (Future<EditionProcessingResult> future : futures) {
				EditionProcessingResult result = future.get();
				assertThat(result.ingest().outcome()).isEqualTo(IngestOutcome.ACCEPTED);
				// 한 번에 하나씩이라 자기 판의 변동은 자기 호출이 처리한다.
				assertThat(result.changes()).allSatisfy(change -> {
					assertThat(change.processed()).isTrue();
					assertThat(change.editionId()).isEqualTo(result.ingest().editionId());
				});
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(new ChangeEvents(this.dataSource).unprocessed(packId)).isEmpty();
	}

	@Autowired
	private EditionProcessing wired;

	@Test
	void 같은_Pack_에_호출이_연결_풀보다_많이_몰려도_모두_끝나고_다른_Pack_은_기다리지_않는다() throws Exception {
		String packId = "c06-crowd";
		Path pack = loadCopy(packId, text -> text);
		loadCopy("c06-crowd-other", text -> text);
		Map<String, Object> first = steps(questions()).get(0);
		byte[] content = Files.readAllBytes(pack.resolve((String) first.get("file")));
		// 설정으로 이어 둔 것을 쓴다. 연결 풀은 기본 크기(10)다.
		int callers = 24;
		ExecutorService pool = Executors.newFixedThreadPool(callers);
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<EditionProcessingResult>> futures = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				futures.add(pool.submit(() -> {
					go.await();
					return this.wired.submit(packId, (String) first.get("inputSource"), null, content,
							date(first.get("effectiveDate")));
				}));
			}
			go.countDown();
			int accepted = 0;
			for (Future<EditionProcessingResult> future : futures) {
				// 기다리는 쪽이 연결을 쥐고 있으면 일하는 쪽이 연결을 얻지 못해 여기서 시간이 다 된다.
				EditionProcessingResult result = future.get(60, java.util.concurrent.TimeUnit.SECONDS);
				accepted += result.ingest().outcome() == IngestOutcome.ACCEPTED ? 1 : 0;
				assertThat(result.ingest().outcome()).isNotEqualTo(IngestOutcome.REJECTED);
			}
			// 한 번에 하나씩이라 처음 하나만 판이 되고 나머지는 내용이 같다.
			assertThat(accepted).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		// 다른 Pack 과, 같은 Pack 의 다음 호출이 막혀 있지 않다 (잠금이 남지 않았다).
		assertThat(this.wired.processPending("c06-crowd-other")).isEmpty();
		assertThat(this.wired.processPending(packId)).isEmpty();
	}

	@Test
	void 없는_Pack_이나_없는_입력_자료와_빈_값은_무엇이_틀렸는지_알린다() throws Exception {
		String packId = "c06-wrong";
		loadCopy(packId, text -> text);
		EditionProcessing processing = new EditionProcessing(this.dataSource, new TickingClock());
		byte[] content = "a\n".getBytes(StandardCharsets.UTF_8);
		LocalDate day = LocalDate.parse("2026-01-01");

		assertThatThrownBy(() -> processing.submit("c06-없는-pack", "program", null, content, day))
			.isInstanceOf(ApiProblemException.class)
			.hasMessageContaining("c06-없는-pack")
			.satisfies(e -> assertThat(((ApiProblemException) e).code()).isEqualTo("PACK_NOT_FOUND"));
		assertThatThrownBy(() -> processing.processPending("c06-없는-pack")).isInstanceOf(ApiProblemException.class);
		assertThatThrownBy(() -> processing.submit(packId, "없는 입력 자료", null, content, day))
			.isInstanceOf(ApiProblemException.class)
			.satisfies(e -> assertThat(((ApiProblemException) e).code()).isEqualTo("INPUT_SOURCE_NOT_FOUND"));
		assertThatThrownBy(() -> processing.submit(null, "program", null, content, day)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> processing.submit(packId, null, null, content, day)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> processing.submit(packId, "program", null, null, day)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> processing.submit(packId, "program", null, content, null)).isInstanceOf(NullPointerException.class);
		assertThat(count("impact_scope", packId)).isZero();
		// 틀린 요청 뒤에도 잠금이 남지 않는다.
		assertThat(processing.processPending(packId)).isEmpty();
	}

	// ── 도우미 ──────────────────────────────────────────────────

	/** 시험용 Pack 의 사본을 식별자를 바꿔 적재한다. 같은 식별자로 다시 부르면 고친 내용으로 다시 적재한다. */
	private Path loadCopy(String packId, java.util.function.UnaryOperator<String> change) throws IOException {
		// 폴더의 이름이 Pack 의 식별자와 같아야 한다. 다시 적재할 때는 다른 윗폴더에 만든다.
		Path copy = this.temp.resolve(String.valueOf(System.nanoTime())).resolve(packId);
		try (Stream<Path> files = Files.walk(PACK)) {
			for (Path source : files.toList()) {
				Path target = copy.resolve(PACK.relativize(source).toString());
				if (Files.isDirectory(source)) {
					Files.createDirectories(target);
				}
				else if (source.toString().endsWith(".ttl")) {
					Files.writeString(target,
							change.apply(Files.readString(source, StandardCharsets.UTF_8)).replace("test-mini", packId),
							StandardCharsets.UTF_8);
				}
				else {
					Files.copy(source, target);
				}
			}
		}
		assertThat(new PackLoader(this.dataSource).load(copy, START).packId()).isEqualTo(packId);
		return copy;
	}

	private int count(String table, String packId) {
		return new org.springframework.jdbc.core.JdbcTemplate(this.dataSource)
			.queryForObject("select count(*) from " + table + " where pack_id = ?", Integer.class, packId);
	}

	private static Map<String, Object> questions() {
		try {
			return map(new Yaml().load(Files.readString(PACK.resolve("questions/S-01.yaml"), StandardCharsets.UTF_8)));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<Map<String, Object>> steps(Map<String, Object> file) {
		return list(map(file.get("scenario")).get("steps"));
	}

	private static String entityId(String packId, Object entity) {
		Map<String, Object> ref = map(entity);
		return packId + "|" + ref.get("type") + "|" + ref.get("key");
	}

	/** YAML 의 날짜는 자정(UTC)의 시각으로 읽힌다. */
	private static LocalDate date(Object value) {
		return ((Date) value).toInstant().atZone(ZoneOffset.UTC).toLocalDate();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<String> strings(Object value) {
		List<String> result = new ArrayList<>();
		for (Object item : (List<Object>) value) {
			result.add(String.valueOf(item));
		}
		return result;
	}

	/** 읽을 때마다 1초씩 가는 시계. 받은 때가 투입한 차례대로 늘어난다. */
	private static final class TickingClock extends Clock {

		private final java.util.concurrent.atomic.AtomicLong seconds = new java.util.concurrent.atomic.AtomicLong();

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return START.plusSeconds(this.seconds.incrementAndGet());
		}
	}
}
