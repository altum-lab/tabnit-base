package com.altumlab.tabnit.api;

import com.altumlab.tabnit.pack.PackProblem;
import java.util.List;

/**
 * Pack 을 적재하지 않았다 (D7 10절). Pack 이 틀렸거나(문제의 목록), 이미 적재된 것과 맞지 않게 고쳤다(까닭의 목록).
 *
 * <p>문구에 서버의 폴더 경로를 담지 않는다.
 */
class PackLoadRejectedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final transient List<PackProblem> problems;

	private final transient List<String> reasons;

	private PackLoadRejectedException(String message, List<PackProblem> problems, List<String> reasons) {
		super(message);
		this.problems = problems;
		this.reasons = reasons;
	}

	/** Pack 이 형식에 맞지 않거나 정의가 서로 맞지 않는다. */
	static PackLoadRejectedException invalid(List<PackProblem> problems) {
		return new PackLoadRejectedException("Pack 을 적재하지 않았습니다. 문제 " + problems.size()
				+ "건을 서버의 Pack 폴더에서 고친 뒤 다시 적재하세요.", List.copyOf(problems), null);
	}

	/** 이미 적재된 Pack 을 받을 수 없는 방식으로 고쳤다. */
	static PackLoadRejectedException changeRejected(List<String> reasons) {
		return new PackLoadRejectedException("고친 Pack 을 적재하지 않았습니다. 이미 적재된 정의와 맞지 않는 곳 " + reasons.size()
				+ "건을 되돌린 뒤 다시 적재하세요. 저장된 정의는 그대로입니다.", null, List.copyOf(reasons));
	}

	/** Pack 의 문제. Pack 이 틀린 것이 아니면 null. */
	List<PackProblem> problems() {
		return this.problems;
	}

	/** 맞지 않는 곳. 고친 Pack 을 거부한 것이 아니면 null. */
	List<String> reasons() {
		return this.reasons;
	}

}
