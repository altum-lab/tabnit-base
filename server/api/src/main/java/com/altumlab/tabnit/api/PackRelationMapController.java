package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.PackRelationMap;
import com.altumlab.tabnit.core.PackRelationMapQuery;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pack 의 전체 지도 API. 규격은 docs/api/openapi.yaml 의 getPackRelationMap, 규칙은 D7 4절 "Pack 의 전체 지도"다
 * (카드 A-20, D2 AD-419).
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 무엇이 이어져 있는지, 묶을지, 수, 순서는 core 가 정한다. 이 클래스는 순서를
 * 바꾸지 않고 수를 다시 세지 않는다. 노드의 한도는 개체 하나의 둘레의 지도와 같은 값이다 (계약의 값).
 */
@RestController
class PackRelationMapController {

	/** 개체 하나하나를 담는 한도. 개체 하나의 둘레의 지도와 같다. */
	static final int MAX_NODES = RelationMapController.MAX_NODES;

	private final PackRelationMapQuery mapQuery;

	private final PackDefinitions packs;

	private final EntityViews entityViews;

	private final Clock clock;

	private final ZoneId installationZone;

	PackRelationMapController(PackRelationMapQuery mapQuery, PackDefinitions packs, EntityViews entityViews, Clock clock,
			ZoneId installationZone) {
		this.mapQuery = mapQuery;
		this.packs = packs;
		this.entityViews = entityViews;
		this.clock = clock;
		this.installationZone = installationZone;
	}

	/** 규격의 PackRelationMap. */
	record Response(String packId, String asOf, int maxNodes, int connectedEntities, int isolatedEntities, int relations,
			boolean grouped, List<TypeGroup> typeGroups, List<RelationGroup> relationGroups, List<Node> nodes,
			List<RelationMapResponse.EdgeItem> edges) {
	}

	/** 유형 하나의 묶음. 이름표는 Pack 의 정의에서 온다: 없으면 유형의 이름이다. */
	record TypeGroup(String entityType, String entityTypeLabel, int entityCount) {
	}

	record RelationGroup(String sourceEntityType, String relationType, String targetEntityType, int relationCount) {
	}

	record Node(String entityId, EntityViews.Item entity) {
	}

	@GetMapping("/api/v1/pack-relation-map")
	@Audited(action = "getPackRelationMap", params = "packId")
	Response packRelationMap(@RequestParam(required = false) String packId, @RequestParam(required = false) String asOf) {
		if (packId == null || packId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		}
		LocalDate date = resolveAsOf(asOf);
		// 날짜를 읽자마자 알린다: 그 뒤에 거부되는 요청(없는 Pack)의 감사 기록에도 조회 시점이 남는다.
		AuditTrail.asOf(date);
		// NUL 이 든 식별자의 Pack 은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		PackDefinition definition = (packId.indexOf('\u0000') >= 0 ? java.util.Optional.<PackDefinition>empty()
				: this.packs.find(packId))
			.orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "packId",
					"Pack " + packId + " 이 적재되어 있지 않습니다. Pack 의 식별자를 확인하세요."));
		PackRelationMap map = this.mapQuery.packRelationMap(packId, date, MAX_NODES);
		// 담긴 노드의 개체를 한 번에 읽어 유형, 키, 조회 시점의 표시 이름을 붙인다 (D2 AD-349).
		TreeSet<String> ids = new TreeSet<>();
		map.nodes().forEach(node -> ids.add(node.entityId()));
		Map<String, EntityViews.Item> entities = this.entityViews.items(ids, date);
		InputSourceLabels labels = this.entityViews.inputSourceLabels();
		return new Response(packId, date.toString(), MAX_NODES, map.connectedEntities(), map.isolatedEntities(),
				map.relations(), map.grouped(),
				map.typeGroups().stream()
					.map(group -> new TypeGroup(group.entityType(),
							definition.entityTypeLabelOf(group.entityType()).orElse(group.entityType()), group.entityCount()))
					.toList(),
				map.relationGroups().stream()
					.map(group -> new RelationGroup(group.sourceEntityType(), group.relationType(), group.targetEntityType(),
							group.relationCount()))
					.toList(),
				map.nodes().stream()
					.map(node -> new Node(node.entityId(), EntityViews.require(entities, node.entityId(), "전체 지도의 노드")))
					.toList(),
				map.edges().stream().map(relation -> RelationMapResponse.EdgeItem.of(relation, labels)).toList());
	}

	/**
	 * 조회 시점을 정한다. 요청에 없으면 여기서 한 번만 오늘로 정하고, 응답에 그 날짜를 담는다 (D2 AD-23).
	 * 오늘은 설치 설정의 시간대에서의 날짜다 (D2 AD-40).
	 */
	private LocalDate resolveAsOf(String asOf) {
		if (asOf == null) {
			return LocalDate.now(clock.withZone(installationZone));
		}
		LocalDate date;
		try {
			date = LocalDate.parse(asOf);
		}
		catch (DateTimeParseException e) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_AS_OF", "asOf",
					"조회 시점의 형식이 틀렸습니다. 2026-03-01 과 같이 적어 다시 보내세요.");
		}
		// 받는 날짜는 0001-01-01 부터 9999-12-31 까지다 (D7 2절, D2 AD-370). 그 밖의 날짜는 엔진이 받지 않는다.
		if (date.getYear() < 1 || date.getYear() > 9999) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_AS_OF", "asOf",
					"조회 시점 " + asOf + " 은(는) 쓸 수 없는 날짜입니다. 0001-01-01 부터 9999-12-31 까지의 날짜를 넣어 다시 보내세요.");
		}
		return date;
	}
}
