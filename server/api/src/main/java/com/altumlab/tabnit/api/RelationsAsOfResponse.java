package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.Relation;
import java.time.LocalDate;
import java.util.List;

/**
 * 시점 조회의 응답. 규격의 RelationsAsOf 와 같은 모양이다.
 *
 * <p>날짜와 시각은 규격의 표기대로 글자로 담는다: 날짜는 2026-03-01, 시각은 협정 세계시 2026-01-01T00:00:00Z.
 *
 * @param asOf 답에 쓴 날짜. 요청에 있었든 없었든 담는다
 * @param entityId 조회한 개체의 id
 * @param relations 조회 시점에 유효한 관계. core 가 준 순서 그대로다
 */
record RelationsAsOfResponse(String asOf, String entityId, List<RelationItem> relations) {

	static RelationsAsOfResponse of(LocalDate asOf, String entityId, List<Relation> relations) {
		return new RelationsAsOfResponse(asOf.toString(), entityId,
				relations.stream().map(RelationItem::of).toList());
	}

	/**
	 * 규격의 Relation.
	 *
	 * @param validTo 종료일. 없으면 null 로 적는다. 항목을 빼지 않는다
	 */
	record RelationItem(String id, String relationType, String sourceEntityId, String targetEntityId,
			String validFrom, String validTo, ProvenanceItem provenance, String recordedAt) {

		static RelationItem of(Relation relation) {
			LocalDate to = relation.period().to();
			return new RelationItem(relation.id(), relation.relationType(), relation.sourceEntityId(),
					relation.targetEntityId(), relation.period().from().toString(), to == null ? null : to.toString(),
					new ProvenanceItem(relation.provenance().inputSourceId(), relation.provenance().editionNo()),
					relation.recordedAt().toString());
		}

	}

	/** 규격의 Provenance. */
	record ProvenanceItem(String inputSourceId, int editionNo) {
	}

}
