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
        List<PackDefinition> found = jdbc.query(
                "select pack_id, label, default_max_depth, loaded_at from pack_definition where pack_id = ?",
                (rs, n) -> new PackDefinition(rs.getString("pack_id"), rs.getString("label"),
                        rs.getInt("default_max_depth"), rs.getTimestamp("loaded_at").toInstant(), entityTypes,
                        relationTypes, attributes, localizedLabels, mappings),
                packId);
        return found.stream().findFirst();
    }

    /** 매핑 한 행. 안쪽(열, 개체, 관계)은 이 목록을 다 읽은 뒤에 채운다. 결과를 읽는 도중에 다른 질의를 보내지 않는다. */
    private record MappingRow(String source, MappingFileFormat format, String sheet, MappedEntityRef primary) {
    }

    private List<MappingDefinition> readMappings(String packId) {
        List<MappingRow> rows = jdbc.query("""
                select input_source_id, file_format, sheet_name, primary_entity_type, primary_key_column
                from pack_mapping where pack_id = ? order by input_source_id collate "C\"""",
                (rs, n) -> new MappingRow(rs.getString("input_source_id"),
                        MappingFileFormat.valueOf(rs.getString("file_format")), rs.getString("sheet_name"),
                        new MappedEntityRef(rs.getString("primary_entity_type"), rs.getString("primary_key_column"))),
                packId);
        List<MappingDefinition> mappings = new ArrayList<>();
        for (MappingRow row : rows) {
            mappings.add(new MappingDefinition(row.source(), row.format(), row.sheet(),
                    readColumns(packId, row.source(), "REQUIRED"), readColumns(packId, row.source(), "ITEM_KEY"),
                    row.primary(), readMappedEntities(packId, row.source()),
                    readMappedRelations(packId, row.source())));
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
                insert into pack_mapping (pack_id, input_source_id, file_format, sheet_name,
                                          primary_entity_type, primary_key_column) values (?, ?, ?, ?, ?, ?)""",
                packId, source, mapping.fileFormat().name(), mapping.sheetName(),
                mapping.primaryEntity().entityType(), mapping.primaryEntity().keyColumn());
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
    }

    void delete(String packId) {
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
