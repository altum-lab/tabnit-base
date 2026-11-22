package com.altumlab.tabnit.api;

import com.altumlab.tabnit.common.AuditEntry;
import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditOutcome;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

/**
 * 관리자가 서버에서 실행하는 계정 관리 명령이 함께 쓰는 것 (D7 9절, D2 AD-268, AD-320, AD-329, AD-346).
 *
 * <p>명령은 서버를 HTTP 없이 띄워 일 하나를 하고 끝낸다. 명령은 감사 기록에 남는다: 하기 전에 시작의 줄을 남기고
 * (남기지 못하면 하지 않는다), 끝나면 결과의 줄을 남긴다. 로그인한 사람이 없으므로 누가는 비워 두고, 명령으로
 * 했다고 적는다. 누가 서버에 들어와 실행했는지는 운영체제의 기록이 맡는다.
 */
final class AccountCommands {

	static final String CREATE_USER_PROPERTY = "tabnit.create-user.login-id";

	static final String UNLOCK_USER_PROPERTY = "tabnit.unlock-user.login-id";

	static final String CHANGE_PASSWORD_PROPERTY = "tabnit.change-password.login-id";

	/** 새 비밀번호를 받는 환경 변수. 만들 때와 바꿀 때가 같다. */
	static final String PASSWORD_VARIABLE = "TABNIT_NEW_USER_PASSWORD";

	/** 감사 기록의 그 밖의 내용에 적는 표시: 서버에서 명령으로 했다. */
	static final String VIA_COMMAND = "via=command";

	private static final List<String> PROPERTIES = List.of(CREATE_USER_PROPERTY, UNLOCK_USER_PROPERTY,
			CHANGE_PASSWORD_PROPERTY);

	private AccountCommands() {
	}

	/** 실행 인자가 계정 관리 명령인가. 그러면 서버를 HTTP 없이 띄웠다가 끝낸다. */
	static boolean requested(String[] args) {
		for (String arg : args) {
			for (String property : PROPERTIES) {
				if (arg.startsWith("--" + property + "=")) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 일을 감사 기록과 함께 한다. 실패하면 실패의 줄을 남기고 그 까닭을 그대로 던진다.
	 *
	 * @param action 무엇을 했는가. 기능의 이름
	 * @param loginId 대상 계정의 아이디
	 * @param detail 그 밖의 내용. 비밀번호를 담지 않는다. 없으면 null
	 * @throws com.altumlab.tabnit.common.AuditLogException 시작의 줄을 남기지 못했을 때. 일은 하지 않는다
	 */
	static <T> T audited(AuditLog audit, Clock clock, String action, String loginId, String detail, Supplier<T> work) {
		String target = "loginId=" + loginId;
		String note = detail == null ? VIA_COMMAND : VIA_COMMAND + "; " + detail;
		long started = audit
			.record(new AuditEntry(clock.instant(), null, action, target, null, AuditOutcome.STARTED, null, note, null));
		T result;
		try {
			result = work.get();
		}
		catch (RuntimeException e) {
			try {
				audit.record(new AuditEntry(clock.instant(), null, action, target, null, AuditOutcome.FAILED, null, note,
						started));
			}
			catch (RuntimeException recordFailure) {
				e.addSuppressed(recordFailure);
			}
			throw e;
		}
		// 일은 이미 됐다. 결과의 줄을 남기지 못하면 그 사실을 알린다: 시작의 줄만 남아 있다.
		audit.record(new AuditEntry(clock.instant(), null, action, target, null, AuditOutcome.SUCCESS, null, note,
				started));
		return result;
	}
}
