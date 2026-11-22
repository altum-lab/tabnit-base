package com.altumlab.tabnit.api;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.UserAccounts;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 로그인 막힘을 푸는 명령 (D2 AD-320, D7 9절).
 *
 * <p>서버를 {@code --tabnit.unlock-user.login-id=아이디} 로 띄우면 그 계정의 막힘을 풀고 끝난다. 틀린 횟수도 처음으로
 * 돌린다. 비밀번호는 바꾸지 않는다. 감사 기록에 남는다.
 */
class UserUnlockCommand implements ApplicationRunner, AccountCommands.Command {

	private static final Logger log = LoggerFactory.getLogger(UserUnlockCommand.class);

	private final UserAccounts accounts;

	private final AuditLog audit;

	private final Clock clock;

	private final String loginId;

	UserUnlockCommand(UserAccounts accounts, AuditLog audit, Clock clock, String loginId) {
		this.accounts = accounts;
		this.audit = audit;
		this.clock = clock;
		this.loginId = loginId;
	}

	@Override
	public void run(ApplicationArguments args) {
		AccountCommands.audited(this.audit, this.clock, "unlockUser", this.loginId, null, () -> {
			this.accounts.unlock(this.loginId);
			return null;
		});
		log.info("로그인 막힘을 풀었습니다: 아이디 {}", this.loginId);
	}
}
