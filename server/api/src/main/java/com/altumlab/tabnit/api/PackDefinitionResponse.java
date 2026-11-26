package com.altumlab.tabnit.api;

import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.MappedAttribute;
import com.altumlab.tabnit.pack.MappedEntity;
import com.altumlab.tabnit.pack.MappedEntityRef;
import com.altumlab.tabnit.pack.MappedRelation;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.RelationTypeDefinition;
import com.altumlab.tabnit.pack.RuleCondition;
import com.altumlab.tabnit.pack.RuleDefinition;
import java.util.List;

/**
 * 적재된 Pack 의 정의의 응답 (docs/api/openapi.yaml 의 PackDefinition, D7 8절 "Pack 의 정의", D2 AD-415).
 *
 * <p>pack 이 읽어 둔 정의를 응답의 모양으로 옮길 뿐이다. 목록의 순서는 pack 이 준 순서 그대로다: 여기서 바꾸지 않는다.
 * 없을 수 있는 항목은 null 로 적는다. 항목을 빼지 않는다.
 *
 * @param loadedAt 적재한 때. 협정 세계시다
 * @param constraints 제약 파일의 글 그대로 (SHACL). 제약이 없으면 빈 글자다
 */
record PackDefinitionResponse(String packId, String label, int defaultMaxDepth, String loadedAt, List<EntityType> entityTypes,
		List<Attribute> attributes, List<RelationType> relationTypes, List<InputSource> inputSources, List<Rule> rules,
		String constraints) {

	record EntityType(String name, String label, List<String> parents, String displayNameAttribute) {
	}

	record Attribute(String name, String label, String entityType, String valueFormat) {
	}

	record RelationType(String name, String label, String sourceType, String targetType, String impactDirection) {
	}

	record EntityRef(String entityType, String keyColumn) {

		static EntityRef of(MappedEntityRef ref) {
			return ref == null ? null : new EntityRef(ref.entityType(), ref.keyColumn());
		}
	}

	record MappedValue(String attributeName, String column, String validFromColumn, String validToColumn,
			String datePattern, String groupingSeparator, String trueValue, String falseValue) {
	}

	record MappedEntityItem(String entityType, String keyColumn, List<MappedValue> attributes) {
	}

	record MappedRelationItem(String relationType, EntityRef source, EntityRef target, String validFromColumn,
			String validToColumn) {
	}

	/** 입력 자료와 그 매핑. */
	record InputSource(String inputSourceId, String label, String fileFormat, String sheetName, String defaultDatePattern,
			List<String> requiredColumns, List<String> itemKeyColumns, EntityRef primaryEntity, List<MappedEntityItem> entities,
			List<MappedRelationItem> relations) {
	}

	record Condition(String subject, String typeIs, String attributeName, String operator, List<String> values,
			String compareTo, String compareSubject, String compareAttributeName, String linkedBy) {
	}

	record Rule(String ruleNo, String label, int priority, List<String> changeKinds, String appliesTo, String targetType,
			List<Condition> conditions, String actionText, int dueInDays, List<String> inputSources,
			List<String> changedAttributes, List<String> changedRelationTypes) {
	}

	static PackDefinitionResponse of(PackDefinition pack) {
		return new PackDefinitionResponse(pack.packId(), pack.label(), pack.defaultMaxDepth(), pack.loadedAt().toString(),
				pack.entityTypes().stream().map(PackDefinitionResponse::entityType).toList(),
				pack.attributes().stream().map(PackDefinitionResponse::attribute).toList(),
				pack.relationTypes().stream().map(PackDefinitionResponse::relationType).toList(),
				pack.mappings().stream().map(PackDefinitionResponse::inputSource).toList(),
				pack.rules().stream().map(PackDefinitionResponse::rule).toList(), pack.constraints());
	}

	private static EntityType entityType(EntityTypeDefinition type) {
		return new EntityType(type.name(), type.label(), type.parents(), type.displayNameAttribute());
	}

	private static Attribute attribute(AttributeDefinition attribute) {
		return new Attribute(attribute.name(), attribute.label(), attribute.entityType(), attribute.valueFormat().name());
	}

	private static RelationType relationType(RelationTypeDefinition type) {
		return new RelationType(type.name(), type.label(), type.sourceType(), type.targetType(), type.impactDirection().name());
	}

	private static InputSource inputSource(MappingDefinition mapping) {
		return new InputSource(mapping.inputSourceId(), mapping.shownLabel(), mapping.fileFormat().name(), mapping.sheetName(),
				mapping.defaultDatePattern(), mapping.requiredColumns(), mapping.itemKeyColumns(),
				EntityRef.of(mapping.primaryEntity()),
				mapping.entities().stream().map(PackDefinitionResponse::mappedEntity).toList(),
				mapping.relations().stream().map(PackDefinitionResponse::mappedRelation).toList());
	}

	private static MappedEntityItem mappedEntity(MappedEntity entity) {
		return new MappedEntityItem(entity.entityType(), entity.keyColumn(),
				entity.attributes().stream().map(PackDefinitionResponse::mappedValue).toList());
	}

	private static MappedValue mappedValue(MappedAttribute attribute) {
		return new MappedValue(attribute.attributeName(), attribute.column(), attribute.validFromColumn(),
				attribute.validToColumn(), attribute.datePattern(), attribute.groupingSeparator(), attribute.trueValue(),
				attribute.falseValue());
	}

	private static MappedRelationItem mappedRelation(MappedRelation relation) {
		return new MappedRelationItem(relation.relationType(), EntityRef.of(relation.source()), EntityRef.of(relation.target()),
				relation.validFromColumn(), relation.validToColumn());
	}

	private static Rule rule(RuleDefinition rule) {
		return new Rule(rule.ruleNo(), rule.label(), rule.priority(), rule.changeKinds().stream().map(Enum::name).toList(),
				rule.appliesTo().name(), rule.targetType(),
				rule.conditions().stream().map(PackDefinitionResponse::condition).toList(), rule.actionText(), rule.dueInDays(),
				rule.inputSources(), rule.changedAttributes(), rule.changedRelationTypes());
	}

	private static Condition condition(RuleCondition condition) {
		return new Condition(condition.subject().name(), condition.typeIs(), condition.attributeName(),
				condition.operator() == null ? null : condition.operator().name(), condition.values(),
				condition.compareTo().name(), condition.compareSubject() == null ? null : condition.compareSubject().name(),
				condition.compareAttributeName(), condition.linkedBy());
	}
}
