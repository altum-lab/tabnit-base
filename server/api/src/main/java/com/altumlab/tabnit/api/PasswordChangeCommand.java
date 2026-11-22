package com.altumlab.tabnit.api;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.UserAccounts;
import java.time.Clock;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 비밀번호를 바꾸는 명령 (D2 AD-320, AD-346, D7 9절).
 *
 * <p>서버를 {@code --tabnit.change-password.login-id=아이디} 로 띄우면 그 계정의 비밀번호를 바꾸고 끝난다.
 * 새 비밀번호는 환경 변수 {@value AccountCommands#PASSWORD_VARIABLE} 로만 받는다: 실행 인자와 설정 파일은 기록에 남는다.
 * 막힘도 함께 푼다. 이미 로그인된 세션은 끝내지 않는다 (세션은 떠 있는 서버에 있다). 비밀번호를 로그와 감사 기록에
 * 적지 않는다.
 */
class PasswordChangeCommand implements ApplicationRunner, AccountCommands.Command {

	private static final Logger log = LoggerFactory.getLogger(PasswordChangeCommand.class);

	private final UserAccounts accounts;

	private final AuditLog audit;

	private final Clock clock;

	private final String loginId;

	private final UnaryOperator<String> environment;

	/** @param environment 환경 변수를 읽는 곳 */
	PasswordChangeCommand(UserAccounts accounts, AuditLog audit, Clock clock, String loginId,
			UnaryOperator<String> environment) {
		this.accounts = accounts;
		this.audit = audit;
		this.clock = clock;
		this.loginId = loginId;
		this.environment = environment;
	}

	@Override
	public void run(ApplicationArguments args) {
		AccountCommands.audited(this.audit, this.clock, "changeUserPassword", this.loginId, null, () -> {
			String password = this.environment.apply(AccountCommands.PASSWORD_VARIABLE);
			if (password == null || password.isEmpty()) {
				throw new IllegalStateException("비밀번호를 바꾸지 못했습니다. 새 비밀번호가 없습니다. 환경 변수 "
						+ AccountCommands.PASSWORD_VARIABLE + " 에 새 비밀번호를 넣어 다시 실행하세요. 실행 인자나 설정 파일에는 적지 마세요.");
			}
			this.accounts.changePassword(this.loginId, password);
			return null;
		});
		log.info("비밀번호를 바꿨습니다: 아이디 {}. 막힘도 함께 풀었습니다. 이미 로그인된 세션은 끝날 때까지 유효합니다:"
				+ " 바로 끝내야 하면 서버를 다시 띄우세요.", this.loginId);
	}
}
