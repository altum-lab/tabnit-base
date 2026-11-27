package com.altumlab.tabnit.api;

import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.link.ChangeEvent;
import com.altumlab.tabnit.link.ChangeEventStatus;
import com.altumlab.tabnit.link.ChangeFailureDetail;
import com.altumlab.tabnit.link.EditionComparison;
import com.altumlab.tabnit.link.EditionComparisons;
import com.altumlab.tabnit.link.EditionPage;
import com.altumlab.tabnit.link.ItemChange;
import java.util.ArrayList;
import java.util.TreeSet;
import com.altumlab.tabnit.rules.ActionRecords;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.Map;
import com.altumlab.tabnit.link.Edition;
import com.altumlab.tabnit.link.EditionRecords;
import com.altumlab.tabnit.link.IngestOutcome;
import com.altumlab.tabnit.link.IngestResult;
import com.altumlab.tabnit.pack.PackDefinitions;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 판을 올리는 API. 규격은 docs/api/openapi.yaml 의 submitEdition, 규칙은 D7 5절이다.
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 투입과 변동의 처리는 {@link EditionProcessing} 이 순서를 잡아 각 모듈에 맡긴다.
 * 판의 투입은 반드시 그 서비스로 한다 (D2 AD-235).
 */
@RestController
class EditionsController {

	/** `2026-03-01` 모양만 받는다. 없는 날짜(2월 30일)는 받지 않는다. */
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
		.withResolverStyle(ResolverStyle.STRICT);

	private static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";

	private final EditionProcessing processing;

	private final EditionRecords editions;

	private final PackDefinitions packs;

	private final EntityViews entityViews;

	private final ActionRecords actions;

	private final EditionComparisons comparisons;

	EditionsController(EditionProcessing processing, EditionRecords editions, PackDefinitions packs,
			EntityViews entityViews, ActionRecords actions, EditionComparisons comparisons) {
		this.comparisons = comparisons;
		this.entityViews = entityViews;
		this.actions = actions;
		this.processing = processing;
		this.editions = editions;
		this.packs = packs;
	}

	/** 규격의 EditionList. 순서는 link 가 준 그대로다. */
	record ListResponse(String packId, int total, int offset, int limit, List<ListItem> editions) {
	}

	/** 규격의 EditionListItem. 수는 link 가 저장된 것을 센 값이다. */
	record ListItem(String editionId, String inputSourceId, String inputSourceLabel, int editionNo, LocalDate effectiveDate, Instant receivedAt,
			String fileName, int itemCount, int changeCount, int noticeCount) {
	}

	/** 지난 판의 목록 (규격의 listEditions, D7 5절 "지난 판", D2 AD-383). 나누는 일과 순서는 link 가 정한다. */
	@GetMapping("/api/v1/editions")
	@Audited(action = "listEditions", params = "packId")
	ListResponse list(@RequestParam(required = false) String packId, @RequestParam(required = false) String offset,
			@RequestParam(required = false) String limit) {
		require(packId, "MISSING_PACK_ID", "packId", "Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		PageRequest page = PageRequest.of(offset, limit);
		// NUL 이 든 식별자의 Pack 은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		Optional<PackDefinition> pack = packId.indexOf('\u0000') >= 0 ? Optional.empty() : this.packs.find(packId);
		if (pack.isEmpty()) {
			throw new ApiProblemException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "packId",
					"Pack " + packId + " 이 적재되어 있지 않습니다. Pack 의 식별자를 확인하세요.");
		}
		InputSourceLabels labels = new InputSourceLabels(this.packs, packId, pack);
		EditionPage found = this.editions.packEditions(packId, page.offset(), page.limit());
		List<ListItem> items = found.editions()
			.stream()
			.map(entry -> new ListItem(entry.edition().id(), entry.edition().inputSourceId(),
					labels.label(packId, entry.edition().inputSourceId()), entry.edition().editionNo(),
					entry.edition().effectiveDate(), entry.edition().receivedAt(), entry.edition().fileName(),
					entry.itemCount(), entry.changeCount(), entry.noticeCount()))
			.toList();
		return new ListResponse(packId, found.total(), page.offset(), page.limit(), items);
	}

	/**
	 * 지난 판 하나의 결과 (규격의 getEdition, D7 5절 "지난 판", D2 AD-383). 저장된 것을 읽어 올린 직후의 답과 같은
	 * 모양으로 돌려준다. 다시 계산하지 않는다.
	 */
	@GetMapping("/api/v1/edition")
	@Audited(action = "getEdition", params = "editionId")
	EditionSubmissionResponse stored(@RequestParam(required = false) String editionId) {
		require(editionId, "MISSING_EDITION_ID", "editionId", "판의 식별자가 없습니다. editionId 에 판의 식별자를 넣어 다시 보내세요.");
		// NUL 이 든 식별자의 판은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		Edition edition = (editionId.indexOf('\u0000') >= 0 ? Optional.<Edition>empty() : this.editions.findEdition(editionId))
			.orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "EDITION_NOT_FOUND", "editionId",
					"그런 판이 없습니다. 판의 식별자를 확인하세요."));
		List<ChangeEvent> events = this.editions.changeEvents(edition.id());
		Map<String, EntityViews.Item> entities = this.entityViews.items(EditionSubmissionResponse.entityIdsOf(events),
				edition.effectiveDate());
		// 처리에 실패한 변동의 까닭과, 변동마다의 조치의 수. 찾는 데만 쓴다.
		Map<String, List<ChangeFailureDetail>> failures = new TreeMap<>();
		boolean anyFailed = events.stream().anyMatch(event -> event.status() == ChangeEventStatus.FAILED);
		if (anyFailed) {
			for (EditionProcessing.UnprocessedChange change : this.processing.unprocessed(edition.packId())) {
				failures.put(change.event().id(), change.failures());
			}
		}
		// 수는 rules 가 센다. 처리된 변동의 것을 한 번에 읽는다 (D2 AD-385).
		Map<String, Integer> actionCounts = this.actions.actionCounts(edition.packId(),
				events.stream().filter(event -> event.status() == ChangeEventStatus.PROCESSED).map(ChangeEvent::id).toList());
		return EditionSubmissionResponse.stored(edition, this.editions.items(edition.id()).size(), events,
				this.editions.notices(edition.id()), this.packs.find(edition.packId()), entities, failures, actionCounts);
	}

	/** 규격의 EditionComparison. 순서는 link 가 준 그대로다 (항목의 키의 글자 코드 순). */
	record ComparisonResponse(String packId, ComparedEdition from, ComparedEdition to, List<ComparedChange> changes) {
	}

	/** 견준 판 하나. */
	record ComparedEdition(String editionId, String inputSourceId, String inputSourceLabel, int editionNo,
			LocalDate effectiveDate) {
	}

	/**
	 * 규격의 ComparedChange. 판 하나의 바뀐 항목(EditionChange)과 같은 항목이고, 변동 이벤트와 처리 결과가 없다:
	 * 견준 것은 저장되지 않는다.
	 *
	 * @param changedValues 그 행에서 바뀐 속성 값. 값을 남기기 전에 들어간 판과 견줬으면 null 이다 (D2 AD-367)
	 */
	record ComparedChange(String itemKey, String kind, String primaryEntityId, EntityViews.Item primaryEntity,
			List<String> targetEntityIds, List<EntityViews.Item> targetEntities, List<String> changedAttributes,
			List<String> changedRelationTypes, List<EditionSubmissionResponse.ChangedValue> changedValues) {
	}

	/**
	 * 같은 입력 자료의 두 판을 견준다 (규격의 compareEditions, D7 5절 "두 판의 비교", D2 AD-418). 읽기만 한다:
	 * 견준 것을 저장하지 않고 변동 이벤트, 영향 범위, 조치를 만들지 않는다. 견주는 일은 link 가 한다.
	 */
	@GetMapping("/api/v1/edition-comparison")
	@Audited(action = "compareEditions", params = { "fromEditionId", "toEditionId" })
	ComparisonResponse compare(@RequestParam(required = false) String fromEditionId,
			@RequestParam(required = false) String toEditionId) {
		require(fromEditionId, "MISSING_FROM_EDITION_ID", "fromEditionId",
				"앞의 판의 식별자가 없습니다. fromEditionId 에 판의 식별자를 넣어 다시 보내세요.");
		require(toEditionId, "MISSING_TO_EDITION_ID", "toEditionId",
				"뒤의 판의 식별자가 없습니다. toEditionId 에 판의 식별자를 넣어 다시 보내세요.");
		Edition from = comparedEdition(fromEditionId, "fromEditionId", "앞의 판");
		Edition to = comparedEdition(toEditionId, "toEditionId", "뒤의 판");
		if (!from.packId().equals(to.packId()) || !from.inputSourceId().equals(to.inputSourceId())) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "DIFFERENT_INPUT_SOURCES", "toEditionId",
					"두 판이 같은 입력 자료의 것이 아닙니다. 같은 Pack 의 같은 입력 자료의 두 판을 골라 다시 보내세요.");
		}
		EditionComparison compared = this.comparisons.compare(from.id(), to.id());
		// 개체는 유형, 키, 표시 이름과 함께 준다. 표시 이름은 뒤에 적은 판의 적용일의 값이다.
		TreeSet<String> ids = new TreeSet<>();
		for (ItemChange change : compared.changes()) {
			ids.add(change.primaryEntityId());
			ids.addAll(change.targetEntityIds());
			if (change.changedValues() != null) {
				change.changedValues().forEach(value -> ids.add(value.entityId()));
			}
		}
		Map<String, EntityViews.Item> entities = this.entityViews.items(ids, compared.to().effectiveDate());
		Optional<PackDefinition> pack = this.packs.find(from.packId());
		InputSourceLabels labels = this.entityViews.inputSourceLabels();
		List<ComparedChange> changes = new ArrayList<>();
		for (ItemChange change : compared.changes()) {
			changes.add(new ComparedChange(change.itemKey(), change.kind().name(), change.primaryEntityId(),
					EntityViews.require(entities, change.primaryEntityId(), "주 개체"), change.targetEntityIds(),
					change.targetEntityIds().stream().map(id -> EntityViews.require(entities, id, "변동 대상")).toList(),
					change.changedAttributes(), change.changedRelationTypes(),
					EditionSubmissionResponse.ChangedValue.of(change.changedValues(), pack, entities)));
		}
		return new ComparisonResponse(from.packId(), comparedEdition(compared.from(), labels),
				comparedEdition(compared.to(), labels), changes);
	}

	private Edition comparedEdition(String editionId, String field, String what) {
		// NUL 이 든 식별자의 판은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		return (editionId.indexOf('\u0000') >= 0 ? Optional.<Edition>empty() : this.editions.findEdition(editionId))
			.orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "EDITION_NOT_FOUND", field,
					what + "이 없습니다. 판의 식별자를 확인하세요."));
	}

	private static ComparedEdition comparedEdition(Edition edition, InputSourceLabels labels) {
		return new ComparedEdition(edition.id(), edition.inputSourceId(),
				labels.label(edition.packId(), edition.inputSourceId()), edition.editionNo(), edition.effectiveDate());
	}

	@PostMapping(path = "/api/v1/editions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Audited(action = "submitEdition", params = { "packId", "inputSourceId", "effectiveDate" })
	EditionSubmissionResponse submit(@RequestParam(required = false) String packId,
			@RequestParam(required = false) String inputSourceId, @RequestParam(required = false) String effectiveDate,
			@RequestParam(required = false) MultipartFile file) {
		require(packId, "MISSING_PACK_ID", "packId", "Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		require(inputSourceId, "MISSING_INPUT_SOURCE_ID", "inputSourceId",
				"입력 자료의 식별자가 없습니다. inputSourceId 에 입력 자료의 식별자를 넣어 다시 보내세요.");
		require(effectiveDate, "MISSING_EFFECTIVE_DATE", "effectiveDate",
				"적용일이 없습니다. effectiveDate 에 2026-03-01 과 같이 적어 다시 보내세요.");
		LocalDate date;
		try {
			date = LocalDate.parse(effectiveDate, DATE);
		}
		catch (DateTimeParseException e) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_EFFECTIVE_DATE", "effectiveDate",
					"적용일의 형식이 틀렸습니다. 2026-03-01 과 같이 적어 다시 보내세요.");
		}
		if (file == null) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_FILE", "file",
					"파일이 없습니다. file 에 올릴 파일을 넣어 다시 보내세요.");
		}
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException e) {
			throw new IllegalStateException("올린 파일을 읽지 못했습니다.", e);
		}
		EditionProcessingResult result = this.processing.submit(packId, inputSourceId, fileName(file), content, date);
		IngestResult ingest = result.ingest();
		// 받아들였는지와 어느 판이 됐는지를 감사 기록에 남긴다. 파일의 이름과 내용은 남기지 않는다.
		AuditTrail.detail("outcome", ingest.outcome().name());
		AuditTrail.detail("editionId", ingest.editionId());
		if (ingest.outcome() == IngestOutcome.REJECTED) {
			if (FILE_TOO_LARGE.equals(ingest.reasonCode())) {
				throw new EditionRejectedException(ingest.reasonCode(), ingest.reasonDetail(), true,
						"파일이 너무 큽니다. 받는 크기의 한도는 " + this.processing.maxFileBytes()
								+ " 바이트입니다. 파일을 나누거나 줄여 다시 올리세요.");
			}
			throw new EditionRejectedException(ingest.reasonCode(), ingest.reasonDetail(), false,
					EditionRejections.message(ingest.reasonCode(), ingest.reasonDetail()));
		}
		Edition edition = this.editions.findEdition(ingest.editionId())
			.orElseThrow(() -> new IllegalStateException("투입한 판 " + ingest.editionId() + " 을 읽지 못했습니다."));
		// 바뀐 값에 붙일 속성의 이름표는 Pack 의 정의에서 읽는다 (D2 AD-367).
		// 개체는 유형, 키, 표시 이름과 함께 준다. 표시 이름은 그 판의 적용일의 값이다 (D2 AD-349, AD-381).
		Map<String, EntityViews.Item> entities = this.entityViews
			.items(EditionSubmissionResponse.entityIdsOf(result.editionChanges()), edition.effectiveDate());
		return EditionSubmissionResponse.of(result, edition, result.editionChanges(), this.packs.find(packId), entities);
	}

	private static void require(String value, String code, String field, String detail) {
		if (value == null || value.isBlank()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, code, field, detail);
		}
	}

	/** 올린 파일의 이름. 브라우저에 따라 폴더까지 붙여 보내므로 마지막 부분만 남긴다. 없으면 null. */
	private static String fileName(MultipartFile file) {
		String name = file.getOriginalFilename();
		if (name == null) {
			return null;
		}
		name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
		return name.isEmpty() ? null : name;
	}
}
