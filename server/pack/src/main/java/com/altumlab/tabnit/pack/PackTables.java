package com.altumlab.tabnit.pack;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** pack 의 테이블을 읽고 쓴다 (D5 4절). 읽는 차례는 이름의 글자 코드 순이다. */
final class PackTables {

    private final JdbcTemplate jdbc;

    PackTables(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> packIds() {
        return jdbc.queryForList("select pack_id from pack_definition order by pack_id collate \"C\"", String.class);
    }

    Optional<PackDefinition> read(String packId) {
        Map<String, List<String>> parents = new TreeMap<>(PackOrder.STRINGS);
        jdbc.query("""
                select name, parent_name from pack_entity_type_parent
                where pack_id = ? order by name collate "C", parent_name collate "C\"""", rs -> {
            parents.computeIfAbsent(rs.getString("name"), k -> new ArrayList<>()).add(rs.getString("parent_name"));
        }, packId);
        List<EntityTypeDefinition> entityTypes = jdbc.query("""
                select name, label, display_name_attribute from pack_entity_type
                where pack_id = ? order by name collate "C\"""",
                (rs, n) -> new EntityTypeDefinition(rs.getString("name"), rs.getString("label"),
                        parents.getOrDefault(rs.getString("name"), List.of()), rs.getString("display_name_attribute")),
                packId);
        List<RelationTypeDefinition> relationTypes = jdbc.query("""
                select name, label, source_type, target_type, impact_direction from pack_relation_type
                where pack_id = ? order by name collate "C\"""",
                (rs, n) -> new RelationTypeDefinition(rs.getString("name"), rs.getString("label"),
                        rs.getString("source_type"), rs.getString("target_type"),
                        PackImpactDirection.valueOf(rs.getString("impact_direction"))),
                packId);
        List<AttributeDefinition> attributes = jdbc.query("""
                select name, label, entity_type, value_format from pack_attribute
                where pack_id = ? order by name collate "C\"""",
                (rs, n) -> new AttributeDefinition(rs.getString("name"), rs.getString("label"),
                        rs.getString("entity_type"), ValueFormat.valueOf(rs.getString("value_format"))),
                packId);
        List<LocalizedLabel> localizedLabels = jdbc.query("""
                select name, language, label from pack_localized_label
                where pack_id = ? order by name collate "C", language collate "C\"""",
                (rs, n) -> new LocalizedLabel(rs.getString("name"), rs.getString("language"), rs.getString("label")),
                packId);
        List<MappingDefinition> mappings = readMappings(packId);
        List<RuleDefinition> rules = readRules(packId);
        List<PackDefinition> found = jdbc.query(
                "select pack_id, label, default_max_depth, loaded_at from pack_definition where pack_id = ?",
                (rs, n) -> new PackDefinition(rs.getString("pack_id"), rs.getString("label"),
                        rs.getInt("default_max_depth"), rs.getTimestamp("loaded_at").toInstant(), entityTypes,
                        relationTypes, attributes, localizedLabels, mappings, rules),
                packId);
        return found.stream().findFirst();
    }

    /** 매핑 한 행. 안쪽(열, 개체, 관계)은 이 목록을 다 읽은 뒤에 채운다. 결과를 읽는 도중에 다른 질의를 보내지 않는다. */
    private record MappingRow(String source, MappingFileFormat format, String sheet, String defaultDatePattern,
            MappedEntityRef primary, String label) {
    }

    private List<MappingDefinition> readMappings(String packId) {
        List<MappingRow> rows = jdbc.query("""
                select input_source_id, file_format, sheet_name, default_date_pattern,
                       primary_entity_type, primary_key_column, label
                from pack_mapping where pack_id = ? order by input_source_id collate "C\"""",
                (rs, n) -> new MappingRow(rs.getString("input_source_id"),
                        MappingFileFormat.valueOf(rs.getString("file_format")), rs.getString("sheet_name"),
                        rs.getString("default_date_pattern"), new MappedEntityRef(rs.getString("primary_entity_type"), rs.getString("primary_key_column")),
                        rs.getString("label")),
                packId);
        List<MappingDefinition> mappings = new ArrayList<>();
        for (MappingRow row : rows) {
            mappings.add(new MappingDefinition(row.source(), row.format(), row.sheet(), row.defaultDatePattern(),
                    readColumns(packId, row.source(), "REQUIRED"), readColumns(packId, row.source(), "ITEM_KEY"),
                    row.primary(), readMappedEntities(packId, row.source()),
                    readMappedRelations(packId, row.source()), row.label()));
        }
        return mappings;
    }

    private List<String> readColumns(String packId, String source, String kind) {
        return jdbc.queryForList("""
                select column_name from pack_mapping_column
                where pack_id = ? and input_source_id = ? and kind = ? order by position""", String.class, packId,
                source, kind);
    }

    private List<MappedEntity> readMappedEntities(String packId, String source) {
        List<MappedEntityRef> refs = jdbc.query("""
                select entity_type, key_column from pack_mapping_entity
                where pack_id = ? and input_source_id = ?
                order by entity_type collate "C", key_column collate "C\"""",
                (rs, n) -> new MappedEntityRef(rs.getString("entity_type"), rs.getString("key_column")), packId,
                source);
        List<MappedEntity> entities = new ArrayList<>();
        for (MappedEntityRef ref : refs) {
            List<MappedAttribute> attributes = jdbc.query("""
                    select attribute_name, column_name, valid_from_column, valid_to_column,
                           date_pattern, grouping_separator, true_value, false_value
                    from pack_mapping_attribute
                    where pack_id = ? and input_source_id = ? and entity_type = ? and key_column = ?
                    order by attribute_name collate "C\"""",
                    (a, m) -> new MappedAttribute(a.getString("attribute_name"), a.getString("column_name"),
                            a.getString("valid_from_column"), a.getString("valid_to_column"),
                            a.getString("date_pattern"), a.getString("grouping_separator"),
                            a.getString("true_value"), a.getString("false_value")),
                    packId, source, ref.entityType(), ref.keyColumn());
            entities.add(new MappedEntity(ref.entityType(), ref.keyColumn(), attributes));
        }
        return entities;
    }

    private List<MappedRelation> readMappedRelations(String packId, String source) {
        return jdbc.query("""
                select relation_type, source_entity_type, source_key_column, target_entity_type, target_key_column,
                       valid_from_column, valid_to_column
                from pack_mapping_relation where pack_id = ? and input_source_id = ?
                order by relation_type collate "C", source_entity_type collate "C", source_key_column collate "C",
                         target_entity_type collate "C", target_key_column collate "C\"""",
                (rs, n) -> new MappedRelation(rs.getString("relation_type"),
                        new MappedEntityRef(rs.getString("source_entity_type"), rs.getString("source_key_column")),
                        new MappedEntityRef(rs.getString("target_entity_type"), rs.getString("target_key_column")),
                        rs.getString("valid_from_column"), rs.getString("valid_to_column")),
                packId, source);
    }

    private void insertMapping(String packId, MappingDefinition mapping) {
        String source = mapping.inputSourceId();
        jdbc.update("""
                insert into pack_mapping (pack_id, input_source_id, file_format, sheet_name, default_date_pattern,
                                          primary_entity_type, primary_key_column, label) values (?, ?, ?, ?, ?, ?, ?, ?)""",
                packId, source, mapping.fileFormat().name(), mapping.sheetName(), mapping.defaultDatePattern(),
                mapping.primaryEntity().entityType(), mapping.primaryEntity().keyColumn(), mapping.label());
        insertColumns(packId, source, "REQUIRED", mapping.requiredColumns());
        insertColumns(packId, source, "ITEM_KEY", mapping.itemKeyColumns());
        for (MappedEntity entity : mapping.entities()) {
            jdbc.update("insert into pack_mapping_entity (pack_id, input_source_id, entity_type, key_column) values (?, ?, ?, ?)",
                    packId, source, entity.entityType(), entity.keyColumn());
            for (MappedAttribute attribute : entity.attributes()) {
                jdbc.update("""
                        insert into pack_mapping_attribute (pack_id, input_source_id, entity_type, key_column,
                            attribute_name, column_name, valid_from_column, valid_to_column,
                            date_pattern, grouping_separator, true_value, false_value)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", packId, source, entity.entityType(),
                        entity.keyColumn(), attribute.attributeName(), attribute.column(), attribute.validFromColumn(),
                        attribute.validToColumn(), attribute.datePattern(), attribute.groupingSeparator(),
                        attribute.trueValue(), attribute.falseValue());
            }
        }
        for (MappedRelation relation : mapping.relations()) {
            jdbc.update("""
                    insert into pack_mapping_relation (pack_id, input_source_id, relation_type,
                        source_entity_type, source_key_column, target_entity_type, target_key_column,
                        valid_from_column, valid_to_column) values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", packId, source,
                    relation.relationType(), relation.source().entityType(), relation.source().keyColumn(),
                    relation.target().entityType(), relation.target().keyColumn(), relation.validFromColumn(),
                    relation.validToColumn());
        }
    }

    private void insertColumns(String packId, String source, String kind, List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            jdbc.update("insert into pack_mapping_column (pack_id, input_source_id, kind, position, column_name) values (?, ?, ?, ?, ?)",
                    packId, source, kind, i + 1, columns.get(i));
        }
    }

    void insert(PackDefinition pack) {
        jdbc.update("insert into pack_definition (pack_id, label, default_max_depth, loaded_at) values (?, ?, ?, ?)",
                pack.packId(), pack.label(), pack.defaultMaxDepth(), Timestamp.from(pack.loadedAt()));
        for (EntityTypeDefinition type : pack.entityTypes()) {
            jdbc.update("insert into pack_entity_type (pack_id, name, label, display_name_attribute) values (?, ?, ?, ?)",
                    pack.packId(), type.name(), type.label(), type.displayNameAttribute());
        }
        for (EntityTypeDefinition type : pack.entityTypes()) {
            for (String parent : type.parents()) {
                jdbc.update("insert into pack_entity_type_parent (pack_id, name, parent_name) values (?, ?, ?)",
                        pack.packId(), type.name(), parent);
            }
        }
        for (AttributeDefinition attribute : pack.attributes()) {
            jdbc.update(
                    "insert into pack_attribute (pack_id, name, label, entity_type, value_format) values (?, ?, ?, ?, ?)",
                    pack.packId(), attribute.name(), attribute.label(), attribute.entityType(),
                    attribute.valueFormat().name());
        }
        for (LocalizedLabel label : pack.localizedLabels()) {
            jdbc.update("insert into pack_localized_label (pack_id, name, language, label) values (?, ?, ?, ?)",
                    pack.packId(), label.name(), label.language(), label.label());
        }
        for (RelationTypeDefinition relation : pack.relationTypes()) {
            jdbc.update("""
                    insert into pack_relation_type (pack_id, name, label, source_type, target_type, impact_direction)
                    values (?, ?, ?, ?, ?, ?)""", pack.packId(), relation.name(), relation.label(),
                    relation.sourceType(), relation.targetType(), relation.impactDirection().name());
        }
        // 매핑은 개체 유형, 속성, 관계 유형을 가리키므로 맨 뒤에 넣는다.
        for (MappingDefinition mapping : pack.mappings()) {
            insertMapping(pack.packId(), mapping);
        }
        // 규칙도 개체 유형과 속성을 가리킨다.
        for (RuleDefinition rule : pack.rules()) {
            insertRule(pack.packId(), rule);
        }
    }

    /** 규칙 한 행. 조건은 이 목록을 다 읽은 뒤에 채운다. 결과를 읽는 도중에 다른 질의를 보내지 않는다. */
    private record RuleRow(String ruleNo, String label, int priority, boolean onAdded, boolean onChanged,
            boolean onRemoved, RuleSubject appliesTo, String targetType, String actionText, int dueInDays) {
    }

    private record ConditionRow(String ruleNo, int position, RuleSubject subject, String typeName,
            String attributeName, RuleOperator operator, RuleCompareTo compareTo, RuleSubject compareSubject,
            String compareAttributeName, String linkedBy) {
    }

    private List<RuleDefinition> readRules(String packId) {
        List<RuleRow> rows = jdbc.query("""
                select rule_no, label, priority, on_added, on_changed, on_removed, applies_to, target_type,
                       action_text, due_in_days
                from pack_rule where pack_id = ? order by rule_no""",
                (rs, n) -> new RuleRow(rs.getString("rule_no"), rs.getString("label"), rs.getInt("priority"),
                        rs.getBoolean("on_added"), rs.getBoolean("on_changed"), rs.getBoolean("on_removed"),
                        RuleSubject.valueOf(rs.getString("applies_to")), rs.getString("target_type"),
                        rs.getString("action_text"), rs.getInt("due_in_days")),
                packId);
        List<ConditionRow> conditionRows = jdbc.query("""
                select rule_no, position, subject, type_name, attribute_name, operator, compare_to, compare_subject,
                       compare_attribute_name, linked_relation_type
                from pack_rule_condition where pack_id = ? order by rule_no, position""",
                (rs, n) -> new ConditionRow(rs.getString("rule_no"), rs.getInt("position"),
                        RuleSubject.valueOf(rs.getString("subject")), rs.getString("type_name"),
                        rs.getString("attribute_name"),
                        rs.getString("operator") == null ? null : RuleOperator.valueOf(rs.getString("operator")),
                        RuleCompareTo.valueOf(rs.getString("compare_to")),
                        rs.getString("compare_subject") == null ? null
                                : RuleSubject.valueOf(rs.getString("compare_subject")),
                        rs.getString("compare_attribute_name"), rs.getString("linked_relation_type")),
                packId);
        Map<String, List<String>> sources = names("pack_rule_input_source", "input_source_id", packId);
        Map<String, List<String>> changedAttributes = names("pack_rule_changed_attribute", "attribute_name", packId);
        Map<String, List<String>> changedRelationTypes = names("pack_rule_changed_relation_type", "relation_type",
                packId);
        // (규칙 번호, 조건의 번호)마다의 값. 값의 번호 순.
        Map<String, Map<Integer, List<String>>> values = new TreeMap<>(PackOrder.STRINGS);
        jdbc.query("""
                select rule_no, position, value
                from pack_rule_condition_value where pack_id = ? order by rule_no, position, value_position""", rs -> {
            values.computeIfAbsent(rs.getString("rule_no"), k -> new TreeMap<>())
                .computeIfAbsent(rs.getInt("position"), k -> new ArrayList<>())
                .add(rs.getString("value"));
        }, packId);
        Map<String, List<RuleCondition>> conditions = new TreeMap<>(PackOrder.STRINGS);
        for (ConditionRow row : conditionRows) {
            conditions.computeIfAbsent(row.ruleNo(), k -> new ArrayList<>())
                .add(new RuleCondition(row.subject(), row.typeName(), row.attributeName(), row.operator(),
                        values.getOrDefault(row.ruleNo(), Map.of()).getOrDefault(row.position(), List.of()),
                        row.compareTo(), row.compareSubject(), row.compareAttributeName(), row.linkedBy()));
        }
        List<RuleDefinition> rules = new ArrayList<>();
        for (RuleRow row : rows) {
            List<RuleChangeKind> kinds = new ArrayList<>();
            if (row.onAdded()) {
                kinds.add(RuleChangeKind.ADDED);
            }
            if (row.onChanged()) {
                kinds.add(RuleChangeKind.CHANGED);
            }
            if (row.onRemoved()) {
                kinds.add(RuleChangeKind.REMOVED);
            }
            rules.add(new RuleDefinition(row.ruleNo(), row.label(), row.priority(), kinds, row.appliesTo(),
                    row.targetType(), conditions.getOrDefault(row.ruleNo(), List.of()), row.actionText(),
                    row.dueInDays(), sources.getOrDefault(row.ruleNo(), List.of()),
                    changedAttributes.getOrDefault(row.ruleNo(), List.of()),
                    changedRelationTypes.getOrDefault(row.ruleNo(), List.of())));
        }
        return rules;
    }

    /** 규칙 번호마다의 이름 목록. 이름의 글자 코드 순. 테이블과 열의 이름은 이 클래스가 적은 고정 글자다. */
    private Map<String, List<String>> names(String table, String column, String packId) {
        Map<String, List<String>> byRule = new TreeMap<>(PackOrder.STRINGS);
        jdbc.query("select rule_no, " + column + " from " + table + " where pack_id = ? order by rule_no, " + column,
                rs -> {
                    byRule.computeIfAbsent(rs.getString("rule_no"), k -> new ArrayList<>()).add(rs.getString(2));
                }, packId);
        return byRule;
    }

    private void insertRule(String packId, RuleDefinition rule) {
        jdbc.update("""
                insert into pack_rule (pack_id, rule_no, label, priority, on_added, on_changed, on_removed, applies_to,
                                       target_type, action_text, due_in_days)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", packId, rule.ruleNo(), rule.label(), rule.priority(),
                rule.changeKinds().contains(RuleChangeKind.ADDED), rule.changeKinds().contains(RuleChangeKind.CHANGED),
                rule.changeKinds().contains(RuleChangeKind.REMOVED), rule.appliesTo().name(), rule.targetType(),
                rule.actionText(), rule.dueInDays());
        for (int i = 0; i < rule.conditions().size(); i++) {
            RuleCondition condition = rule.conditions().get(i);
            jdbc.update("""
                    insert into pack_rule_condition (pack_id, rule_no, position, subject, type_name, attribute_name,
                                                     operator, compare_to, compare_subject, compare_attribute_name,
                                                     linked_relation_type)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", packId, rule.ruleNo(),
                    i + 1, condition.subject().name(), condition.typeIs(), condition.attributeName(),
                    condition.operator() == null ? null : condition.operator().name(), condition.compareTo().name(),
                    condition.compareSubject() == null ? null : condition.compareSubject().name(),
                    condition.compareAttributeName(), condition.linkedBy());
            for (int j = 0; j < condition.values().size(); j++) {
                jdbc.update("""
                        insert into pack_rule_condition_value (pack_id, rule_no, position, value_position, value)
                        values (?, ?, ?, ?, ?)""", packId, rule.ruleNo(), i + 1, j + 1, condition.values().get(j));
            }
        }
        for (String source : rule.inputSources()) {
            jdbc.update("insert into pack_rule_input_source (pack_id, rule_no, input_source_id) values (?, ?, ?)",
                    packId, rule.ruleNo(), source);
        }
        for (String name : rule.changedAttributes()) {
            jdbc.update("insert into pack_rule_changed_attribute (pack_id, rule_no, attribute_name) values (?, ?, ?)",
                    packId, rule.ruleNo(), name);
        }
        for (String name : rule.changedRelationTypes()) {
            jdbc.update(
                    "insert into pack_rule_changed_relation_type (pack_id, rule_no, relation_type) values (?, ?, ?)",
                    packId, rule.ruleNo(), name);
        }
    }

    void delete(String packId) {
        // 규칙도 온톨로지의 정의를 가리키므로 먼저 지운다.
        jdbc.update("delete from pack_rule_input_source where pack_id = ?", packId);
        jdbc.update("delete from pack_rule_changed_attribute where pack_id = ?", packId);
        jdbc.update("delete from pack_rule_changed_relation_type where pack_id = ?", packId);
        jdbc.update("delete from pack_rule_condition_value where pack_id = ?", packId);
        jdbc.update("delete from pack_rule_condition where pack_id = ?", packId);
        jdbc.update("delete from pack_rule where pack_id = ?", packId);
        // 매핑은 온톨로지의 정의를 가리키므로 먼저 지운다.
        jdbc.update("delete from pack_mapping_attribute where pack_id = ?", packId);
        jdbc.update("delete from pack_mapping_relation where pack_id = ?", packId);
        jdbc.update("delete from pack_mapping_entity where pack_id = ?", packId);
        jdbc.update("delete from pack_mapping_column where pack_id = ?", packId);
        jdbc.update("delete from pack_mapping where pack_id = ?", packId);
        jdbc.update("delete from pack_localized_label where pack_id = ?", packId);
        jdbc.update("delete from pack_relation_type where pack_id = ?", packId);
        jdbc.update("delete from pack_entity_type_parent where pack_id = ?", packId);
        // 개체 유형이 속성을 가리키는 참조는 트랜잭션 끝에서 확인한다.
        jdbc.update("delete from pack_attribute where pack_id = ?", packId);
        jdbc.update("delete from pack_entity_type where pack_id = ?", packId);
        jdbc.update("delete from pack_definition where pack_id = ?", packId);
    }
}
