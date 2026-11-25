package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.AttributeValue;
import com.altumlab.tabnit.core.AttributeValueQuery;
import com.altumlab.tabnit.core.EntityQuery;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 개체를 응답의 모양으로 옮긴다 (docs/api/openapi.yaml 의 EntityRef, D7 2절 "개체", D2 AD-349).
 *
 * <p>받고, 넘기고, 돌려준다. 유형과 키는 core 가, 유형의 이름표와 표시 이름으로 쓸 속성은 pack 이, 그 속성의 값은
 * core 가 안다. 여기서는 그것을 읽어 모을 뿐이다. 표시 이름은 넘겨받은 기준 날짜에 유효한 값이다: 없으면 null 이고
 * 앞의 값을 끌어다 쓰지 않는다 (D2 AD-55).
 */
class EntityViews {

	/** 규격의 EntityRef. */
	record Item(String entityId, String entityType, String entityTypeLabel, String entityKey, String displayName) {
	}

	private final EntityQuery entities;

	private final AttributeValueQuery values;

	private final PackDefinitions packs;

	EntityViews(EntityQuery entities, AttributeValueQuery values, PackDefinitions packs) {
		this.entities = entities;
		this.values = values;
		this.packs = packs;
	}

	/**
	 * 개체 여럿을 한 번에 읽어 옮긴다. 찾는 데 쓰는 모음을 돌려준다: 차례는 부르는 쪽이 정한다.
	 *
	 * @param entityIds 개체의 식별자. 겹쳐도 된다
	 * @param asOf 표시 이름을 읽을 기준 날짜
	 * @return 식별자로 찾는 개체. 저장소에 없는 개체는 들어 있지 않다
	 */
	Map<String, Item> items(Collection<String> entityIds, LocalDate asOf) {
		TreeSet<String> ids = new TreeSet<>(entityIds);
		List<EntityRef> found = this.entities.findAll(ids);
		// Pack 의 정의는 Pack 마다 한 번만 읽는다. 적재되지 않은 Pack 의 개체도 있을 수 있다 (정의 없이 넣은 자료).
		Map<String, Optional<PackDefinition>> definitions = new TreeMap<>();
		// 기준 날짜에 유효한 값을 한 번에 읽어 (개체, 속성)으로 찾는다.
		Map<String, Map<String, String>> valuesByEntity = new TreeMap<>();
		for (AttributeValue value : this.values.valuesAsOf(found.stream().map(EntityRef::id).toList(), asOf)) {
			valuesByEntity.computeIfAbsent(value.entityId(), key -> new TreeMap<>()).put(value.attributeName(), value.value());
		}
		Map<String, Item> items = new TreeMap<>();
		for (EntityRef entity : found) {
			Optional<PackDefinition> pack = definitions.computeIfAbsent(entity.packId(), this.packs::find);
			// 이름표를 알 수 없으면 유형의 이름을 그대로 준다.
			String label = pack.flatMap(definition -> definition.entityTypeLabelOf(entity.entityType()))
				.orElse(entity.entityType());
			String displayName = pack.flatMap(definition -> definition.displayNameAttributeOf(entity.entityType()))
				.map(attribute -> valuesByEntity.getOrDefault(entity.id(), Map.of()).get(attribute))
				.orElse(null);
			items.put(entity.id(), new Item(entity.id(), entity.entityType(), label, entity.entityKey(), displayName));
		}
		return items;
	}

	/** 입력 자료의 이름표를 찾는 것. 답 하나를 만드는 동안 쓴다 (D2 AD-384). */
	InputSourceLabels inputSourceLabels() {
		return new InputSourceLabels(this.packs);
	}

	/** 찾은 것에서 하나를 꺼낸다. 없으면 저장소가 어긋난 것이다: 근거 없이 지어내지 않는다. */
	static Item require(Map<String, Item> items, String entityId, String what) {
		Item item = items.get(entityId);
		if (item == null) {
			throw new IllegalStateException(what + " " + entityId + " 가 저장소에 없습니다. 저장소의 상태를 확인하세요.");
		}
		return item;
	}
}
