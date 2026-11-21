package com.altumlab.tabnit.rules;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 조치의 확정과 보류 (D8 5절, D2 AD-47). 상태를 바꾸고 누가 언제 했는지를 이력에 남긴다. 둘은 트랜잭션 하나로 함께 된다.
 *
 * <p>상태는 대기에서 확정이나 보류로, 보류에서 확정으로만 바뀐다. 확정은 되돌릴 수 없다.
 * 현재 시각을 읽지 않는다. 한 때는 밖에서 받는다. 누가 했는지는 아이디를 값으로 받는다: 그 아이디가 실제 사용자인지,
 * 확정할 권한이 있는지는 부르는 쪽이 확인한다 (D2 AD-246).
 *
 * <p>부르는 쪽이 이미 트랜잭션 안이면 그 트랜잭션에 든다. 그 트랜잭션의 격리 수준은 READ COMMITTED 여야 한다:
 * 더 높은 수준에서는, 그 트랜잭션이 시작한 뒤 다른 사람이 같은 조치를 바꿨을 때 거부가 아니라 저장소의 오류
 * ({@link ActionStorageException})가 되고 그 트랜잭션은 되돌려진다. 확정이 두 번 되지는 않는다.
 * 거부를 잡고 이어 가는 트랜잭션은 끝날 때까지 그 조치의 행을 잠근 채로 있다.
 */
public class ActionDecisions {

    /** 사유의 길이의 한도. 글자(코드 포인트)의 수다 (D2 AD-247). */
    static final int MAX_NOTE_LENGTH = 1000;

    /** 아이디의 길이의 한도. 글자(코드 포인트)의 수다. */
    static final int MAX_DECIDED_BY_LENGTH = 200;

    /** 받는 시각의 범위. 저장소가 담는 날짜의 범위와 같다 (0001 ~ 9999 년). */
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");

    private static final Instant LATEST = Instant.parse("+10000-01-01T00:00:00Z");

    private final JdbcTemplate jdbc;

    private final TransactionTemplate transaction;

    private final ActionRecords records;

    public ActionDecisions(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.records = new ActionRecords(dataSource);
    }

    /**
     * 조치를 확정한다. 대기이거나 보류인 조치만 확정할 수 있다.
     *
     * @param decidedBy 누가 했는가. 사용자의 아이디. 비어 있을 수 없다
     * @param note 사유. 없어도 된다. 비어 있으면 없는 것으로 남긴다
     * @param decidedAt 언제 했는가. 밖에서 받는다. 마이크로초까지 남긴다
     * @return 바뀐 뒤의 조치와 그 이력. 같은 트랜잭션에서 읽은 것이다: 이력의 마지막 줄이 방금 한 것이다
     * @throws ActionNotFoundException 그런 조치가 없을 때
     * @throws ActionDecisionRejectedException 이미 확정된 조치일 때. 아무것도 바뀌지 않는다
     * @throws ActionDecisionInputException 넘긴 값이 틀렸을 때. 어느 항목인지를 담는다. {@link IllegalArgumentException} 이기도 하다
     */
    public ActionWithHistory confirm(String actionId, String decidedBy, String note, Instant decidedAt) {
        return decide(actionId, ActionStatus.CONFIRMED, decidedBy, note, decidedAt);
    }

    /**
     * 조치를 보류한다. 대기인 조치만 보류할 수 있다.
     *
     * @param note 사유. 반드시 있어야 한다 (D2 AD-247)
     * @throws ActionDecisionRejectedException 이미 확정되었거나 보류된 조치일 때. 아무것도 바뀌지 않는다
     */
    public ActionWithHistory hold(String actionId, String decidedBy, String note, Instant decidedAt) {
        return decide(actionId, ActionStatus.ON_HOLD, decidedBy, note, decidedAt);
    }

    private ActionWithHistory decide(String actionId, ActionStatus to, String decidedBy, String rawNote, Instant decidedAt) {
        Objects.requireNonNull(actionId, "조치의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(decidedBy, "누가 했는지는 반드시 있어야 합니다");
        Objects.requireNonNull(decidedAt, "언제 했는지는 반드시 있어야 합니다");
        requireStorable(actionId, ActionDecisionInputException.Field.ACTION_ID, "조치의 식별자");
        if (blank(decidedBy)) {
            throw new ActionDecisionInputException(ActionDecisionInputException.Field.DECIDED_BY,
                    "누가 했는지가 비어 있습니다. 사용자의 아이디를 넣어 다시 보내세요.");
        }
        requireStorable(decidedBy, ActionDecisionInputException.Field.DECIDED_BY, "사용자의 아이디");
        if (decidedBy.codePointCount(0, decidedBy.length()) > MAX_DECIDED_BY_LENGTH) {
            throw new ActionDecisionInputException(ActionDecisionInputException.Field.DECIDED_BY,
                    "사용자의 아이디가 너무 깁니다. " + MAX_DECIDED_BY_LENGTH + "자까지 받습니다.");
        }
        if (rawNote != null && rawNote.codePointCount(0, rawNote.length()) > MAX_NOTE_LENGTH) {
            // 길이는 빈 값인지를 보기 전에 잰다. 공백뿐인 긴 값도 한도를 넘으면 받지 않는다.
            throw new ActionDecisionInputException(ActionDecisionInputException.Field.NOTE,
                    "사유가 너무 깁니다. " + MAX_NOTE_LENGTH + "자까지 받습니다. 줄여 다시 보내세요.");
        }
        String note = rawNote == null || blank(rawNote) ? null : rawNote;
        if (note == null && to == ActionStatus.ON_HOLD) {
            throw new ActionDecisionInputException(ActionDecisionInputException.Field.NOTE,
                    "보류에는 사유가 반드시 있어야 합니다. 왜 보류하는지 적어 다시 보내세요.");
        }
        if (note != null) {
            requireStorable(note, ActionDecisionInputException.Field.NOTE, "사유");
        }
        if (decidedAt.isBefore(EARLIEST) || !decidedAt.isBefore(LATEST)) {
            // 저장소가 담는 범위 밖이다. 저장하다가 실패하지 않도록 여기서 막는다.
            throw new ActionDecisionInputException(ActionDecisionInputException.Field.DECIDED_AT,
                    "언제 했는지는 0001 년부터 9999 년까지만 받습니다: " + decidedAt + ". 시각을 확인하세요.");
        }
        OffsetDateTime when = OffsetDateTime.ofInstant(decidedAt.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        String hash = RulesHash.sha256Hex(actionId);
        Object outcome;
        try {
            // 거부와 "없음"은 트랜잭션 밖에서 던진다. 안에서 던지면, 부르는 쪽이 이미 트랜잭션 안일 때 그 트랜잭션까지
            // 되돌려야 하는 것으로 표시된다.
            outcome = transaction.execute(status -> {
                // 그 조치의 행을 잠근다. 두 사람이 함께 바꾸려 하면 뒤의 것은 앞의 것이 끝난 뒤의 상태를 본다.
                List<String> current = jdbc.query("select status from action where id_hash = ? and id = ? for update",
                        (rs, row) -> rs.getString("status"), hash, actionId);
                if (current.isEmpty()) {
                    return new ActionNotFoundException("조치 " + actionId + " 이(가) 없습니다. 조치의 식별자를 확인하세요.");
                }
                ActionStatus from = ActionStatus.valueOf(current.get(0));
                if (!allowed(from, to)) {
                    // 마지막 이력을 같은 잠금 아래에서 읽는다. 알리는 지금의 상태와 어긋나지 않는다.
                    List<ActionDecision> history = records.history(actionId);
                    return new ActionDecisionRejectedException(from, to,
                            history.isEmpty() ? null : history.get(history.size() - 1), "조치 " + actionId + " 은(는) 지금 " + label(from)
                            + " 상태라 " + label(to) + "(으)로 바꿀 수 없습니다. 대기인 조치는 확정하거나 보류할 수 있고, 보류인 조치는 확정할 수 있습니다."
                            + " 확정된 조치는 바꿀 수 없습니다.");
                }
                jdbc.update("update action set status = ? where id_hash = ? and id = ?", to.name(), hash, actionId);
                jdbc.update("""
                        insert into action_history (action_id_hash, seq, from_status, to_status, decided_by, decided_at, note)
                        select ?, coalesce(max(seq), 0) + 1, ?, ?, ?, ?, ?
                          from action_history
                         where action_id_hash = ?
                        """, hash, from.name(), to.name(), decidedBy, when, note, hash);
                // 바뀐 조치는 같은 트랜잭션에서 읽어 돌려준다. 끝난 뒤에 읽으면 그 사이에 다른 사람이 바꾼 상태가
                // 돌아올 수 있다 (보류한 사람이 확정된 조치를 돌려받는다).
                return records.detail(actionId)
                    .orElseThrow(() -> new IllegalStateException("방금 바꾼 조치 " + actionId + " 을(를) 읽지 못했습니다."));
            });
        }
        catch (DataAccessException | TransactionException e) {
            throw new ActionStorageException("조치 " + actionId + " 의 상태를 바꾸지 못했습니다. 상태와 이력은 함께 바뀌거나 함께 안 바뀝니다."
                    + " 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
        if (outcome instanceof RuntimeException refused) {
            throw refused;
        }
        return (ActionWithHistory) outcome;
    }

    /**
     * "누가 했는가"로 받을 수 있는 값인가: 빈 값이 아니고, 200자 이하이고, 저장할 수 있는 글자다.
     * 부르는 쪽이 그 값을 미리 정해 둘 때 (설정 등) 확인하는 데 쓴다.
     */
    public static boolean isAcceptableDecidedBy(String decidedBy) {
        return decidedBy != null && !blank(decidedBy) && ActionGenerator.storable(decidedBy)
                && decidedBy.codePointCount(0, decidedBy.length()) <= MAX_DECIDED_BY_LENGTH;
    }

    /**
     * 빈 값인가. 눈에 보이는 글자가 하나도 없으면 빈 것으로 본다: 공백, 줄바꿈, 끊지 않는 공백, 폭 없는 글자뿐인 값.
     * 눈에 보이지 않는 글자로 "반드시 적는다"를 지나가지 못하게 한다.
     */
    private static boolean blank(String text) {
        return text.codePoints()
            .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT);
    }

    private static void requireStorable(String text, ActionDecisionInputException.Field field, String name) {
        if (!ActionGenerator.storable(text)) {
            throw new ActionDecisionInputException(field,
                    name + "에 저장할 수 없는 글자(NUL 이나 짝 없는 대리 문자)가 들어 있습니다. 그 글자를 빼고 다시 보내세요.");
        }
    }

    /** 대기에서 확정이나 보류로, 보류에서 확정으로만 간다 (D2 AD-47). */
    private static boolean allowed(ActionStatus from, ActionStatus to) {
        return (from == ActionStatus.PENDING && (to == ActionStatus.CONFIRMED || to == ActionStatus.ON_HOLD))
                || (from == ActionStatus.ON_HOLD && to == ActionStatus.CONFIRMED);
    }

    private static String label(ActionStatus status) {
        return switch (status) {
            case PENDING -> "대기";
            case CONFIRMED -> "확정";
            case ON_HOLD -> "보류";
        };
    }
}
