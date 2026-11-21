package com.altumlab.tabnit.pack;

import java.time.Instant;
import java.util.List;

/**
 * 적재된 Pack 의 정의 (D6 4절 ~ 5절).
 *
 * @param packId Pack 식별자
 * @param label 사람이 읽는 이름
 * @param defaultMaxDepth 영향 범위의 최대 깊이 기본값. 1 이상
 * @param loadedAt 적재한 때. 적재할 때 밖에서 받은 시각이다
 * @param entityTypes 개체 유형. 이름의 글자 코드 순
 * @param relationTypes 관계 유형. 이름의 글자 코드 순
 * @param attributes 속성. 이름의 글자 코드 순
 * @param mappings 매핑 정의. 입력 자료의 식별자 순
 * @param rules 규칙. 규칙 번호의 글자 코드 순
 * @param localizedLabels 언어별 이름표. 이름, 언어 순. 화면과 API 가 보여 주는 기본 이름표는 여기에 없고 각 정의의 label 에 있다
 */
public record PackDefinition(String packId, String label, int defaultMaxDepth, Instant loadedAt,
        List<EntityTypeDefinition> entityTypes, List<RelationTypeDefinition> relationTypes,
        List<AttributeDefinition> attributes, List<LocalizedLabel> localizedLabels,
        List<MappingDefinition> mappings, List<RuleDefinition> rules) {

    public PackDefinition {
        rules = List.copyOf(rules);
        entityTypes = List.copyOf(entityTypes);
        relationTypes = List.copyOf(relationTypes);
        attributes = List.copyOf(attributes);
        localizedLabels = List.copyOf(localizedLabels);
        mappings = List.copyOf(mappings);
    }

    /** 규칙이 없는 Pack 의 정의. */
    public PackDefinition(String packId, String label, int defaultMaxDepth, Instant loadedAt,
            List<EntityTypeDefinition> entityTypes, List<RelationTypeDefinition> relationTypes,
            List<AttributeDefinition> attributes, List<LocalizedLabel> localizedLabels,
            List<MappingDefinition> mappings) {
        this(packId, label, defaultMaxDepth, loadedAt, entityTypes, relationTypes, attributes, localizedLabels, mappings,
                List.of());
    }

    /** 적재한 때를 빼고 내용이 같은가. */
    boolean sameContentAs(PackDefinition other) {
        return packId.equals(other.packId) && label.equals(other.label) && defaultMaxDepth == other.defaultMaxDepth
                && entityTypes.equals(other.entityTypes) && relationTypes.equals(other.relationTypes)
                && attributes.equals(other.attributes) && localizedLabels.equals(other.localizedLabels)
                && mappings.equals(other.mappings) && rules.equals(other.rules);
    }
}
