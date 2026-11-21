package com.altumlab.tabnit.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 조치 목록의 응답 (docs/api/openapi.yaml 의 ActionList, D7 6절).
 *
 * <p>목록의 순서는 rules 가 준 순서 그대로다. 여기서 바꾸지 않는다.
 */
record ActionListResponse(String packId, String status, String changeEventId, int total, List<Item> actions) {

	record Entity(String entityId, String entityType) {
	}

	record Basis(Entity entity, String inputSourceId, int editionNo, LocalDate effectiveDate) {
	}

	record Item(String id, String changeEventId, String ruleNo, String ruleLabel, String actionText, int priority,
			LocalDate dueDate, String status, String appliesTo, Entity target, Basis basis, Instant createdAt) {
	}

	/**
	 * @param items 응답의 모양으로 옮긴 조치. rules 가 준 순서 그대로다
	 */
	static ActionListResponse of(String packId, String status, String changeEventId, List<Item> items) {
		return new ActionListResponse(packId, status, changeEventId, items.size(), List.copyOf(items));
	}
}
