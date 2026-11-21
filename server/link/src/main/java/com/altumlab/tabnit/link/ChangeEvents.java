package com.altumlab.tabnit.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 변동 이벤트를 읽고, 뒤 단계(영향 범위 계산, 규칙 실행)의 성공과 실패를 표시한다 (보드 C-06 가운데 link 가 맡는 것).
 *
 * <p>순서를 잡는 것은 api 다. api 가 여기서 처리되지 않은 변동을 읽어 core 와 rules 를 차례로 부르고, 결과를
 * 여기에 표시한다 (D2 AD-44). 이 모듈은 영향 범위를 계산하지 않고 규칙을 부르지 않는다.
 *
 * <p>상태의 바뀜 (D2 AD-154, AD-206): 대기는 처리됨이나 실패가 된다. 실패는 다시 실행한 결과로 처리됨이나 실패가 된다.
 * 처리됨은 끝이다. 이 클래스는 현재 시각을 읽지 않는다.
 */
public class ChangeEvents {

    private static final String COLUMNS = """
            e.id, e.edition_id, e.item_key, e.change_kind, e.primary_entity_id, e.status, e.failed_stage,
            e.failure_code
            """;

    private final JdbcTemplate jdbc;

    /** 읽기는 한 시점의 것으로 한다. 이벤트와 변동 대상을 두 문장으로 읽는 사이에 새 판이 들어와도 어긋나지 않는다. */
    private final TransactionTemplate snapshot;

    public ChangeEvents(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
        this.jdbc = new JdbcTemplate(dataSource);
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 이 Pack 의 처리되지 않은 변동 이벤트: 대기와 실패. 판을 받은 차례이고, 그 안에서는 항목의 키의 글자 코드 순이다.
     * 받은 때가 같은 판은 입력 자료의 식별자 순, 그 안에서는 판 번호 순이다 (D2 AD-207). 판 번호는 수로 견준다:
     * 판의 식별자를 글자로 견주면 10번째 판이 2번째 판보다 앞선다. 변동 대상은 식별자 순이다.
     */
    public List<ChangeEvent> unprocessed(String packId) {
        Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
        return snapshot.execute(status -> readUnprocessed(packId));
    }

    private List<ChangeEvent> readUnprocessed(String packId) {
        Map<String, List<String>> targets = new TreeMap<>(LinkOrder.STRINGS);
        jdbc.query("""
                select t.change_event_id, t.entity_id
                  from change_target t
                  join change_event e on e.id = t.change_event_id
                  join edition d on d.id = e.edition_id
                 where d.pack_id = ? and e.status in ('PENDING', 'FAILED')
                 order by t.change_event_id, t.entity_id
                """, rs -> {
            targets.computeIfAbsent(rs.getString("change_event_id"), k -> new ArrayList<>())
                .add(rs.getString("entity_id"));
        }, packId);
        return jdbc.query("select " + COLUMNS + """
                  from change_event e
                  join edition d on d.id = e.edition_id
                 where d.pack_id = ? and e.status in ('PENDING', 'FAILED')
                 order by d.received_at, d.input_source_id, d.edition_no, e.item_key
                """, mapper(targets), packId);
    }

    /** 변동 이벤트 하나. 없으면 비어 있다. */
    public Optional<ChangeEvent> find(String changeEventId) {
        Objects.requireNonNull(changeEventId, "변동 이벤트의 식별자는 반드시 있어야 합니다");
        return snapshot.execute(status -> read(changeEventId));
    }

    private Optional<ChangeEvent> read(String changeEventId) {
        Map<String, List<String>> targets = new TreeMap<>(LinkOrder.STRINGS);
        targets.put(changeEventId, jdbc.queryForList("""
                select entity_id
                  from change_target
                 where change_event_id = ?
                 order by entity_id
                """, String.class, changeEventId));
        return jdbc.query("select " + COLUMNS + """
                  from change_event e
                 where e.id = ?
                """, mapper(targets), changeEventId).stream().findFirst();
    }

    /**
     * 뒤 단계를 마쳤다고 표시한다. 대기와 실패에서만 바뀐다. 이미 처리됨이면 그대로 두고 성공으로 답한다.
     *
     * @throws ChangeEventRejectedException 그런 변동 이벤트가 없을 때
     */
    public void markProcessed(String changeEventId) {
        Objects.requireNonNull(changeEventId, "변동 이벤트의 식별자는 반드시 있어야 합니다");
        int changed = jdbc.update("""
                update change_event
                   set status = 'PROCESSED', failed_stage = null, failure_code = null
                 where id = ? and status in ('PENDING', 'FAILED')
                """, changeEventId);
        if (changed == 0) {
            // 바뀌지 않았다. 없는 것인지, 이미 처리된 것인지 가린다. 이미 처리됨이면 같은 표시를 다시 한 것이다.
            require(changeEventId);
        }
    }

    /**
     * 뒤 단계가 실패했다고 표시한다. 대기와 실패에서만 바뀐다. 실패에서 다시 표시하면 단계와 이유가 새것으로 바뀐다.
     *
     * @param stage 어느 단계에서 실패했는가
     * @param failureCode 실패한 이유의 코드. 문구가 아니라 코드다. 비어 있을 수 없다
     * @throws ChangeEventRejectedException 그런 변동 이벤트가 없거나 이미 처리됨일 때. 처리됨은 실패로 바꾸지 못한다
     */
    public void markFailed(String changeEventId, ChangeFailedStage stage, String failureCode) {
        Objects.requireNonNull(changeEventId, "변동 이벤트의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(stage, "실패한 단계는 반드시 있어야 합니다");
        Objects.requireNonNull(failureCode, "실패한 이유의 코드는 반드시 있어야 합니다");
        if (failureCode.isBlank() || failureCode.indexOf('\u0000') >= 0 || hasUnpairedSurrogate(failureCode)) {
            // 저장소가 담지 못하거나 다른 글자로 바꿔 담는 글자다. 코드는 나중에 글자 그대로 견주므로 조용히 바뀌면 안 된다.
            throw new IllegalArgumentException(
                    "실패한 이유의 코드는 비어 있거나 NUL 글자, 짝이 맞지 않는 대리 문자를 담을 수 없습니다. 코드를 넘기세요.");
        }
        int changed = jdbc.update("""
                update change_event
                   set status = 'FAILED', failed_stage = ?, failure_code = ?
                 where id = ? and status in ('PENDING', 'FAILED')
                """, stage.name(), failureCode, changeEventId);
        if (changed == 0) {
            ChangeEvent current = require(changeEventId);
            throw new ChangeEventRejectedException("변동 이벤트 " + changeEventId + " 은(는) 이미 처리됨(" + current.status()
                    + ")이라 실패로 바꿀 수 없습니다. 처리된 변동의 결과는 바꾸지 않습니다.");
        }
    }

    private static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            }
            else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    private ChangeEvent require(String changeEventId) {
        return find(changeEventId).orElseThrow(() -> new ChangeEventRejectedException(
                "변동 이벤트가 없습니다: " + changeEventId + ". 식별자를 확인하세요."));
    }

    private static RowMapper<ChangeEvent> mapper(Map<String, List<String>> targets) {
        return (rs, n) -> new ChangeEvent(rs.getString("id"), rs.getString("edition_id"), rs.getString("item_key"),
                ChangeKind.valueOf(rs.getString("change_kind")), rs.getString("primary_entity_id"),
                ChangeEventStatus.valueOf(rs.getString("status")), rs.getString("failed_stage"),
                rs.getString("failure_code"), targets.getOrDefault(rs.getString("id"), List.of()));
    }
}
