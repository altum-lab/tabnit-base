package com.altumlab.tabnit.api;

import com.altumlab.tabnit.rules.Action;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
	 * @param basisTypes 근거가 되는 개체의 식별자마다의 개체 유형. 조치에는 저장되어 있지 않아 core 에서 읽은 것이다
	 */
	static ActionListResponse of(String packId, String status, String changeEventId, List<Action> actions,
			Map<String, String> basisTypes) {
		List<Item> items = new ArrayList<>();
		for (Action action : actions) {
			String basisType = basisTypes.get(action.basisEntityId());
			if (basisType == null) {
				// 근거 없는 조치는 저장되지 않는다. 여기 오면 저장소가 어긋난 것이다.
				throw new IllegalStateException(
						"조치 " + action.id() + " 의 근거가 되는 개체 " + action.basisEntityId() + " 가 저장소에 없습니다.");
			}
			items.add(new Item(action.id(), action.changeEventId(), action.ruleNo(), action.ruleLabel(), action.actionText(),
					action.priority(), action.dueDate(), action.status().name(), action.appliesTo().name(),
					new Entity(action.targetEntityId(), action.targetEntityType()),
					new Basis(new Entity(action.basisEntityId(), basisType), action.basisInputSourceId(),
							action.basisEditionNo(), action.effectiveDate()),
					action.createdAt()));
		}
		return new ActionListResponse(packId, status, changeEventId, items.size(), items);
	}
}
