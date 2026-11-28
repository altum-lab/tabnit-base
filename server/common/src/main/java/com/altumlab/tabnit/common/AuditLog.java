package com.altumlab.tabnit.common;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 감사 기록 (D5 7절 audit_log): 누가, 언제, 무엇을 조회하거나 바꿨는지와 그 결과를 한 곳에 남긴다.
 *
 * <p>더하기만 한다. 고치거나 지우는 길이 없다 (D2 AD-276). 한 줄은 남기는 즉시 저장된다: 부른 쪽의 일이 뒤에 실패해도
 * 남긴 줄은 남는다.
 *
 * <p>요청에서 온 값이 그대로 들어오므로, 저장할 수 없는 글자와 너무 긴 값은 여기서 다듬는다. 그런 값 때문에 기록을 못 해
 * 요청이 실패하는 일이 없게 한다. 현재 시각을 읽지 않는다. 언제인지는 밖에서 받는다.
 */
public class AuditLog {

    /** 줄마다 담는 길이의 한도. 글자(코드 포인트)의 수다. 넘으면 자르고 끝에 표시를 둔다. */
    static final int MAX_ACTOR_LENGTH = 200;

    static final int MAX_ACTION_LENGTH = 100;

    static final int MAX_TARGET_LENGTH = 1000;

    static final int MAX_DETAIL_LENGTH = 4000;

    /** 잘랐다는 표시. */
    private static final String CUT = "…";

    private final JdbcTemplate jdbc;

    /** 나눠 읽을 때 전체 수와 그 쪽을 한 시점의 것으로 읽는다. 줄을 남기는 일({@link #record})은 이 안에서 하지 않는다. */
    private final TransactionTemplate snapshot;

    public AuditLog(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 한 줄을 남긴다.
     *
     * @return 남긴 줄의 식별자
     * @throws AuditLogException 남기지 못했을 때. 부른 쪽은 하려던 일을 하지 않는다
     */
    public long record(AuditEntry entry) {
        Objects.requireNonNull(entry, "남길 내용은 반드시 있어야 합니다");
        String actor = clean(entry.actor(), MAX_ACTOR_LENGTH);
        String action = clean(entry.action(), MAX_ACTION_LENGTH);
        String target = clean(entry.target(), MAX_TARGET_LENGTH);
        String detail = clean(entry.detail(), MAX_DETAIL_LENGTH);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        insert into audit_log
                            (occurred_at, actor, action, target, as_of, outcome, http_status, detail, started_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, new String[] { "id" });
                statement.setObject(1,
                        OffsetDateTime.ofInstant(entry.occurredAt().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
                statement.setString(2, actor == null || actor.isEmpty() ? null : actor);
                statement.setString(3, action);
                statement.setString(4, target);
                if (entry.asOf() == null) {
                    statement.setNull(5, Types.DATE);
                }
                else {
                    statement.setObject(5, entry.asOf());
                }
                statement.setString(6, entry.outcome().name());
                if (entry.httpStatus() == null) {
                    statement.setNull(7, Types.INTEGER);
                }
                else {
                    statement.setInt(7, entry.httpStatus());
                }
                statement.setString(8, detail);
                if (entry.startedId() == null) {
                    statement.setNull(9, Types.BIGINT);
                }
                else {
                    statement.setLong(9, entry.startedId());
                }
                return statement;
            }, key);
        }
        catch (DataAccessException e) {
            throw new AuditLogException("감사 기록을 남기지 못했습니다. 저장소의 상태를 확인하세요.", e);
        }
        Number id = key.getKey();
        if (id == null) {
            throw new AuditLogException("감사 기록을 남겼는데 식별자를 받지 못했습니다. 저장소의 상태를 확인하세요.", null);
        }
        return id.longValue();
    }

    /** 가장 최근에 남긴 줄의 식별자. 아직 한 줄도 없으면 0. */
    public long lastId() {
        try {
            Long id = jdbc.queryForObject("select coalesce(max(id), 0) from audit_log", Long.class);
            return id == null ? 0 : id;
        }
        catch (DataAccessException e) {
            throw new AuditLogException("감사 기록을 읽지 못했습니다. 저장소의 상태를 확인하세요.", e);
        }
    }

    /** 그 식별자보다 뒤에 남긴 줄을 식별자 순으로 읽는다. */
    public List<AuditRecord> after(long id) {
        try {
            return jdbc.query("""
                    select id, occurred_at, actor, action, target, as_of, outcome, http_status, detail, started_id
                    from audit_log
                    where id > ?
                    order by id
                    """, AuditLog::toRecord, id);
        }
        catch (DataAccessException e) {
            throw new AuditLogException("감사 기록을 읽지 못했습니다. 저장소의 상태를 확인하세요.", e);
        }
    }

    /**
     * 조건에 맞는 줄을 나눠 읽는다 (카드 E-22, D2 AD-414). 읽기만 한다: 줄을 더하지 않는다.
     *
     * <p>순서는 일련번호의 내림차순이다 (가장 최근에 남긴 것부터). 일련번호는 유일하므로 동률이 없다.
     * 전체 수와 그 쪽을 한 시점의 것으로 읽는다 (읽기 전용, REPEATABLE READ). 부르는 쪽이 이미 트랜잭션 안이면 그
     * 트랜잭션에 들어 그 격리 수준을 따른다.
     *
     * @param filter 거르는 조건. 거르지 않으려면 {@link AuditFilter#NONE}
     * @param offset 건너뛸 수. 0 이상. 전체 수와 같거나 크면 빈 목록이다
     * @param limit 한 번에 받을 수. 1 이상. 위쪽 한도는 부르는 쪽이 정한다
     * @return 그 쪽의 줄과, 나누기 전의 전체 수
     * @throws AuditLogException 읽지 못했을 때
     * @throws IllegalArgumentException 나누는 값이 틀렸을 때
     */
    public AuditPage page(AuditFilter filter, int offset, int limit) {
        Objects.requireNonNull(filter, "거르는 조건은 반드시 있어야 합니다. 거르지 않으려면 AuditFilter.NONE 을 넘깁니다");
        if (offset < 0) {
            throw new IllegalArgumentException("건너뛸 수는 0 이상이어야 합니다. 받은 값: " + offset + ".");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("한 번에 받을 수는 1 이상이어야 합니다. 받은 값: " + limit + ".");
        }
        // 준 조건만 붙인다. 붙이는 차례는 정해져 있다.
        StringBuilder where = new StringBuilder();
        List<Object> arguments = new ArrayList<>();
        if (filter.actor() != null) {
            where.append(" and actor = ?");
            // 남길 때와 같은 법으로 다듬어 견준다 (제어 문자는 빈칸, 긴 값은 자른다). 그대로 견주면 다듬어 남긴 줄을
            // 영영 찾지 못한다.
            arguments.add(clean(filter.actor(), MAX_ACTOR_LENGTH));
        }
        if (filter.action() != null) {
            where.append(" and action = ?");
            arguments.add(clean(filter.action(), MAX_ACTION_LENGTH));
        }
        if (filter.outcome() != null) {
            where.append(" and outcome = ?");
            arguments.add(filter.outcome().name());
        }
        if (filter.from() != null) {
            where.append(" and occurred_at >= ?");
            arguments.add(OffsetDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
        }
        if (filter.to() != null) {
            where.append(" and occurred_at < ?");
            arguments.add(OffsetDateTime.ofInstant(filter.to(), ZoneOffset.UTC));
        }
        if (filter.maxId() != null) {
            where.append(" and id <= ?");
            arguments.add(filter.maxId());
        }
        String count = "select count(*) from audit_log where true" + where;
        String page = """
                select id, occurred_at, actor, action, target, as_of, outcome, http_status, detail, started_id
                from audit_log
                where true""" + where + "\norder by id desc\nlimit ? offset ?";
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(limit);
        pageArguments.add(offset);
        try {
            // 전체 수와 그 쪽은 한 시점의 것으로 읽는다. 따로 읽으면 그 사이에 줄이 늘어 수와 목록이 어긋날 수 있다.
            return snapshot.execute(status -> {
                Integer total = jdbc.queryForObject(count, Integer.class, arguments.toArray());
                List<AuditRecord> records = jdbc.query(page, AuditLog::toRecord, pageArguments.toArray());
                return new AuditPage(records, total == null ? 0 : total);
            });
        }
        catch (DataAccessException | TransactionException e) {
            throw new AuditLogException("감사 기록을 읽지 못했습니다. 저장소의 상태를 확인하세요.", e);
        }
    }

    private static AuditRecord toRecord(ResultSet rs, int rowNum) throws SQLException {
        Date asOf = rs.getDate("as_of");
        int status = rs.getInt("http_status");
        Integer httpStatus = rs.wasNull() ? null : status;
        long started = rs.getLong("started_id");
        Long startedId = rs.wasNull() ? null : started;
        return new AuditRecord(rs.getLong("id"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("actor"), rs.getString("action"), rs.getString("target"),
                asOf == null ? null : asOf.toLocalDate(), AuditOutcome.valueOf(rs.getString("outcome")), httpStatus,
                rs.getString("detail"), startedId);
    }

    /**
     * 저장할 수 있는 글자로 다듬고 한도에 맞춰 자른다. 제어 글자(널 글자, 줄바꿈, 탭 등)는 빈칸으로, 짝 없는 대리 문자는
     * 대체 글자로 바꾼다. 한 줄의 기록이 여러 줄로 보이거나 저장을 막지 않게 한다.
     */
    private static String clean(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), maxLength + 1));
        int count = 0;
        int total = text.codePointCount(0, text.length());
        for (int i = 0; i < text.length();) {
            if (count == maxLength - 1 && total > maxLength) {
                out.append(CUT);
                break;
            }
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) {
                out.append(' ');
            }
            else if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                out.append('�');
            }
            else {
                out.appendCodePoint(codePoint);
            }
            count++;
        }
        return out.toString();
    }
}
