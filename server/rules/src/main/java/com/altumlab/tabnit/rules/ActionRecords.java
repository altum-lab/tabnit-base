package com.altumlab.tabnit.rules;

import com.altumlab.tabnit.pack.RuleSubject;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 저장된 영향 범위와 조치를 읽는다 (D5 6절). 읽기만 한다.
 *
 * <p>영향 범위와 조치는 트랜잭션 하나로 함께 저장되고 뒤에 고치지 않으므로 (D2 AD-209, AD-119), 영향 범위의 행이
 * 보이면 그 대상과 경로도 모두 보인다. 그래서 여러 문장을 한 시점으로 묶지 않는다.
 * 조치의 상태는 B-07 부터 바뀔 수 있다. 조치는 한 문장으로 읽는다.
 */
public class ActionRecords {

    private final JdbcTemplate jdbc;

    public ActionRecords(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
    }

    /**
     * 변동 이벤트 하나의 조치.
     *
     * @return 우선순위, 규칙 번호, 대상 개체의 식별자 순 (D2 AD-222). 저장된 것이 없으면 빈 목록
     */
    public List<Action> actions(String changeEventId) {
        Objects.requireNonNull(changeEventId, "변동 이벤트의 식별자는 반드시 있어야 합니다");
        ActionGenerator.requireStorable(changeEventId, "변동 이벤트의 식별자");
        try {
            return jdbc.query("""
                    select a.id, a.pack_id, s.change_event_id, a.rule_no, a.rule_label, a.action_text, a.priority,
                           a.applies_to, a.target_entity_id, a.target_entity_type, a.basis_entity_id,
                           a.basis_input_source_id, a.basis_edition_no, a.as_of, a.due_date, a.status, a.created_at
                      from action a
                      join impact_scope s on s.change_event_hash = a.change_event_hash
                     where a.change_event_hash = ?
                     order by a.priority, a.rule_no, a.target_entity_id
                    """,
                    (rs, row) -> new Action(rs.getString("id"), rs.getString("pack_id"), rs.getString("change_event_id"),
                            rs.getString("rule_no"), rs.getString("rule_label"), rs.getString("action_text"),
                            rs.getInt("priority"), RuleSubject.valueOf(rs.getString("applies_to")),
                            rs.getString("target_entity_id"), rs.getString("target_entity_type"),
                            rs.getString("basis_entity_id"), rs.getString("basis_input_source_id"),
                            rs.getInt("basis_edition_no"), rs.getObject("as_of", LocalDate.class),
                            rs.getObject("due_date", LocalDate.class), ActionStatus.valueOf(rs.getString("status")),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                    RulesHash.sha256Hex(changeEventId));
        }
        catch (DataAccessException e) {
            throw new ActionStorageException("변동 이벤트 " + changeEventId + " 의 조치를 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /**
     * 이미 저장된 변동인가. 저장되어 있으면 그 Pack 의 식별자.
     */
    Optional<String> storedPackId(String changeEventId) {
        return jdbc.query("select pack_id from impact_scope where change_event_hash = ?",
                (rs, row) -> rs.getString("pack_id"), RulesHash.sha256Hex(changeEventId)).stream().findFirst();
    }

    /**
     * 변동 이벤트 하나의 저장된 영향 범위.
     *
     * @return 저장된 것이 없으면 (아직 처리되지 않았거나 실패한 변동) 비어 있다
     */
    public Optional<StoredImpactScope> impactScope(String changeEventId) {
        Objects.requireNonNull(changeEventId, "변동 이벤트의 식별자는 반드시 있어야 합니다");
        ActionGenerator.requireStorable(changeEventId, "변동 이벤트의 식별자");
        String hash = RulesHash.sha256Hex(changeEventId);
        try {
            List<StoredImpactScope> heads = jdbc.query("""
                    select change_event_id, pack_id, as_of_previous, as_of_effective, max_depth, truncated, stored_at
                      from impact_scope
                     where change_event_hash = ?
                    """,
                    (rs, row) -> new StoredImpactScope(rs.getString("change_event_id"), rs.getString("pack_id"),
                            rs.getObject("as_of_previous", LocalDate.class), rs.getObject("as_of_effective", LocalDate.class),
                            rs.getInt("max_depth"), rs.getBoolean("truncated"),
                            rs.getObject("stored_at", OffsetDateTime.class).toInstant(), List.of()),
                    hash);
            if (heads.isEmpty()) {
                return Optional.empty();
            }
            // 단계를 대상의 해시로 묶는다. 찾는 데만 쓰고, 차례는 아래 대상의 질의가 정한다.
            TreeMap<String, List<StoredPathStep>> steps = new TreeMap<>(RulesOrder.STRINGS);
            jdbc.query("""
                    select entity_hash, step_no, relation_id, relation_type, source_entity_id, target_entity_id
                      from impact_path_step
                     where change_event_hash = ?
                     order by entity_hash, step_no
                    """, rs -> {
                steps.computeIfAbsent(rs.getString("entity_hash"), key -> new ArrayList<>())
                    .add(new StoredPathStep(rs.getInt("step_no"), rs.getString("relation_id"), rs.getString("relation_type"),
                            rs.getString("source_entity_id"), rs.getString("target_entity_id")));
            }, hash);
            List<StoredImpactTarget> targets = jdbc.query("""
                    select entity_hash, entity_id, entity_type, depth, path_start_entity_id, path_as_of
                      from impact_target
                     where change_event_hash = ?
                     order by depth, entity_type, entity_id
                    """,
                    (rs, row) -> new StoredImpactTarget(rs.getString("entity_id"), rs.getString("entity_type"),
                            rs.getInt("depth"), rs.getString("path_start_entity_id"),
                            rs.getObject("path_as_of", LocalDate.class),
                            steps.getOrDefault(rs.getString("entity_hash"), List.of())),
                    hash);
            StoredImpactScope head = heads.get(0);
            return Optional.of(new StoredImpactScope(head.changeEventId(), head.packId(), head.asOfPrevious(),
                    head.asOfEffective(), head.maxDepth(), head.truncated(), head.storedAt(), targets));
        }
        catch (DataAccessException e) {
            throw new ActionStorageException("변동 이벤트 " + changeEventId + " 의 영향 범위를 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }
}
