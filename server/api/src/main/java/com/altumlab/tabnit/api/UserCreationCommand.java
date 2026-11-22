package com.altumlab.tabnit.api;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.Role;
import com.altumlab.tabnit.common.UserAccount;
import com.altumlab.tabnit.common.UserAccounts;
import java.time.Clock;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 사용자를 만드는 명령 (D2 AD-268, D7 9절). 사용자를 만드는 화면이 생기기 전까지 쓴다.
 *
 * <p>서버를 {@code --tabnit.create-user.login-id=아이디 --tabnit.create-user.role=역할} 로 띄우면 계정 하나를 만들고 끝난다.
 * 비밀번호는 환경 변수 {@value #PASSWORD_VARIABLE} 로만 받는다: 실행 인자와 설정 파일은 기록에 남는다.
 * 비밀번호를 로그에 적지 않는다.
 */
class UserCreationCommand implements ApplicationRunner {

	static final String LOGIN_ID_PROPERTY = AccountCommands.CREATE_USER_PROPERTY;

	static final String PASSWORD_VARIABLE = AccountCommands.PASSWORD_VARIABLE;

	private static final Logger log = LoggerFactory.getLogger(UserCreationCommand.class);

	private final UserAccounts accounts;

	private final AuditLog audit;

	private final Clock clock;

	private final String loginId;

	private final String role;

	private final UnaryOperator<String> environment;

	/**
	 * @param role 역할의 이름. ADMIN 또는 OFFICER
	 * @param environment 환경 변수를 읽는 곳
	 */
	UserCreationCommand(UserAccounts accounts, AuditLog audit, Clock clock, String loginId, String role,
			UnaryOperator<String> environment) {
		this.accounts = accounts;
		this.audit = audit;
		this.clock = clock;
		this.loginId = loginId;
		this.role = role;
		this.environment = environment;
	}

	/** 실행 인자가 사용자를 만들라는 것인가. 계정 관리 명령 모두를 가리는 것은 {@link AccountCommands#requested} 다. */
	static boolean requested(String[] args) {
		for (String arg : args) {
			if (arg.startsWith("--" + LOGIN_ID_PROPERTY + "=")) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void run(ApplicationArguments args) {
		Role parsed;
		if ("ADMIN".equals(this.role)) {
			parsed = Role.ADMIN;
		}
		else if ("OFFICER".equals(this.role)) {
			parsed = Role.OFFICER;
		}
		else {
			throw new IllegalStateException("사용자를 만들지 못했습니다. 역할은 ADMIN (관리자) 또는 OFFICER (담당자)입니다."
					+ " --tabnit.create-user.role 에 둘 가운데 하나를 넣어 다시 실행하세요.");
		}
		String password = this.environment.apply(PASSWORD_VARIABLE);
		if (password == null || password.isEmpty()) {
			throw new IllegalStateException("사용자를 만들지 못했습니다. 비밀번호가 없습니다. 환경 변수 " + PASSWORD_VARIABLE
					+ " 에 비밀번호를 넣어 다시 실행하세요. 실행 인자나 설정 파일에는 적지 마세요.");
		}
		// 명령은 감사 기록에 남는다 (D2 AD-329). 역할을 함께 적는다. 비밀번호는 적지 않는다.
		UserAccount created = AccountCommands.audited(this.audit, this.clock, "createUser", this.loginId,
				"role=" + parsed.name(), () -> this.accounts.create(this.loginId, password, parsed, this.clock.instant()));
		log.info("사용자를 만들었습니다: 아이디 {}, 역할 {}", created.loginId(), created.role());
	}

}
