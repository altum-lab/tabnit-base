package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.Relation;
import com.altumlab.tabnit.core.RelationMap;
import com.altumlab.tabnit.core.RelationMapNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 관계 지도의 응답. 규격의 RelationMap 과 같은 모양이다 (D2 AD-352).
 *
 * <p>날짜와 시각은 규격의 표기대로 글자로 담는다: 날짜는 2026-03-01, 시각은 협정 세계시 2026-01-01T00:00:00Z.
 *
 * @param asOf 답에 쓴 날짜. 요청에 있었든 없었든 담는다
 * @param centerEntityId 가운데 개체의 id
 * @param depth 답에 쓴 깊이
 * @param maxNodes 담는 노드의 한도
 * @param totalNodes 자르기 전의 노드의 수. core 가 센 값이다
 * @param truncated 한도를 넘어 잘렸는가
 * @param nodes 노드. core 가 준 순서 그대로다
 * @param edges 간선. core 가 준 순서 그대로다
 */
record RelationMapResponse(String asOf, String centerEntityId, int depth, int maxNodes, int totalNodes, boolean truncated,
		List<NodeItem> nodes, List<EdgeItem> edges) {

	/**
	 * @param entities 담긴 노드의 개체. 식별자로 찾는다. 표시 이름은 조회 시점의 값이다
	 */
	static RelationMapResponse of(LocalDate asOf, String centerEntityId, int depth, int maxNodes, RelationMap map,
			Map<String, EntityViews.Item> entities) {
		return new RelationMapResponse(asOf.toString(), centerEntityId, depth, maxNodes, map.totalNodes(), map.truncated(),
				map.nodes().stream().map(node -> NodeItem.of(node, entities)).toList(),
				map.edges().stream().map(EdgeItem::of).toList());
	}

	/** 규격의 RelationMapNode. */
	record NodeItem(String entityId, EntityViews.Item entity, int depth) {

		static NodeItem of(RelationMapNode node, Map<String, EntityViews.Item> entities) {
			return new NodeItem(node.entityId(), EntityViews.require(entities, node.entityId(), "관계 지도의 노드"),
					node.depth());
		}
	}

	/**
	 * 규격의 RelationMapEdge. 두 끝의 개체는 노드에 있으므로 여기에는 식별자만 담는다.
	 *
	 * @param validTo 종료일. 없으면 null 로 적는다. 항목을 빼지 않는다
	 */
	record EdgeItem(String id, String relationType, String sourceEntityId, String targetEntityId, String validFrom,
			String validTo, RelationsAsOfResponse.ProvenanceItem provenance, String recordedAt) {

		static EdgeItem of(Relation relation) {
			LocalDate to = relation.period().to();
			return new EdgeItem(relation.id(), relation.relationType(), relation.sourceEntityId(),
					relation.targetEntityId(), relation.period().from().toString(), to == null ? null : to.toString(),
					new RelationsAsOfResponse.ProvenanceItem(relation.provenance().inputSourceId(),
							relation.provenance().editionNo()),
					relation.recordedAt().toString());
		}
	}
}
