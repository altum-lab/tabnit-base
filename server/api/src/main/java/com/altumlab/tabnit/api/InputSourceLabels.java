package com.altumlab.tabnit.api;

import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 입력 자료의 이름표를 찾는다 (D7 2절 "입력 자료", D2 AD-384).
 *
 * <p>이름표는 Pack 의 매핑에 적은 화면에 보일 이름이다. 적지 않았거나, 그 Pack 이 적재되어 있지 않거나, 그 Pack 에 그런
 * 입력 자료가 없으면 (정의 없이 넣은 자료) 입력 자료의 식별자를 그대로 준다. 지어내지 않는다. 저장소를 읽지 못한 것은
 * 식별자로 대신하지 않는다: 오류로 알린다.
 * 저장하지 않고 볼 때의 정의로 읽는다: Pack 을 다시 적재해 이름표를 바꾸면 지난 것도 새 이름표로 보인다 (D2 AD-349 와 같다).
 *
 * <p>답 하나를 만드는 동안만 쓴다. Pack 의 정의는 Pack 마다 한 번만 읽는다.
 */
final class InputSourceLabels {

	private final PackDefinitions packs;

	/** Pack 에서 그 입력 자료들의 이름표. 찾는 데만 쓴다. */
	private final Map<String, Map<String, String>> byPack = new TreeMap<>();

	InputSourceLabels(PackDefinitions packs) {
		this.packs = packs;
	}

	/** 이미 읽은 정의로 만든다. 그 Pack 은 다시 읽지 않는다. */
	InputSourceLabels(PackDefinitions packs, String packId, Optional<PackDefinition> pack) {
		this.packs = packs;
		this.byPack.put(packId, read(pack));
	}

	String label(String packId, String inputSourceId) {
		return this.byPack.computeIfAbsent(packId, id -> read(this.packs.find(id))).getOrDefault(inputSourceId, inputSourceId);
	}

	private static Map<String, String> read(Optional<PackDefinition> pack) {
		Map<String, String> labels = new TreeMap<>();
		pack.ifPresent(definition -> {
			for (MappingDefinition mapping : definition.mappings()) {
				labels.put(mapping.inputSourceId(), mapping.shownLabel());
			}
		});
		return labels;
	}
}
