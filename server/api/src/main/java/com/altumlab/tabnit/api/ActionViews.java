package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.EntityQuery;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.rules.Action;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 조치를 응답의 모양으로 옮긴다 (docs/api/openapi.yaml 의 Action). 조치 목록과 조치 하나의 응답이 함께 쓴다.
 *
 * <p>근거가 되는 개체의 유형은 조치에 저장되어 있지 않아 core 에서 읽어 붙인다. 순서는 받은 그대로다.
 */
class ActionViews {

	private final EntityQuery entities;

	ActionViews(EntityQuery entities) {
		this.entities = entities;
	}

	List<ActionListResponse.Item> items(List<Action> actions) {
		// 근거가 되는 개체를 한 번에 읽는다. 찾는 데만 쓴다.
		TreeSet<String> basisIds = new TreeSet<>();
		for (Action action : actions) {
			basisIds.add(action.basisEntityId());
		}
		Map<String, String> basisTypes = new TreeMap<>();
		for (EntityRef entity : this.entities.findAll(basisIds)) {
			basisTypes.put(entity.id(), entity.entityType());
		}
		List<ActionListResponse.Item> items = new ArrayList<>();
		for (Action action : actions) {
			String basisType = basisTypes.get(action.basisEntityId());
			if (basisType == null) {
				// 근거 없는 조치는 저장되지 않는다. 여기 오면 저장소가 어긋난 것이다.
				throw new IllegalStateException(
						"조치 " + action.id() + " 의 근거가 되는 개체 " + action.basisEntityId() + " 가 저장소에 없습니다.");
			}
			items.add(new ActionListResponse.Item(action.id(), action.changeEventId(), action.ruleNo(), action.ruleLabel(),
					action.actionText(), action.priority(), action.dueDate(), action.status().name(),
					action.appliesTo().name(), new ActionListResponse.Entity(action.targetEntityId(), action.targetEntityType()),
					new ActionListResponse.Basis(new ActionListResponse.Entity(action.basisEntityId(), basisType),
							action.basisInputSourceId(), action.basisEditionNo(), action.effectiveDate()),
					action.createdAt()));
		}
		return items;
	}

	ActionListResponse.Item item(Action action) {
		return items(List.of(action)).get(0);
	}
}
