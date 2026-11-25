package com.altumlab.tabnit.api;

import java.util.TreeMap;
import java.util.TreeSet;
import com.altumlab.tabnit.link.ChangeEvent;
import com.altumlab.tabnit.link.Edition;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 처리되지 않은 변동을 실패한 까닭과 함께 보고, 다시 처리하는 API. 규격은 docs/api/openapi.yaml 의
 * listUnprocessedChanges 와 reprocessChanges, 규칙은 D7 5절 "처리되지 않은 변동과 다시 처리"다 (D2 AD-321, AD-345).
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 무엇이 처리되지 않은 것이고 어떤 차례인지는 link 가, 다시 처리는 처리 서비스가
 * 한다. 다시 처리는 관리자만 한다 (역할은 보안 설정이 확인한다).
 */
@RestController
class UnprocessedChangesController {

	static final String LIST_PATH = "/api/v1/unprocessed-changes";

	static final String REPROCESS_PATH = "/api/v1/change-reprocessings";

	private final EditionProcessing processing;

	private final EntityViews entityViews;

	UnprocessedChangesController(EditionProcessing processing, EntityViews entityViews) {
		this.processing = processing;
		this.entityViews = entityViews;
	}

	/** 규격의 UnprocessedChangeList. 순서는 처리 서비스가 준 그대로다. */
	record ListResponse(String packId, List<Item> changes) {
	}

	/** 규격의 UnprocessedChange. */
	record Item(String changeEventId, String editionId, String inputSourceId, int editionNo, LocalDate effectiveDate,
			String itemKey, String kind, String primaryEntityId, EntityViews.Item primaryEntity,
			EditionSubmissionResponse.Processing processing) {
	}

	/** 규격의 ChangeReprocessing. 순서는 처리한 차례다. 수는 목록에서 센 것이다: 화면이 다시 세지 않게 함께 준다. */
	record ReprocessResponse(String packId, int processedCount, int failedCount,
			List<EditionSubmissionResponse.Retried> changes) {
	}

	@GetMapping(LIST_PATH)
	@Audited(action = "listUnprocessedChanges", params = "packId")
	ListResponse list(@RequestParam(required = false) String packId) {
		if (packId == null || packId.isBlank()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		}
		List<EditionProcessing.UnprocessedChange> unprocessed = this.processing.unprocessed(packId);
		// 주 개체는 유형, 키, 표시 이름과 함께 준다. 표시 이름은 그 변동이 나온 판의 적용일의 값이다 (D2 AD-381).
		// 적용일마다 한 번씩 읽는다. 찾는 데만 쓴다.
		Map<LocalDate, TreeSet<String>> idsByDate = new TreeMap<>();
		for (EditionProcessing.UnprocessedChange change : unprocessed) {
			idsByDate.computeIfAbsent(change.edition().effectiveDate(), date -> new TreeSet<>())
				.add(change.event().primaryEntityId());
		}
		Map<LocalDate, Map<String, EntityViews.Item>> entitiesByDate = new TreeMap<>();
		idsByDate.forEach((date, ids) -> entitiesByDate.put(date, this.entityViews.items(ids, date)));
		List<Item> items = new ArrayList<>();
		for (EditionProcessing.UnprocessedChange change : unprocessed) {
			ChangeEvent event = change.event();
			Edition edition = change.edition();
			items.add(new Item(event.id(), edition.id(), edition.inputSourceId(), edition.editionNo(),
					edition.effectiveDate(), event.itemKey(), event.kind().name(), event.primaryEntityId(),
					EntityViews.require(entitiesByDate.get(edition.effectiveDate()), event.primaryEntityId(), "주 개체"),
					new EditionSubmissionResponse.Processing(event.status().name(), event.failedStage(),
							event.failureCode(), EditionSubmissionResponse.Failure.of(change.failures()), 0)));
		}
		return new ListResponse(packId, items);
	}

	@PostMapping(REPROCESS_PATH)
	@Audited(action = "reprocessChanges")
	ReprocessResponse reprocess(@RequestBody Map<String, Object> request) {
		Object packId = request.get("packId");
		if (packId == null || (packId instanceof String text && text.isBlank())) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"다시 처리할 Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		}
		// 글자가 아닌 값(수, 목록)은 글자로 바꿔 받는다: 그런 식별자의 Pack 은 없으므로 없는 Pack 으로 답한다.
		String name = packId instanceof String text ? text : String.valueOf(packId);
		AuditTrail.target("packId", name);
		List<EditionSubmissionResponse.Retried> changes = new ArrayList<>();
		int processed = 0;
		for (ProcessedChange change : this.processing.processPending(name)) {
			if (!change.processed() && change.failedStage() == null) {
				// 결과를 표시하지 못한 것은 넣지 않는다 (상태가 바뀌지 않았다).
				continue;
			}
			processed += change.processed() ? 1 : 0;
			changes.add(new EditionSubmissionResponse.Retried(change.changeEventId(), change.editionId(),
					change.itemKey(), EditionSubmissionResponse.Processing.of(change)));
		}
		AuditTrail.detail("reprocessed", Integer.toString(changes.size()));
		AuditTrail.detail("processed", Integer.toString(processed));
		return new ReprocessResponse(name, processed, changes.size() - processed, changes);
	}
}
