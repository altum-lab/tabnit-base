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
        List<PackDefinition> found = jdbc.query(
                "select pack_id, label, default_max_depth, loaded_at from pack_definition where pack_id = ?",
                (rs, n) -> new PackDefinition(rs.getString("pack_id"), rs.getString("label"),
                        rs.getInt("default_max_depth"), rs.getTimestamp("loaded_at").toInstant(), entityTypes,
                        relationTypes, attributes, localizedLabels),
                packId);
        return found.stream().findFirst();
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
    }

    void delete(String packId) {
        jdbc.update("delete from pack_localized_label where pack_id = ?", packId);
        jdbc.update("delete from pack_relation_type where pack_id = ?", packId);
        jdbc.update("delete from pack_entity_type_parent where pack_id = ?", packId);
        // 개체 유형이 속성을 가리키는 참조는 트랜잭션 끝에서 확인한다.
        jdbc.update("delete from pack_attribute where pack_id = ?", packId);
        jdbc.update("delete from pack_entity_type where pack_id = ?", packId);
        jdbc.update("delete from pack_definition where pack_id = ?", packId);
    }
}
