package com.altumlab.tabnit.common;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자 계정과 로그인의 판단 (D5 7절 app_user).
 *
 * <p>비밀번호는 해시로만 저장한다. 해시는 bcrypt 이고, 저장된 값의 앞에 방식의 이름을 함께 적는다: 뒤에 방식을 바꿔도
 * 옛 비밀번호가 그대로 확인된다 (D2 AD-264). 비밀번호와 그 해시를 예외의 문구나 기록에 담지 않는다.
 *
 * <p>잇달아 여러 번 틀리면 잠시 막는다 (D2 AD-265). 막힌 동안에는 맞는 비밀번호도 받지 않는다. 시간이 지나면 풀린다.
 *
 * <p>현재 시각을 읽지 않는다. 지금이 언제인지는 밖에서 받는다. 세션과 HTTP 는 이 클래스가 모른다 (api 가 맡는다).
 */
public class UserAccounts {

    /** 아이디: 영어 소문자나 숫자로 시작하고 영어 소문자, 숫자, 점, 밑줄, 붙임표만. 3자에서 50자. */
    private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{2,49}");

    /** 비밀번호의 가장 짧은 길이. 글자(코드 포인트)의 수다. */
    static final int MIN_PASSWORD_LENGTH = 12;

    /** bcrypt 는 72바이트까지만 본다. 그보다 긴 비밀번호는 뒤가 버려지므로 받지 않는다. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final JdbcTemplate jdbc;

    private final TransactionTemplate transaction;

    private final PasswordEncoder encoder;

    /** 없는 아이디일 때 견줄 해시. 계정이 있을 때와 같은 일을 해, 걸린 시간으로 계정이 있는지를 알 수 없게 한다. */
    private final String absentHash;

    private final int maxFailures;

    private final Duration lockDuration;

    /**
     * @param maxFailures 잇달아 이만큼 틀리면 막는다. 1 이상
     * @param lockDuration 막는 시간. 0 보다 길어야 한다
     */
    public UserAccounts(DataSource dataSource, int maxFailures, Duration lockDuration) {
        this(dataSource, maxFailures, lockDuration, PasswordEncoderFactories.createDelegatingPasswordEncoder());
    }

    /** 시험이 해시를 견주는 횟수를 세려고 쓴다. */
    UserAccounts(DataSource dataSource, int maxFailures, Duration lockDuration, PasswordEncoder encoder) {
        Objects.requireNonNull(encoder, "해시 방식은 반드시 있어야 합니다");
        Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
        Objects.requireNonNull(lockDuration, "막는 시간은 반드시 있어야 합니다");
        if (maxFailures < 1) {
            throw new IllegalArgumentException("막기까지 틀려도 되는 횟수는 1 이상입니다: " + maxFailures + ". 설정을 확인하세요.");
        }
        if (lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("막는 시간은 0 보다 길어야 합니다: " + lockDuration + ". 설정을 확인하세요.");
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
        this.encoder = encoder;
        this.absentHash = encoder.encode("-");
    }

    /**
     * 계정을 만든다.
     *
     * @param password 비밀번호. 12자 이상, 72바이트 이하. 해시로만 저장한다
     * @param createdAt 만든 때. 밖에서 받는다
     * @throws UserAccountRejectedException 아이디나 비밀번호가 규칙에 맞지 않거나 이미 있는 아이디일 때
     */
    public UserAccount create(String loginId, String password, Role role, Instant createdAt) {
        Objects.requireNonNull(loginId, "아이디는 반드시 있어야 합니다");
        Objects.requireNonNull(password, "비밀번호는 반드시 있어야 합니다");
        Objects.requireNonNull(role, "역할은 반드시 있어야 합니다");
        Objects.requireNonNull(createdAt, "만든 때는 반드시 있어야 합니다");
        if (!LOGIN_ID.matcher(loginId).matches()) {
            throw new UserAccountRejectedException("아이디를 쓸 수 없습니다. 영어 소문자나 숫자로 시작하고 영어 소문자, 숫자, 점(.), 밑줄(_), 붙임표(-)만 써서"
                    + " 3자에서 50자로 정하세요.");
        }
        requireStorablePassword(password);
        try {
            jdbc.update("""
                    insert into app_user (login_id, password_hash, role, failed_count, locked_until, created_at)
                    values (?, ?, ?, 0, null, ?)
                    """, loginId, encoder.encode(password), role.name(),
                    OffsetDateTime.ofInstant(createdAt.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
        }
        catch (DuplicateKeyException e) {
            throw new UserAccountRejectedException("아이디 " + loginId + " 은(는) 이미 있습니다. 다른 아이디로 정하세요.");
        }
        return new UserAccount(loginId, role);
    }

    /** 비밀번호의 규칙: 12자 이상, 72바이트 이하, NUL 글자 없음. 만들 때와 바꿀 때가 같다. 문구에 비밀번호를 담지 않는다. */
    private static void requireStorablePassword(String password) {
        if (password.codePointCount(0, password.length()) < MIN_PASSWORD_LENGTH) {
            throw new UserAccountRejectedException("비밀번호가 너무 짧습니다. " + MIN_PASSWORD_LENGTH + "자 이상으로 정하세요.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new UserAccountRejectedException("비밀번호가 너무 깁니다. " + MAX_PASSWORD_BYTES
                    + "바이트까지 받습니다 (한글은 한 글자가 3바이트입니다). 줄여서 정하세요.");
        }
        if (password.indexOf('\u0000') >= 0) {
            throw new UserAccountRejectedException("비밀번호에 쓸 수 없는 글자가 있습니다. 다른 비밀번호로 정하세요.");
        }
    }

    /**
     * 로그인 막힘을 푼다. 틀린 횟수도 처음으로 돌린다. 막혀 있지 않은 계정을 풀어도 된다 (D2 AD-320).
     * 비밀번호와 역할은 바꾸지 않는다.
     *
     * @throws UserAccountRejectedException 그런 아이디의 계정이 없을 때
     */
    public void unlock(String loginId) {
        Objects.requireNonNull(loginId, "아이디는 반드시 있어야 합니다");
        int changed = LOGIN_ID.matcher(loginId).matches()
                ? jdbc.update("update app_user set failed_count = 0, locked_until = null where login_id = ?", loginId)
                : 0;
        if (changed == 0) {
            throw noSuchAccount(loginId);
        }
    }

    /**
     * 비밀번호를 바꾼다. 막힘도 함께 풀어 바로 로그인할 수 있게 한다 (D2 AD-320, AD-346). 역할은 바꾸지 않는다.
     * 이미 로그인된 세션은 이 클래스가 알지 못한다: 끝내지 않는다.
     *
     * @param password 새 비밀번호. 만들 때와 같은 규칙이다. 해시로만 저장한다
     * @throws UserAccountRejectedException 그런 아이디의 계정이 없거나 비밀번호가 규칙에 맞지 않을 때
     */
    public void changePassword(String loginId, String password) {
        Objects.requireNonNull(loginId, "아이디는 반드시 있어야 합니다");
        Objects.requireNonNull(password, "비밀번호는 반드시 있어야 합니다");
        if (!LOGIN_ID.matcher(loginId).matches()) {
            throw noSuchAccount(loginId);
        }
        requireStorablePassword(password);
        int changed = jdbc.update(
                "update app_user set password_hash = ?, failed_count = 0, locked_until = null where login_id = ?",
                encoder.encode(password), loginId);
        if (changed == 0) {
            throw noSuchAccount(loginId);
        }
    }

    private static UserAccountRejectedException noSuchAccount(String loginId) {
        // 규칙에 맞지 않는 아이디는 그대로 되돌려 적지 않는다: 어떤 글자든 들어올 수 있다.
        String shown = LOGIN_ID.matcher(loginId).matches() ? "아이디 " + loginId + " 의 계정이" : "그런 아이디의 계정이";
        return new UserAccountRejectedException(shown + " 없습니다. 아이디를 확인하세요.");
    }

    /** 아이디로 계정을 읽는다. 없으면 비어 있다. */
    public Optional<UserAccount> find(String loginId) {
        Objects.requireNonNull(loginId, "아이디는 반드시 있어야 합니다");
        if (!LOGIN_ID.matcher(loginId).matches()) {
            return Optional.empty();
        }
        return jdbc.query("select login_id, role from app_user where login_id = ?",
                (rs, row) -> new UserAccount(rs.getString("login_id"), Role.valueOf(rs.getString("role"))), loginId)
            .stream()
            .findFirst();
    }

    /**
     * 로그인을 한 번 시도한다. 틀린 횟수와 막힘을 함께 고친다.
     *
     * <p>아이디가 없는 것과 비밀번호가 틀린 것은 같은 결과다 (계정이 있는지를 알려 주지 않는다).
     * 어떤 글자가 들어와도 예외를 던지지 않는다: 규칙에 맞지 않는 아이디는 없는 아이디다.
     *
     * @param now 지금. 밖에서 받는다
     */
    public LoginAttempt authenticate(String loginId, String password, Instant now) {
        Objects.requireNonNull(loginId, "아이디는 반드시 있어야 합니다");
        Objects.requireNonNull(password, "비밀번호는 반드시 있어야 합니다");
        Objects.requireNonNull(now, "지금이 언제인지는 반드시 있어야 합니다");
        // 저장할 수 있는 비밀번호만 해시와 견준다. 그 밖의 것은 어느 계정의 비밀번호도 아니다.
        boolean plausible = password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES
                && password.indexOf('\u0000') < 0;
        if (!LOGIN_ID.matcher(loginId).matches()) {
            // 없는 아이디다. 계정이 있을 때와 같은 일을 한다.
            encoder.matches(plausible ? password : "-", absentHash);
            return failed();
        }
        return transaction.execute(status -> {
            // 그 계정의 행을 잠근다. 함께 온 시도가 틀린 횟수를 빠뜨리지 않는다.
            List<Stored> rows = jdbc.query(
                    "select password_hash, role, failed_count, locked_until from app_user where login_id = ? for update",
                    (rs, row) -> new Stored(rs.getString("password_hash"), Role.valueOf(rs.getString("role")),
                            rs.getInt("failed_count"), rs.getObject("locked_until", OffsetDateTime.class)),
                    loginId);
            if (rows.isEmpty()) {
                encoder.matches(plausible ? password : "-", absentHash);
                return failed();
            }
            Stored stored = rows.get(0);
            // 어떤 경우에도 해시를 꼭 한 번 견준다: 걸린 시간으로 계정이 있는지, 막혀 있는지를 알 수 없게 한다.
            // 저장할 수 없는 비밀번호는 견주기만 하고 맞는 것으로 치지 않는다.
            boolean matched = encoder.matches(plausible ? password : "-", stored.passwordHash()) && plausible;
            if (stored.lockedUntil() != null && now.isBefore(stored.lockedUntil().toInstant())) {
                // 막혀 있다. 비밀번호가 맞아도 받지 않고, 막힌 시간도 늘리지 않는다.
                return new LoginAttempt(LoginAttempt.Outcome.LOCKED, null, stored.lockedUntil().toInstant());
            }
            // 막힘이 풀렸으면 처음부터 다시 센다.
            int failures = stored.lockedUntil() != null ? 0 : stored.failedCount();
            if (matched) {
                jdbc.update("update app_user set failed_count = 0, locked_until = null where login_id = ?", loginId);
                return new LoginAttempt(LoginAttempt.Outcome.SUCCEEDED, new UserAccount(loginId, stored.role()), null);
            }
            if (failures + 1 >= maxFailures) {
                jdbc.update("update app_user set failed_count = 0, locked_until = ? where login_id = ?",
                        OffsetDateTime.ofInstant(now.plus(lockDuration).truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC),
                        loginId);
            }
            else {
                jdbc.update("update app_user set failed_count = ?, locked_until = null where login_id = ?", failures + 1,
                        loginId);
            }
            return failed();
        });
    }

    private static LoginAttempt failed() {
        return new LoginAttempt(LoginAttempt.Outcome.FAILED, null, null);
    }

    private record Stored(String passwordHash, Role role, int failedCount, OffsetDateTime lockedUntil) {
    }
}
