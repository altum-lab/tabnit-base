package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.RelationMap;
import com.altumlab.tabnit.core.RelationMapNode;
import com.altumlab.tabnit.core.RelationMapQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.TreeSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관계 지도 API. 규격은 docs/api/openapi.yaml 의 getRelationMap, 규칙은 D7 4절 "관계 지도"다 (D2 AD-352).
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 무엇이 이어져 있는지, 순서, 자르는 일은 core 가 정한다.
 * 이 클래스는 노드와 간선의 순서를 바꾸지 않고 수를 다시 세지 않는다. 깊이의 기본값과 한도, 노드의 한도는
 * 계약이 정한 값이고 여기서 core 에 넘긴다 (core 에는 기본값이 없다).
 */
@RestController
class RelationMapController {

	/** 요청에 depth 가 없을 때의 값. */
	static final int DEFAULT_DEPTH = 1;

	/** 따라갈 수 있는 가장 큰 깊이. */
	static final int MAX_DEPTH = 3;

	/** 지도 하나에 담는 노드의 한도. 가운데 개체를 넣어 센다. */
	static final int MAX_NODES = 200;

	private final RelationMapQuery mapQuery;

	private final EntityViews entityViews;

	private final Clock clock;

	private final ZoneId installationZone;

	RelationMapController(RelationMapQuery mapQuery, EntityViews entityViews, Clock clock, ZoneId installationZone) {
		this.mapQuery = mapQuery;
		this.entityViews = entityViews;
		this.clock = clock;
		this.installationZone = installationZone;
	}

	@GetMapping("/api/v1/relation-map")
	@Audited(action = "getRelationMap", params = "entityId")
	RelationMapResponse relationMap(@RequestParam(required = false) String entityId,
			@RequestParam(required = false) String asOf, @RequestParam(required = false) String depth) {
		if (entityId == null || entityId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_ENTITY_ID", "entityId",
					"개체의 id 가 없습니다. entityId 에 가운데에 둘 개체의 id 를 넣어 다시 보내세요.");
		}
		LocalDate date = resolveAsOf(asOf);
		int levels = resolveDepth(depth);
		AuditTrail.asOf(date);
		RelationMap map = this.mapQuery.relationMap(entityId, date, levels, MAX_NODES);
		// 담긴 노드의 개체를 한 번에 읽어 유형, 키, 조회 시점의 표시 이름을 붙인다 (D2 AD-349).
		TreeSet<String> ids = new TreeSet<>();
		for (RelationMapNode node : map.nodes()) {
			ids.add(node.entityId());
		}
		return RelationMapResponse.of(date, entityId, levels, MAX_NODES, map, this.entityViews.items(ids, date));
	}

	/**
	 * 깊이를 정한다. 없으면 기본값이다. 영어 숫자 한 글자로 적은 1 부터 {@value #MAX_DEPTH} 까지만 받는다:
	 * 틀린 값을 조용히 고쳐 받지 않는다.
	 */
	private static int resolveDepth(String depth) {
		if (depth == null) {
			return DEFAULT_DEPTH;
		}
		if (depth.length() == 1 && depth.charAt(0) >= '1' && depth.charAt(0) <= '0' + MAX_DEPTH) {
			return depth.charAt(0) - '0';
		}
		throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_DEPTH", "depth",
				"깊이(depth)의 값이 틀렸습니다. 1 부터 " + MAX_DEPTH + " 까지의 정수를 넣어 다시 보내세요.");
	}

	/**
	 * 조회 시점을 정한다. 요청에 없으면 여기서 한 번만 오늘로 정하고, 응답에 그 날짜를 담는다 (D2 AD-23).
	 * 오늘은 설치 설정의 시간대에서의 날짜다 (D2 AD-40).
	 */
	private LocalDate resolveAsOf(String asOf) {
		if (asOf == null) {
			return LocalDate.now(clock.withZone(installationZone));
		}
		try {
			return LocalDate.parse(asOf);
		}
		catch (DateTimeParseException e) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_AS_OF", "asOf",
					"조회 시점의 형식이 틀렸습니다. 2026-03-01 과 같이 적어 다시 보내세요.");
		}
	}

}
