package com.altumlab.tabnit.rules;

import com.altumlab.tabnit.pack.RuleSubject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 저장된 영향 범위와 조치를 읽는다 (D5 6절). 읽기만 한다.
 *
 * <p>영향 범위와 조치는 트랜잭션 하나로 함께 저장되고 뒤에 고치지 않으므로 (D2 AD-209, AD-119), 영향 범위의 행이
 * 보이면 그 대상과 경로도 모두 보인다. 그래서 통째로 읽는 조회는 여러 문장을 한 시점으로 묶지 않는다.
 * 조치의 상태는 확정과 보류로 바뀐다 ({@link ActionDecisions}). 조치는 한 문장으로 읽는다.
 *
 * <p>나눠 읽는 조회 (D2 AD-347)는 전체 수와 그 쪽을 따로 읽으므로 한 시점의 것으로 묶는다 (읽기 전용,
 * REPEATABLE READ). 부르는 쪽이 이미 트랜잭션 안이면 그 트랜잭션에 들어 그 격리 수준을 따른다:
 * 한 시점을 보장받으려면 트랜잭션 밖에서 부른다.
 */
public class ActionRecords {

    private static final RowMapper<Action> ACTION = (rs, row) -> new Action(rs.getString("id"), rs.getString("pack_id"),
            rs.getString("change_event_id"), rs.getString("rule_no"), rs.getString("rule_label"),
            rs.getString("action_text"), rs.getInt("priority"), RuleSubject.valueOf(rs.getString("applies_to")),
            rs.getString("target_entity_id"), rs.getString("target_entity_type"), rs.getString("basis_entity_id"),
            rs.getString("basis_input_source_id"), rs.getInt("basis_edition_no"), rs.getObject("as_of", LocalDate.class),
            rs.getObject("due_date", LocalDate.class), ActionStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    /**
     * 조치와 이력을 함께 읽을 때의 한 시점. 부르는 쪽이 이미 트랜잭션 안이면 그 트랜잭션에 든다: 상태를 바꾼 직후에
     * 같은 트랜잭션에서 읽으면 방금 바꾼 것이 보인다.
     */
    private final TransactionTemplate snapshot;

    public ActionRecords(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
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
                    ACTION,
                    RulesHash.sha256Hex(changeEventId));
        }
        catch (DataAccessException e) {
            throw new ActionStorageException("변동 이벤트 " + changeEventId + " 의 조치를 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /**
     * 조치 하나를 식별자로 읽는다.
     *
     * @return 그런 조치가 없으면 비어 있다
     */
    public Optional<Action> find(String actionId) {
        Objects.requireNonNull(actionId, "조치의 식별자는 반드시 있어야 합니다");
        ActionGenerator.requireStorable(actionId, "조치의 식별자");
        try {
            return jdbc.query("""
                    select a.id, a.pack_id, s.change_event_id, a.rule_no, a.rule_label, a.action_text, a.priority,
                           a.applies_to, a.target_entity_id, a.target_entity_type, a.basis_entity_id,
                           a.basis_input_source_id, a.basis_edition_no, a.as_of, a.due_date, a.status, a.created_at
                      from action a
                      join impact_scope s on s.change_event_hash = a.change_event_hash
                     where a.id_hash = ? and a.id = ?
                    """, ACTION, RulesHash.sha256Hex(actionId), actionId).stream().findFirst();
        }
        catch (DataAccessException e) {
            throw new ActionStorageException("조치 " + actionId + " 을(를) 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /**
     * 조치 하나와 그 이력을 한 시점의 것으로 함께 읽는다. 따로 읽으면 그 사이에 다른 사람이 상태를 바꿔 둘이
     * 어긋날 수 있다.
     *
     * @return 그런 조치가 없으면 비어 있다
     */
    public Optional<ActionWithHistory> detail(String actionId) {
        Objects.requireNonNull(actionId, "조치의 식별자는 반드시 있어야 합니다");
        ActionGenerator.requireStorable(actionId, "조치의 식별자");
        try {
            return snapshot.execute(status -> find(actionId).map(action -> new ActionWithHistory(action, history(actionId))));
        }
        catch (TransactionException e) {
            throw new ActionStorageException("조치 " + actionId + " 을(를) 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /**
     * 조치 하나의 이력. 상태를 바꾼 차례다.
     *
     * @return 아직 아무도 바꾸지 않았거나 그런 조치가 없으면 빈 목록
     */
    public List<ActionDecision> history(String actionId) {
        Objects.requireNonNull(actionId, "조치의 식별자는 반드시 있어야 합니다");
        ActionGenerator.requireStorable(actionId, "조치의 식별자");
        try {
            return jdbc.query("""
                    select h.seq, h.from_status, h.to_status, h.decided_by, h.decided_at, h.note
                      from action_history h
                      join action a on a.id_hash = h.action_id_hash
                     where h.action_id_hash = ? and a.id = ?
                     order by h.seq
                    """,
                    (rs, row) -> new ActionDecision(rs.getInt("seq"), ActionStatus.valueOf(rs.getString("from_status")),
                            ActionStatus.valueOf(rs.getString("to_status")), rs.getString("decided_by"),
                            rs.getObject("decided_at", OffsetDateTime.class).toInstant(), rs.getString("note")),
                    RulesHash.sha256Hex(actionId), actionId);
        }
        catch (DataAccessException e) {
            throw new ActionStorageException("조치 " + actionId + " 의 이력을 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /**
     * 변동 이벤트의 식별자로 읽을 수 있는 글자인가. 저장소가 그대로 담지 못하는 글자(NUL, 짝 없는 대리 문자)가 들어
     * 있으면 어떤 변동 이벤트의 것도 아니다. 부르는 쪽이 요청을 먼저 가릴 때 쓴다: 아닌 것을 조회에 넘기면
     * {@link IllegalArgumentException} 이다.
     */
    public static boolean isReadableChangeEventId(String changeEventId) {
        return changeEventId != null && !changeEventId.isEmpty() && ActionGenerator.storable(changeEventId);
    }

    /** 개체의 식별자로 읽을 수 있는 글자인가. {@link #isReadableChangeEventId} 와 같은 기준이다. */
    public static boolean isReadableEntityId(String entityId) {
        return entityId != null && !entityId.isEmpty() && ActionGenerator.storable(entityId);
    }

    /** 조치의 식별자로 읽을 수 있는 글자인가. {@link #isReadableChangeEventId} 와 같은 기준이다. */
    public static boolean isReadableActionId(String actionId) {
        return actionId != null && !actionId.isEmpty() && ActionGenerator.storable(actionId);
    }

    /**
     * Pack 하나의 조치 목록.
     *
     * @param status 이 상태의 조치만. null 이면 가리지 않는다
     * @param changeEventId 이 변동 이벤트의 조치만. null 이면 가리지 않는다
     * @return 기한이 이른 것부터, 같으면 우선순위, 규칙 번호, 대상 개체의 식별자, 조치의 식별자 순 (D2 AD-243).
     *         해당하는 것이 없으면 (없는 Pack, 다른 Pack 의 변동 포함) 빈 목록
     */
    public List<Action> packActions(String packId, ActionStatus status, String changeEventId) {
        Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
        if (changeEventId != null) {
            ActionGenerator.requireStorable(changeEventId, "변동 이벤트의 식별자");
        }
        // 거르는 조건은 정해진 조각만 잇는다. 값은 모두 자리표시로 묶는다.
        StringBuilder sql = new StringBuilder("""
                select a.id, a.pack_id, s.change_event_id, a.rule_no, a.rule_label, a.action_text, a.priority,
                       a.applies_to, a.target_entity_id, a.target_entity_type, a.basis_entity_id,
                       a.basis_input_source_id, a.basis_edition_no, a.as_of, a.due_date, a.status, a.created_at
                  from action a
                  join impact_scope s on s.change_event_hash = a.change_event_hash
                 where a.pack_id = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(packId);
        if (status != null) {
            sql.append(" and a.status = ?\n");
            arguments.add(status.name());
        }
        if (changeEventId != null) {
            sql.append(" and a.change_event_hash = ?\n");
            arguments.add(RulesHash.sha256Hex(changeEventId));
        }
        sql.append(" order by a.due_date, a.priority, a.rule_no, a.target_entity_id, a.id");
        try {
            return jdbc.query(sql.toString(), ACTION, arguments.toArray());
        }
        catch (DataAccessException e) {
            throw new ActionStorageException("Pack " + packId + " 의 조치를 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /**
     * Pack 하나의 조치 목록을 나눠 돌려준다 (D2 AD-347). 거르는 것과 순서는
     * {@link #packActions(String, ActionStatus, String)} 와 같다: 나눠 받은 것을 이으면 한 번에 받은 것과 순서까지 같다.
     *
     * @param offset 건너뛸 수. 0 이상. 전체 수와 같거나 크면 빈 목록이다
     * @param limit 한 번에 받을 수. 1 이상. 위쪽 한도는 부르는 쪽이 정한다
     * @return 그 쪽의 조치와, 거른 조치의 전체 수
     */
    public ActionPage packActions(String packId, ActionStatus status, String changeEventId, int offset, int limit) {
        Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
        requirePage(offset, limit);
        if (changeEventId != null) {
            ActionGenerator.requireStorable(changeEventId, "변동 이벤트의 식별자");
        }
        StringBuilder where = new StringBuilder(" where a.pack_id = ?\n");
        List<Object> arguments = new ArrayList<>();
        arguments.add(packId);
        if (status != null) {
            where.append(" and a.status = ?\n");
            arguments.add(status.name());
        }
        if (changeEventId != null) {
            where.append(" and a.change_event_hash = ?\n");
            arguments.add(RulesHash.sha256Hex(changeEventId));
        }
        List<Object> paged = new ArrayList<>(arguments);
        paged.add(limit);
        paged.add(offset);
        try {
            // 전체 수와 그 쪽은 한 시점의 것으로 읽는다. 따로 읽으면 그 사이에 조치가 늘어 수와 목록이 어긋날 수 있다.
            return snapshot.execute(tx -> {
                Integer total = jdbc.queryForObject("select count(*) from action a\n" + where, Integer.class,
                        arguments.toArray());
                List<Action> actions = jdbc.query("""
                        select a.id, a.pack_id, s.change_event_id, a.rule_no, a.rule_label, a.action_text, a.priority,
                               a.applies_to, a.target_entity_id, a.target_entity_type, a.basis_entity_id,
                               a.basis_input_source_id, a.basis_edition_no, a.as_of, a.due_date, a.status, a.created_at
                          from action a
                          join impact_scope s on s.change_event_hash = a.change_event_hash
                        """ + where + " order by a.due_date, a.priority, a.rule_no, a.target_entity_id, a.id limit ? offset ?",
                        ACTION, paged.toArray());
                return new ActionPage(actions, total == null ? 0 : total);
            });
        }
        catch (DataAccessException | TransactionException e) {
            throw new ActionStorageException("Pack " + packId + " 의 조치를 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /**
     * 여러 변동 이벤트의 조치의 수를 한 번에 센다 (D2 AD-385). 변동마다
     * {@link #packActions(String, ActionStatus, String, int, int)} 의 전체 수를 따로 읽은 것과 같다: 그 Pack 의 조치만,
     * 상태를 가리지 않고 센다.
     *
     * @param changeEventIds 물을 변동 이벤트의 식별자. 같은 것이 여럿 있어도 된다. 수에 한도가 없다
     * @return 물은 변동마다의 수. 식별자의 글자 코드 순이다. 조치가 없는 변동 (저장된 적이 없는 변동, 다른 Pack 의 변동
     *         포함)은 0 으로 들어 있다. 물은 것이 없으면 빈 결과
     */
    public SortedMap<String, Integer> actionCounts(String packId, Collection<String> changeEventIds) {
        Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(changeEventIds, "변동 이벤트의 식별자 목록은 반드시 있어야 합니다");
        // 글자 코드 순이다 (D2 AD-38). 문자열의 기본 순서는 보충 문자에서 이것과 다르다.
        SortedMap<String, Integer> counts = new TreeMap<>(RulesOrder.STRINGS);
        // 해시로 묻고 식별자로 답한다. 찾는 데만 쓴다.
        Map<String, String> idByHash = new TreeMap<>();
        int position = 0;
        for (String changeEventId : changeEventIds) {
            // 여럿 가운데 어느 것이 틀렸는지 알린다.
            position++;
            Objects.requireNonNull(changeEventId, "변동 이벤트의 식별자 목록의 " + position + "번째 값이 없습니다");
            ActionGenerator.requireStorable(changeEventId, "변동 이벤트의 식별자 목록의 " + position + "번째 값");
            if (counts.put(changeEventId, 0) == null) {
                idByHash.put(RulesHash.sha256Hex(changeEventId), changeEventId);
            }
        }
        if (counts.isEmpty()) {
            return counts;
        }
        String[] hashes = idByHash.keySet().toArray(String[]::new);
        try {
            // 해시를 배열 하나로 넘긴다. 인자의 수에 한도가 없다. 문장 하나라 한 시점의 것이다.
            jdbc.query("""
                    select a.change_event_hash, count(*) as action_count
                      from action a
                     where a.pack_id = ? and a.change_event_hash = any(?)
                     group by a.change_event_hash
                    """, (PreparedStatement statement) -> {
                statement.setString(1, packId);
                statement.setArray(2, statement.getConnection().createArrayOf("text", hashes));
            }, (ResultSet rs) -> {
                counts.put(idByHash.get(rs.getString("change_event_hash")), rs.getInt("action_count"));
            });
        }
        catch (DataAccessException e) {
            throw new ActionStorageException("Pack " + packId + " 의 변동 " + counts.size()
                    + "개의 조치의 수를 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
        return counts;
    }

    /**
     * 여러 변동 이벤트의 저장된 영향 범위의 영향 대상의 수를 한 번에 센다 (카드 C-15, D2 AD-417). 변동마다
     * {@link #impactScopePage(String, String, int, int)} 의 전체 수를 따로 읽은 것과 같다.
     *
     * @param changeEventIds 물을 변동 이벤트의 식별자. 같은 것이 여럿 있어도 된다. 수에 한도가 없다
     * @return 영향 범위가 저장된 변동마다의 수. 식별자의 글자 코드 순이다. 영향 범위가 저장되지 않은 변동 (아직 처리되지
     *         않았거나 어느 단계에서든 실패한 변동, 없는 변동)은 들어 있지 않다: 0 은 계산했더니 영향 대상이 없다는 뜻이다.
     *         조치의 수를 세는 조회와 달리 Pack 으로 거르지 않는다: 변동의 식별자로만 찾는다
     */
    public SortedMap<String, Integer> impactTargetCounts(Collection<String> changeEventIds) {
        Objects.requireNonNull(changeEventIds, "변동 이벤트의 식별자 목록은 반드시 있어야 합니다");
        // 글자 코드 순이다 (D2 AD-38). 문자열의 기본 순서는 보충 문자에서 이것과 다르다.
        SortedMap<String, Integer> counts = new TreeMap<>(RulesOrder.STRINGS);
        // 해시로 묻고 식별자로 답한다. 찾는 데만 쓴다.
        Map<String, String> idByHash = new TreeMap<>();
        int position = 0;
        for (String changeEventId : changeEventIds) {
            position++;
            Objects.requireNonNull(changeEventId, "변동 이벤트의 식별자 목록의 " + position + "번째 값이 없습니다");
            ActionGenerator.requireStorable(changeEventId, "변동 이벤트의 식별자 목록의 " + position + "번째 값");
            idByHash.put(RulesHash.sha256Hex(changeEventId), changeEventId);
        }
        if (idByHash.isEmpty()) {
            return counts;
        }
        String[] hashes = idByHash.keySet().toArray(String[]::new);
        try {
            // 해시를 배열 하나로 넘긴다. 인자의 수에 한도가 없다. 문장 하나라 한 시점의 것이다.
            jdbc.query("""
                    select s.change_event_hash,
                           (select count(*) from impact_target t where t.change_event_hash = s.change_event_hash) as target_count
                      from impact_scope s
                     where s.change_event_hash = any(?)
                    """, (PreparedStatement statement) -> {
                statement.setArray(1, statement.getConnection().createArrayOf("text", hashes));
            }, (ResultSet rs) -> {
                counts.put(idByHash.get(rs.getString("change_event_hash")), rs.getInt("target_count"));
            });
        }
        catch (DataAccessException e) {
            throw new ActionStorageException("변동 " + idByHash.size()
                    + "개의 영향 대상의 수를 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
        return counts;
    }

    private static void requirePage(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("건너뛸 수는 0 이상이어야 합니다. 받은 값: " + offset + ".");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("한 번에 받을 수는 1 이상이어야 합니다. 받은 값: " + limit + ".");
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
            for (StoredImpactTarget target : targets) {
                if (target.steps().size() != target.depth()) {
                    // 근거 경로 없는 영향 대상은 저장되지 않는다. 여기 오면 저장소가 어긋난 것이다. 근거 없이 내보내지 않는다.
                    throw new ActionStorageException("변동 이벤트 " + changeEventId + " 의 영향 대상 " + target.entityId()
                            + " 의 근거 경로가 깊이와 맞지 않습니다 (깊이 " + target.depth() + ", 단계 " + target.steps().size()
                            + "). 저장소의 상태를 확인하세요.", null);
                }
            }
            StoredImpactScope head = heads.get(0);
            return Optional.of(new StoredImpactScope(head.changeEventId(), head.packId(), head.asOfPrevious(),
                    head.asOfEffective(), head.maxDepth(), head.truncated(), head.storedAt(), targets));
        }
        catch (DataAccessException e) {
            throw new ActionStorageException("변동 이벤트 " + changeEventId + " 의 영향 범위를 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /**
     * 변동 이벤트 하나의 저장된 영향 범위에서 영향 대상을 나눠 돌려준다 (D2 AD-347). 순서는
     * {@link #impactScope(String)} 와 같다: 나눠 받은 것을 이으면 한 번에 받은 것과 근거 경로까지 같다.
     * 전체 수와 유형별·깊이별 수는 나누기 전의, 거르지 않은 영향 범위 전체의 것이다.
     *
     * @param entityId 이 개체인 영향 대상만. null 이면 가리지 않는다. 그 개체가 영향 대상이 아니면 빈 목록이다
     * @param offset 건너뛸 수. 0 이상
     * @param limit 한 번에 받을 수. 1 이상. 위쪽 한도는 부르는 쪽이 정한다
     * @return 저장된 것이 없으면 (아직 처리되지 않았거나 실패한 변동) 비어 있다
     */
    public Optional<StoredImpactScopePage> impactScopePage(String changeEventId, String entityId, int offset, int limit) {
        Objects.requireNonNull(changeEventId, "변동 이벤트의 식별자는 반드시 있어야 합니다");
        ActionGenerator.requireStorable(changeEventId, "변동 이벤트의 식별자");
        requirePage(offset, limit);
        if (entityId != null) {
            if (entityId.isEmpty()) {
                throw new IllegalArgumentException("개체의 식별자가 비어 있습니다. 가리지 않으려면 넘기지 마세요.");
            }
            ActionGenerator.requireStorable(entityId, "개체의 식별자");
        }
        String hash = RulesHash.sha256Hex(changeEventId);
        try {
            // 저장된 영향 범위는 바뀌지 않지만, 머리와 대상과 단계는 한 시점의 것으로 읽는다.
            return snapshot.execute(tx -> readScopePage(changeEventId, hash, entityId, offset, limit));
        }
        catch (DataAccessException | TransactionException e) {
            throw new ActionStorageException("변동 이벤트 " + changeEventId + " 의 영향 범위를 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    private Optional<StoredImpactScopePage> readScopePage(String changeEventId, String hash, String entityId, int offset,
            int limit) {
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
        // 수는 거르지 않은 영향 범위 전체에서 센다. 유형은 글자 코드 순, 깊이는 수의 순이다.
        java.util.LinkedHashMap<String, Integer> byType = new java.util.LinkedHashMap<>();
        jdbc.query("""
                select entity_type, count(*) as n
                  from impact_target
                 where change_event_hash = ?
                 group by entity_type
                 order by entity_type
                """, rs -> {
            byType.put(rs.getString("entity_type"), rs.getInt("n"));
        }, hash);
        java.util.LinkedHashMap<Integer, Integer> byDepth = new java.util.LinkedHashMap<>();
        jdbc.query("""
                select depth, count(*) as n
                  from impact_target
                 where change_event_hash = ?
                 group by depth
                 order by depth
                """, rs -> {
            byDepth.put(rs.getInt("depth"), rs.getInt("n"));
        }, hash);
        int total = byDepth.values().stream().mapToInt(Integer::intValue).sum();
        // 이 쪽의 영향 대상. 대상 하나만 물으면 그 개체의 해시로 찾는다 (기본 키).
        List<Object> arguments = new ArrayList<>();
        arguments.add(hash);
        String only = "";
        if (entityId != null) {
            // 해시로 찾고 식별자로 한 번 더 견준다 (조치 하나를 찾을 때와 같은 방식).
            only = " and entity_hash = ? and entity_id = ?\n";
            arguments.add(RulesHash.sha256Hex(entityId));
            arguments.add(entityId);
        }
        arguments.add(limit);
        arguments.add(offset);
        List<String> hashes = new ArrayList<>();
        List<StoredImpactTarget> bare = jdbc.query("""
                select entity_hash, entity_id, entity_type, depth, path_start_entity_id, path_as_of
                  from impact_target
                 where change_event_hash = ?
                """ + only + " order by depth, entity_type, entity_id limit ? offset ?", (rs, row) -> {
            hashes.add(rs.getString("entity_hash"));
            return new StoredImpactTarget(rs.getString("entity_id"), rs.getString("entity_type"), rs.getInt("depth"),
                    rs.getString("path_start_entity_id"), rs.getObject("path_as_of", LocalDate.class), List.of());
        }, arguments.toArray());
        // 이 쪽의 대상의 단계만 읽는다. 대상의 해시로 묶는다: 찾는 데만 쓰고, 차례는 위의 질의가 정했다.
        TreeMap<String, List<StoredPathStep>> steps = new TreeMap<>(RulesOrder.STRINGS);
        if (!hashes.isEmpty()) {
            jdbc.query("""
                    select entity_hash, step_no, relation_id, relation_type, source_entity_id, target_entity_id
                      from impact_path_step
                     where change_event_hash = ? and entity_hash = any(?)
                     order by entity_hash, step_no
                    """, ps -> {
                ps.setString(1, hash);
                ps.setArray(2, ps.getConnection().createArrayOf("text", hashes.toArray()));
            }, rs -> {
                steps.computeIfAbsent(rs.getString("entity_hash"), key -> new ArrayList<>())
                    .add(new StoredPathStep(rs.getInt("step_no"), rs.getString("relation_id"), rs.getString("relation_type"),
                            rs.getString("source_entity_id"), rs.getString("target_entity_id")));
            });
        }
        List<StoredImpactTarget> targets = new ArrayList<>();
        for (int i = 0; i < bare.size(); i++) {
            StoredImpactTarget target = bare.get(i);
            List<StoredPathStep> path = steps.getOrDefault(hashes.get(i), List.of());
            if (path.size() != target.depth()) {
                // 근거 경로 없는 영향 대상은 저장되지 않는다. 여기 오면 저장소가 어긋난 것이다. 근거 없이 내보내지 않는다.
                throw new ActionStorageException("변동 이벤트 " + changeEventId + " 의 영향 대상 " + target.entityId()
                        + " 의 근거 경로가 깊이와 맞지 않습니다 (깊이 " + target.depth() + ", 단계 " + path.size()
                        + "). 저장소의 상태를 확인하세요.", null);
            }
            targets.add(new StoredImpactTarget(target.entityId(), target.entityType(), target.depth(),
                    target.pathStartEntityId(), target.pathAsOf(), path));
        }
        StoredImpactScope head = heads.get(0);
        return Optional.of(new StoredImpactScopePage(new StoredImpactScope(head.changeEventId(), head.packId(),
                head.asOfPrevious(), head.asOfEffective(), head.maxDepth(), head.truncated(), head.storedAt(), targets),
                total, byType, byDepth));
    }
}
