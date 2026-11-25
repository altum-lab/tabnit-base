package com.altumlab.tabnit.pack;

import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.Optional;
import java.util.Objects;
import java.util.Map;

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
 * @param constraints 제약 파일(constraints.ttl)의 글 그대로 (SHACL, D6 6절). 제약이 없으면 빈 글자다. 적재할 때 검증한 것이다
 * @param localizedLabels 언어별 이름표. 이름, 언어 순. 화면과 API 가 보여 주는 기본 이름표는 여기에 없고 각 정의의 label 에 있다
 */
public record PackDefinition(String packId, String label, int defaultMaxDepth, Instant loadedAt,
        List<EntityTypeDefinition> entityTypes, List<RelationTypeDefinition> relationTypes,
        List<AttributeDefinition> attributes, List<LocalizedLabel> localizedLabels,
        List<MappingDefinition> mappings, List<RuleDefinition> rules, String constraints) {

    public PackDefinition {
        constraints = Objects.requireNonNull(constraints, "제약의 글은 반드시 있어야 합니다. 제약이 없으면 빈 글자입니다");
        rules = List.copyOf(rules);
        entityTypes = List.copyOf(entityTypes);
        relationTypes = List.copyOf(relationTypes);
        attributes = List.copyOf(attributes);
        localizedLabels = List.copyOf(localizedLabels);
        mappings = List.copyOf(mappings);
    }

    /** 제약이 없는 Pack 의 정의. */
    public PackDefinition(String packId, String label, int defaultMaxDepth, Instant loadedAt,
            List<EntityTypeDefinition> entityTypes, List<RelationTypeDefinition> relationTypes,
            List<AttributeDefinition> attributes, List<LocalizedLabel> localizedLabels,
            List<MappingDefinition> mappings, List<RuleDefinition> rules) {
        this(packId, label, defaultMaxDepth, loadedAt, entityTypes, relationTypes, attributes, localizedLabels, mappings,
                rules, "");
    }

    /** 규칙이 없는 Pack 의 정의. */
    public PackDefinition(String packId, String label, int defaultMaxDepth, Instant loadedAt,
            List<EntityTypeDefinition> entityTypes, List<RelationTypeDefinition> relationTypes,
            List<AttributeDefinition> attributes, List<LocalizedLabel> localizedLabels,
            List<MappingDefinition> mappings) {
        this(packId, label, defaultMaxDepth, loadedAt, entityTypes, relationTypes, attributes, localizedLabels, mappings,
                List.of());
    }

    /**
     * 개체 유형의 표시 이름으로 쓸 속성의 이름 (D6 5절, D2 AD-70). 그 유형이 스스로 적었으면 그것이고, 아니면 상위
     * 유형에서 받는다 (여러 단계 위도). 상위 유형들이 서로 다른 값을 내려주는 Pack 은 읽을 때 거부되므로 (D2 AD-88),
     * 어느 길로 올라가도 값이 같다. 이름의 글자 코드 순으로 올라가 처음 만나는 것을 돌려준다.
     *
     * @return 어디에도 적지 않았거나 그런 유형이 없으면 비어 있다
     */
    public Optional<String> displayNameAttributeOf(String entityType) {
        Objects.requireNonNull(entityType, "개체 유형의 이름은 반드시 있어야 합니다");
        Map<String, EntityTypeDefinition> byName = new TreeMap<>(PackOrder.STRINGS);
        entityTypes.forEach(type -> byName.put(type.name(), type));
        // 너비 우선으로 올라간다. 이미 본 유형은 다시 보지 않는다 (상위 유형의 사슬은 돌아오지 않지만 여러 길로 닿을 수 있다).
        TreeSet<String> seen = new TreeSet<>(PackOrder.STRINGS);
        List<String> level = List.of(entityType);
        while (!level.isEmpty()) {
            TreeSet<String> next = new TreeSet<>(PackOrder.STRINGS);
            for (String name : level) {
                EntityTypeDefinition type = byName.get(name);
                if (type == null || !seen.add(name)) {
                    continue;
                }
                if (type.displayNameAttribute() != null) {
                    return Optional.of(type.displayNameAttribute());
                }
                next.addAll(type.parents());
            }
            level = List.copyOf(next);
        }
        return Optional.empty();
    }

    /** 개체 유형의 이름표 (화면에 보일 이름). 그런 유형이 없으면 비어 있다. */
    public Optional<String> entityTypeLabelOf(String entityType) {
        Objects.requireNonNull(entityType, "개체 유형의 이름은 반드시 있어야 합니다");
        // 적재된 정의에는 이름표가 반드시 있다 (D6 5절). 손으로 만든 정의에 이름표가 없어도 없다고 답한다.
        return entityTypes.stream().filter(type -> type.name().equals(entityType)).findFirst().map(EntityTypeDefinition::label);
    }

    /** 적재한 때를 빼고 내용이 같은가. */
    boolean sameContentAs(PackDefinition other) {
        return packId.equals(other.packId) && label.equals(other.label) && defaultMaxDepth == other.defaultMaxDepth
                && entityTypes.equals(other.entityTypes) && relationTypes.equals(other.relationTypes)
                && attributes.equals(other.attributes) && localizedLabels.equals(other.localizedLabels)
                && mappings.equals(other.mappings) && rules.equals(other.rules) && constraints.equals(other.constraints);
    }
}
