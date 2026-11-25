package com.altumlab.tabnit.api;

import com.altumlab.tabnit.pack.ValueFormat;
import java.util.TreeSet;
import com.altumlab.tabnit.link.ChangeEvent;
import com.altumlab.tabnit.link.ChangeEventStatus;
import com.altumlab.tabnit.link.ChangeFailureDetail;
import com.altumlab.tabnit.link.Edition;
import com.altumlab.tabnit.link.IngestNotice;
import com.altumlab.tabnit.link.IngestOutcome;
import com.altumlab.tabnit.link.MappingOutcome;
import com.altumlab.tabnit.link.ValueChange;
import com.altumlab.tabnit.pack.PackDefinition;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 판 올리기의 응답 (docs/api/openapi.yaml 의 EditionSubmission, D7 5절).
 *
 * <p>목록의 순서는 link 와 처리 서비스가 준 순서 그대로다. 여기서 바꾸지 않는다.
 */
record EditionSubmissionResponse(String outcome, String editionId, String packId, String inputSourceId, int editionNo,
		LocalDate effectiveDate, int itemCount, List<Change> changes, List<Notice> notices, List<Retried> retriedChanges,
		boolean processingNotStarted) {

	/**
	 * @param changedValues 그 행에서 바뀐 속성 값. 이전 값을 알 수 없으면 null 이다 (D2 AD-367)
	 */
	record Change(String changeEventId, String itemKey, String kind, String primaryEntityId, EntityViews.Item primaryEntity,
			List<String> targetEntityIds, List<EntityViews.Item> targetEntities, List<ChangedValue> changedValues,
			Processing processing) {
	}

	/**
	 * 규격의 ChangedValue. 값과 차례는 link 가 준 그대로다. 이름표는 Pack 의 정의에서 온다: 없으면 속성의 이름이다.
	 *
	 * @param entity 그 개체를 유형, 키, 표시 이름과 함께 (D2 AD-381)
	 * @param beforeDisplay before 를 화면에 보일 글자. 참·거짓 속성이면 예, 아니오다. 그 밖에는 before 와 같다
	 * @param afterDisplay after 를 화면에 보일 글자
	 */
	record ChangedValue(String entityId, EntityViews.Item entity, String attributeName, String attributeLabel, String before,
			String after, String beforeDisplay, String afterDisplay) {

		/** 참과 거짓을 화면에 보일 글자 (D2 AD-381). 저장한 글자는 true, false 다 (D6 5절). */
		private static final String TRUE_DISPLAY = "예";

		private static final String FALSE_DISPLAY = "아니오";

		static List<ChangedValue> of(List<ValueChange> values, Optional<PackDefinition> pack,
				Map<String, EntityViews.Item> entities) {
			if (values == null) {
				return null;
			}
			// 이름표와 형식을 이름으로 찾는 데만 쓴다.
			Map<String, String> labels = new TreeMap<>();
			Map<String, ValueFormat> formats = new TreeMap<>();
			pack.ifPresent(definition -> definition.attributes().forEach(attribute -> {
				labels.put(attribute.name(), attribute.label());
				formats.put(attribute.name(), attribute.valueFormat());
			}));
			return values.stream().map(value -> {
				boolean truth = formats.get(value.attributeName()) == ValueFormat.BOOLEAN;
				return new ChangedValue(value.entityId(), EntityViews.require(entities, value.entityId(), "바뀐 값의 개체"),
						value.attributeName(), labels.getOrDefault(value.attributeName(), value.attributeName()),
						value.before(), value.after(), display(value.before(), truth), display(value.after(), truth));
			}).toList();
		}

		private static String display(String value, boolean truth) {
			if (!truth || value == null) {
				return value;
			}
			// 저장한 글자가 둘 가운데 하나가 아니면 (정의가 바뀌기 전에 들어간 값) 그대로 보인다.
			return "true".equals(value) ? TRUE_DISPLAY : "false".equals(value) ? FALSE_DISPLAY : value;
		}
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
	 * @param pack 그 Pack 의 정의. 속성의 이름표를 읽는다. 읽지 못했으면 비어 있다
	 * @param entities 변동 이벤트에 나오는 개체. 주 개체, 변동 대상, 바뀐 값의 개체를 식별자로 찾는다
	 */
	/** 변동 이벤트에 나오는 개체의 식별자: 주 개체, 변동 대상, 바뀐 값의 개체. 글자 순이다. */
	static TreeSet<String> entityIdsOf(List<ChangeEvent> events) {
		TreeSet<String> ids = new TreeSet<>();
		for (ChangeEvent event : events) {
			ids.add(event.primaryEntityId());
			ids.addAll(event.targetEntityIds());
			if (event.changedValues() != null) {
				event.changedValues().forEach(value -> ids.add(value.entityId()));
			}
		}
		return ids;
	}

	static EditionSubmissionResponse of(EditionProcessingResult result, Edition edition, List<ChangeEvent> events,
			Optional<PackDefinition> pack, Map<String, EntityViews.Item> entities) {
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
						EntityViews.require(entities, event.primaryEntityId(), "주 개체"), event.targetEntityIds(),
						event.targetEntityIds().stream().map(id -> EntityViews.require(entities, id, "변동 대상")).toList(),
						ChangedValue.of(event.changedValues(), pack, entities),
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
