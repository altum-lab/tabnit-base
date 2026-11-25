package com.altumlab.tabnit.api;

import com.altumlab.tabnit.rules.Action;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 조치를 응답의 모양으로 옮긴다 (docs/api/openapi.yaml 의 Action). 조치 목록과 조치 하나의 응답이 함께 쓴다.
 *
 * <p>대상과 근거가 되는 개체는 유형, 키, 표시 이름과 함께 준다 ({@link EntityViews}). 표시 이름은 그 조치의
 * 변동이 나온 판의 적용일의 값이다 (D2 AD-349). 순서는 받은 그대로다.
 */
class ActionViews {

	private final EntityViews entities;

	ActionViews(EntityViews entities) {
		this.entities = entities;
	}

	List<ActionListResponse.Item> items(List<Action> actions) {
		// 개체를 판의 적용일마다 한 번에 읽는다. 찾는 데만 쓴다. 날짜의 차례는 결과에 닿지 않는다.
		Map<LocalDate, TreeSet<String>> idsByDate = new TreeMap<>();
		for (Action action : actions) {
			TreeSet<String> ids = idsByDate.computeIfAbsent(action.effectiveDate(), date -> new TreeSet<>());
			ids.add(action.targetEntityId());
			ids.add(action.basisEntityId());
		}
		Map<LocalDate, Map<String, EntityViews.Item>> found = new TreeMap<>();
		idsByDate.forEach((date, ids) -> found.put(date, this.entities.items(ids, date)));
		InputSourceLabels labels = this.entities.inputSourceLabels();
		List<ActionListResponse.Item> items = new ArrayList<>();
		for (Action action : actions) {
			Map<String, EntityViews.Item> at = found.get(action.effectiveDate());
			// 근거 없는 조치는 저장되지 않는다. 개체가 없으면 저장소가 어긋난 것이다.
			EntityViews.Item target = EntityViews.require(at, action.targetEntityId(), "조치 " + action.id() + " 의 대상인 개체");
			EntityViews.Item basis = EntityViews.require(at, action.basisEntityId(),
					"조치 " + action.id() + " 의 근거가 되는 개체");
			items.add(new ActionListResponse.Item(action.id(), action.changeEventId(), action.ruleNo(), action.ruleLabel(),
					action.actionText(), action.priority(), action.dueDate(), action.status().name(),
					action.appliesTo().name(),
					// 유형, 이름표, 키를 모두 개체에서 읽은 것으로 준다. 조치에 저장된 대상의 유형과 같다:
					// 유형은 개체의 식별자에 들어 있어 바뀌지 않는다.
					target,
					new ActionListResponse.Basis(basis, action.basisInputSourceId(),
							labels.label(action.packId(), action.basisInputSourceId()), action.basisEditionNo(),
							action.effectiveDate()),
					action.createdAt()));
		}
		return items;
	}

	ActionListResponse.Item item(Action action) {
		return items(List.of(action)).get(0);
	}
}
