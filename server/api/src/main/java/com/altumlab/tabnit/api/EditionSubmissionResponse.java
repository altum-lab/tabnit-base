package com.altumlab.tabnit.api;

import com.altumlab.tabnit.link.ChangeEvent;
import com.altumlab.tabnit.link.ChangeEventStatus;
import com.altumlab.tabnit.link.ChangeFailureDetail;
import com.altumlab.tabnit.link.Edition;
import com.altumlab.tabnit.link.IngestNotice;
import com.altumlab.tabnit.link.IngestOutcome;
import com.altumlab.tabnit.link.MappingOutcome;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 판 올리기의 응답 (docs/api/openapi.yaml 의 EditionSubmission, D7 5절).
 *
 * <p>목록의 순서는 link 와 처리 서비스가 준 순서 그대로다. 여기서 바꾸지 않는다.
 */
record EditionSubmissionResponse(String outcome, String editionId, String packId, String inputSourceId, int editionNo,
		LocalDate effectiveDate, int itemCount, List<Change> changes, List<Notice> notices, List<Retried> retriedChanges,
		boolean processingNotStarted) {

	record Change(String changeEventId, String itemKey, String kind, String primaryEntityId, List<String> targetEntityIds,
			Processing processing) {
	}

	/** 규격의 ChangeProcessing. */
	record Processing(String status, String failedStage, String failureCode, List<Failure> failures, int actionCount) {

		/** 이 요청이 처리한 결과에서 만든다. */
		static Processing of(ProcessedChange change) {
			return new Processing(change.processed() ? ChangeEventStatus.PROCESSED.name() : ChangeEventStatus.FAILED.name(),
					change.failedStage(), change.failureCode(), Failure.of(change.failures()), change.actionCount());
		}
	}

	/** 평가에 실패한 규칙 하나. 규격의 ChangeFailure. */
	record Failure(String ruleNo, String code, String entityId, String attributeName, String value) {

		static List<Failure> of(List<ChangeFailureDetail> details) {
			return details.stream()
				.map(d -> new Failure(d.ruleNo(), d.code(), d.entityId(), d.attributeName(), d.value()))
				.toList();
		}
	}

	record Retried(String changeEventId, String editionId, String itemKey, Processing processing) {
	}

	/** 규격의 EditionNotice. 문구는 종류와 코드와 값을 풀어 쓴 것이다 (D2 AD-348). */
	record Notice(String kind, int rowNo, String code, String subject, String detail, String message) {
	}

	/**
	 * @param edition 만든 판, 또는 내용이 같았던 앞 판
	 * @param events 그 판의 저장된 변동 이벤트. 항목의 키의 글자 코드 순이다. 내용이 같았으면 넘기지 않는다
	 */
	static EditionSubmissionResponse of(EditionProcessingResult result, Edition edition, List<ChangeEvent> events) {
		boolean accepted = result.ingest().outcome() == IngestOutcome.ACCEPTED;
		MappingOutcome mapping = accepted ? result.ingest().mapping() : null;
		// 이 요청이 처리한 것을 변동 이벤트의 식별자로 찾는 데만 쓴다.
		Map<String, ProcessedChange> processed = new TreeMap<>();
		List<Retried> retried = new ArrayList<>();
		for (ProcessedChange change : result.changes()) {
			if (edition.id().equals(change.editionId())) {
				processed.put(change.changeEventId(), change);
			}
			else if (change.processed() || change.failedStage() != null) {
				// 앞선 판의 변동을 함께 다시 처리한 것. 결과를 표시하지 못한 것은 넣지 않는다 (상태가 바뀌지 않았다).
				retried.add(new Retried(change.changeEventId(), change.editionId(), change.itemKey(),
						Processing.of(change)));
			}
		}
		List<Change> changes = new ArrayList<>();
		if (accepted) {
			for (ChangeEvent event : events) {
				ProcessedChange done = processed.get(event.id());
				// 상태와 실패의 단계와 코드는 저장된 변동 이벤트의 것이다. 조치의 수와 평가에 실패한 규칙은 이 요청이
				// 처리한 결과에서 온다 (저장한 것과 같은 것이다).
				changes.add(new Change(event.id(), event.itemKey(), event.kind().name(), event.primaryEntityId(),
						event.targetEntityIds(),
						new Processing(event.status().name(), event.failedStage(), event.failureCode(),
								done == null || event.status() != ChangeEventStatus.FAILED ? List.of()
										: Failure.of(done.failures()),
								done == null ? 0 : done.actionCount())));
			}
		}
		List<Notice> notices = new ArrayList<>();
		if (mapping != null) {
			for (IngestNotice notice : mapping.notices()) {
				notices.add(new Notice(notice.kind(), notice.rowNo(), notice.code(), notice.subject(), notice.detail(),
						EditionNotices.message(notice)));
			}
		}
		return new EditionSubmissionResponse(result.ingest().outcome().name(), edition.id(), edition.packId(),
				edition.inputSourceId(), edition.editionNo(), edition.effectiveDate(),
				mapping == null ? 0 : mapping.items().size(), changes, notices, retried, result.processingNotStarted());
	}
}
