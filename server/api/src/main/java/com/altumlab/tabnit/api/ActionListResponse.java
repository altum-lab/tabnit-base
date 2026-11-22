package com.altumlab.tabnit.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 조치 목록의 응답 (docs/api/openapi.yaml 의 ActionList, D7 6절).
 *
 * <p>목록의 순서는 rules 가 준 순서 그대로다. 여기서 바꾸지 않는다.
 */
record ActionListResponse(String packId, String status, String changeEventId, int total, int offset, int limit,
		List<Item> actions) {

	record Entity(String entityId, String entityType) {
	}

	record Basis(Entity entity, String inputSourceId, int editionNo, LocalDate effectiveDate) {
	}

	record Item(String id, String changeEventId, String ruleNo, String ruleLabel, String actionText, int priority,
			LocalDate dueDate, String status, String appliesTo, Entity target, Basis basis, Instant createdAt) {
	}

	/**
	 * @param total 거른 조치의 전체 수. 나누기 전의 수이고 rules 가 센 값이다
	 * @param page 답에 쓴 건너뛴 수와 한 번에 주는 수
	 * @param items 응답의 모양으로 옮긴 이 쪽의 조치. rules 가 준 순서 그대로다
	 */
	static ActionListResponse of(String packId, String status, String changeEventId, int total, PageRequest page,
			List<Item> items) {
		return new ActionListResponse(packId, status, changeEventId, total, page.offset(), page.limit(),
				List.copyOf(items));
	}
}
