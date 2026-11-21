package com.altumlab.tabnit.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E-02 (common 쪽): 사용자 계정과 로그인의 판단.
 *
 * <p>비밀번호는 해시로만 저장한다. 잇달아 여러 번 틀리면 잠시 막는다 (D2 AD-264, AD-265).
 * 현재 시각을 읽지 않는다: 지금이 언제인지는 밖에서 받는다. 아이디와 비밀번호는 모두 시험용으로 지어낸 값이다.
 */
class UserAccountsTest {

    private static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");

    /** 시험용으로 지어낸 비밀번호. 실제 계정의 것이 아니다. */
    private static final String PASSWORD = "지어낸-시험용-비밀번호-1";

    private static final String WRONG = "틀린-시험용-비밀번호-9";

    private final JdbcTemplate jdbc = new JdbcTemplate(CommonTestDatabase.dataSource());

    /** 잇달아 5번 틀리면 15분 막는다. */
    private final UserAccounts accounts = new UserAccounts(CommonTestDatabase.dataSource(), 5, Duration.ofMinutes(15));

    // ── 계정 ─────────────────────────────────────────────────────

    @Test
    void 계정을_만들면_비밀번호는_해시로만_저장되고_맞는_비밀번호로_로그인된다() {
        UserAccount created = accounts.create("u-basic", PASSWORD, Role.OFFICER, T0);

        assertThat(created).isEqualTo(new UserAccount("u-basic", Role.OFFICER));
        assertThat(accounts.find("u-basic")).contains(created);
        // 저장된 값은 평문이 아니고, 어떤 방식의 해시인지가 함께 적혀 있다.
        String stored = jdbc.queryForObject("select password_hash from app_user where login_id = ?", String.class, "u-basic");
        assertThat(stored).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        LoginAttempt attempt = accounts.authenticate("u-basic", PASSWORD, T0.plusSeconds(60));
        assertThat(attempt.outcome()).isEqualTo(LoginAttempt.Outcome.SUCCEEDED);
        assertThat(attempt.account()).isEqualTo(created);
        // 같은 비밀번호라도 계정마다 해시가 다르다.
        accounts.create("u-basic-2", PASSWORD, Role.ADMIN, T0);
        assertThat(jdbc.queryForObject("select password_hash from app_user where login_id = ?", String.class, "u-basic-2"))
            .isNotEqualTo(stored);
        assertThat(accounts.find("u-basic-2").orElseThrow().role()).isEqualTo(Role.ADMIN);
        assertThat(accounts.find("u-없는-사람")).isEmpty();
    }

    @Test
    void 틀린_비밀번호와_없는_아이디는_같은_결과이고_계정이_있는지_알_수_없다() {
        accounts.create("u-wrong", PASSWORD, Role.OFFICER, T0);

        LoginAttempt wrongPassword = accounts.authenticate("u-wrong", WRONG, T0);
        LoginAttempt unknown = accounts.authenticate("u-wrong-없음", PASSWORD, T0);

        assertThat(wrongPassword.outcome()).isEqualTo(LoginAttempt.Outcome.FAILED);
        assertThat(wrongPassword.account()).isNull();
        assertThat(unknown).isEqualTo(wrongPassword);
        // 없는 아이디로 로그인하려 해도 계정이 생기지 않는다.
        assertThat(accounts.find("u-wrong-없음")).isEmpty();
    }

    @Test
    void 같은_아이디로_다시_만들거나_규칙에_맞지_않는_아이디와_비밀번호는_거부한다() {
        accounts.create("u-rules", PASSWORD, Role.OFFICER, T0);

        assertThatThrownBy(() -> accounts.create("u-rules", PASSWORD, Role.ADMIN, T0))
            .isInstanceOf(UserAccountRejectedException.class)
            .hasMessageContaining("u-rules");
        // 앞의 계정은 그대로다.
        assertThat(accounts.find("u-rules").orElseThrow().role()).isEqualTo(Role.OFFICER);
        // 아이디: 영어 소문자나 숫자로 시작하고 영어 소문자, 숫자, 점, 밑줄, 붙임표만. 3자에서 50자.
        for (String bad : List.of("ab", "A-upper", "-start", "has space", "한글아이디", "a".repeat(51), "a\u0000b")) {
            assertThatThrownBy(() -> accounts.create(bad, PASSWORD, Role.OFFICER, T0))
                .as(bad)
                .isInstanceOf(UserAccountRejectedException.class)
                .hasMessageContaining("아이디");
        }
        accounts.create("a1.b_c-d", PASSWORD, Role.OFFICER, T0);
        accounts.create("u" + "9".repeat(49), PASSWORD, Role.OFFICER, T0);
        // 비밀번호: 12자 이상 (글자의 수), 72바이트 이하.
        assertThatThrownBy(() -> accounts.create("u-short", "열한글자비밀번호입니다".substring(0, 11), Role.OFFICER, T0))
            .isInstanceOf(UserAccountRejectedException.class)
            .hasMessageContaining("12");
        accounts.create("u-twelve", "열두글자인비밀번호입니다", Role.OFFICER, T0);
        assertThat(accounts.authenticate("u-twelve", "열두글자인비밀번호입니다", T0).outcome())
            .isEqualTo(LoginAttempt.Outcome.SUCCEEDED);
        accounts.create("u-72", "a".repeat(72), Role.OFFICER, T0);
        assertThatThrownBy(() -> accounts.create("u-73", "a".repeat(73), Role.OFFICER, T0))
            .isInstanceOf(UserAccountRejectedException.class)
            .hasMessageContaining("72");
        // 한글 25자는 75바이트다.
        assertThatThrownBy(() -> accounts.create("u-ko", "가".repeat(25), Role.OFFICER, T0))
            .isInstanceOf(UserAccountRejectedException.class);
        assertThatThrownBy(() -> accounts.create("u-nul", "비밀번호에-널-글자\u0000-들어감", Role.OFFICER, T0))
            .isInstanceOf(UserAccountRejectedException.class);
        // 거부한 문구에 비밀번호를 담지 않는다.
        assertThatThrownBy(() -> accounts.create("u-leak", "짧은비밀", Role.OFFICER, T0))
            .isInstanceOf(UserAccountRejectedException.class)
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("짧은비밀"));
        assertThat(accounts.find("u-short")).isEmpty();
        assertThatThrownBy(() -> accounts.create(null, PASSWORD, Role.OFFICER, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> accounts.create("u-null", null, Role.OFFICER, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> accounts.create("u-null", PASSWORD, null, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> accounts.create("u-null", PASSWORD, Role.OFFICER, null))
            .isInstanceOf(NullPointerException.class);
    }

    // ── 여러 번 틀렸을 때 ───────────────────────────────────────────

    @Test
    void 잇달아_다섯_번_틀리면_막히고_막힌_동안에는_맞는_비밀번호도_받지_않는다() {
        accounts.create("u-lock", PASSWORD, Role.OFFICER, T0);

        for (int i = 1; i <= 4; i++) {
            assertThat(accounts.authenticate("u-lock", WRONG, T0.plusSeconds(i)).outcome())
                .isEqualTo(LoginAttempt.Outcome.FAILED);
        }
        // 다섯 번째 틀린 순간에 막힌다.
        Instant fifth = T0.plusSeconds(5);
        assertThat(accounts.authenticate("u-lock", WRONG, fifth).outcome()).isEqualTo(LoginAttempt.Outcome.FAILED);

        LoginAttempt locked = accounts.authenticate("u-lock", PASSWORD, fifth.plusSeconds(1));
        assertThat(locked.outcome()).isEqualTo(LoginAttempt.Outcome.LOCKED);
        assertThat(locked.account()).isNull();
        assertThat(locked.lockedUntil()).isEqualTo(fifth.plus(Duration.ofMinutes(15)));
        // 막힌 동안의 시도는 막힌 시간을 늘리지 않는다.
        assertThat(accounts.authenticate("u-lock", WRONG, fifth.plus(Duration.ofMinutes(14))).lockedUntil())
            .isEqualTo(fifth.plus(Duration.ofMinutes(15)));
        // 끝나는 때 바로 직전까지 막혀 있고, 그때부터 풀린다.
        assertThat(accounts.authenticate("u-lock", PASSWORD, fifth.plus(Duration.ofMinutes(15)).minusMillis(1)).outcome())
            .isEqualTo(LoginAttempt.Outcome.LOCKED);
        assertThat(accounts.authenticate("u-lock", PASSWORD, fifth.plus(Duration.ofMinutes(15))).outcome())
            .isEqualTo(LoginAttempt.Outcome.SUCCEEDED);
    }

    @Test
    void 로그인에_성공하면_틀린_횟수가_처음으로_돌아가고_풀린_뒤에는_다시_다섯_번을_센다() {
        accounts.create("u-reset", PASSWORD, Role.OFFICER, T0);
        for (int i = 0; i < 4; i++) {
            accounts.authenticate("u-reset", WRONG, T0);
        }
        assertThat(accounts.authenticate("u-reset", PASSWORD, T0).outcome()).isEqualTo(LoginAttempt.Outcome.SUCCEEDED);
        // 앞의 네 번은 잊는다. 다시 네 번 틀려도 막히지 않는다.
        for (int i = 0; i < 4; i++) {
            assertThat(accounts.authenticate("u-reset", WRONG, T0).outcome()).isEqualTo(LoginAttempt.Outcome.FAILED);
        }
        assertThat(accounts.authenticate("u-reset", PASSWORD, T0).outcome()).isEqualTo(LoginAttempt.Outcome.SUCCEEDED);

        // 막혔다가 풀린 뒤에도 처음부터 센다: 네 번 틀려도 막히지 않고 다섯 번째에 다시 막힌다.
        for (int i = 0; i < 5; i++) {
            accounts.authenticate("u-reset", WRONG, T0);
        }
        Instant after = T0.plus(Duration.ofMinutes(15));
        for (int i = 0; i < 4; i++) {
            assertThat(accounts.authenticate("u-reset", WRONG, after).outcome()).isEqualTo(LoginAttempt.Outcome.FAILED);
        }
        assertThat(accounts.authenticate("u-reset", PASSWORD, after).outcome()).isEqualTo(LoginAttempt.Outcome.SUCCEEDED);
    }

    @Test
    void 틀린_시도가_함께_몰려도_횟수를_빠뜨리지_않는다() throws Exception {
        accounts.create("u-race", PASSWORD, Role.OFFICER, T0);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<LoginAttempt.Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return accounts.authenticate("u-race", WRONG, T0).outcome();
                }));
            }
            go.countDown();
            for (Future<LoginAttempt.Outcome> future : futures) {
                assertThat(future.get()).isEqualTo(LoginAttempt.Outcome.FAILED);
            }
        }
        finally {
            pool.shutdownNow();
        }
        // 다섯 번이 모두 세어져 막혀 있다.
        assertThat(accounts.authenticate("u-race", PASSWORD, T0.plusSeconds(1)).outcome())
            .isEqualTo(LoginAttempt.Outcome.LOCKED);
    }

    @Test
    void 어떤_시도든_해시를_꼭_한_번_견준다_걸린_시간으로_계정이_있는지와_막혔는지를_알_수_없다() {
        java.util.concurrent.atomic.AtomicInteger compared = new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.security.crypto.password.PasswordEncoder real = org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
        org.springframework.security.crypto.password.PasswordEncoder counting = new org.springframework.security.crypto.password.PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                return real.encode(raw);
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                compared.incrementAndGet();
                return real.matches(raw, encoded);
            }
        };
        UserAccounts counted = new UserAccounts(CommonTestDatabase.dataSource(), 2, Duration.ofMinutes(15), counting);
        counted.create("u-time", PASSWORD, Role.OFFICER, T0);
        String tooLong = "a".repeat(73);

        // 맞는 비밀번호, 틀린 비밀번호, 저장할 수 없는 비밀번호(너무 김, 널 글자), 없는 아이디, 규칙에 맞지 않는 아이디.
        String[][] attempts = { { "u-time", PASSWORD }, { "u-time", WRONG }, { "u-time", PASSWORD }, { "u-time", tooLong },
                { "u-time", PASSWORD }, { "u-time", "x\u0000y" }, { "u-time", PASSWORD }, { "u-time-none", PASSWORD },
                { "u-time-none", tooLong }, { "잘못된 아이디", PASSWORD }, { "잘못된 아이디", tooLong } };
        for (String[] attempt : attempts) {
            compared.set(0);
            counted.authenticate(attempt[0], attempt[1], T0);
            assertThat(compared.get()).as(attempt[0] + " / " + attempt[1].length() + "자").isEqualTo(1);
        }
        // 너무 긴 비밀번호는 앞 72바이트가 맞아도 맞는 것이 아니다.
        counted.create("u-time-72", "b".repeat(72), Role.OFFICER, T0);
        assertThat(counted.authenticate("u-time-72", "b".repeat(73), T0).outcome()).isEqualTo(LoginAttempt.Outcome.FAILED);
        // 막힌 계정도 같다.
        counted.authenticate("u-time", WRONG, T0);
        counted.authenticate("u-time", WRONG, T0);
        for (String password : new String[] { PASSWORD, WRONG, tooLong }) {
            compared.set(0);
            assertThat(counted.authenticate("u-time", password, T0.plusSeconds(1)).outcome())
                .isEqualTo(LoginAttempt.Outcome.LOCKED);
            assertThat(compared.get()).isEqualTo(1);
        }
    }

    @Test
    void 막는_횟수와_시간은_정한_값을_따르고_틀린_값은_받지_않는다() {
        UserAccounts strict = new UserAccounts(CommonTestDatabase.dataSource(), 2, Duration.ofSeconds(30));
        strict.create("u-config", PASSWORD, Role.OFFICER, T0);

        strict.authenticate("u-config", WRONG, T0);
        strict.authenticate("u-config", WRONG, T0);

        assertThat(strict.authenticate("u-config", PASSWORD, T0.plusSeconds(29)).outcome())
            .isEqualTo(LoginAttempt.Outcome.LOCKED);
        assertThat(strict.authenticate("u-config", PASSWORD, T0.plusSeconds(30)).outcome())
            .isEqualTo(LoginAttempt.Outcome.SUCCEEDED);
        assertThatThrownBy(() -> new UserAccounts(CommonTestDatabase.dataSource(), 0, Duration.ofMinutes(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserAccounts(CommonTestDatabase.dataSource(), 5, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserAccounts(null, 5, Duration.ofMinutes(1))).isInstanceOf(NullPointerException.class);
        // 로그인의 빈 값과 아주 긴 값은 예외가 아니라 실패다 (아무 글자나 들어온다).
        assertThat(strict.authenticate("", "", T0).outcome()).isEqualTo(LoginAttempt.Outcome.FAILED);
        assertThat(strict.authenticate("u-config-x", "가".repeat(5000), T0).outcome()).isEqualTo(LoginAttempt.Outcome.FAILED);
        assertThat(strict.authenticate("a\u0000b", "x\u0000y", T0).outcome()).isEqualTo(LoginAttempt.Outcome.FAILED);
        assertThatThrownBy(() -> strict.authenticate(null, PASSWORD, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> strict.authenticate("u-config", null, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> strict.authenticate("u-config", PASSWORD, null)).isInstanceOf(NullPointerException.class);
    }
}
