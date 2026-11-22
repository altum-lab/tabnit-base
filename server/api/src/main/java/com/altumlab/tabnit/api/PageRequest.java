package com.altumlab.tabnit.api;

import org.springframework.http.HttpStatus;

/**
 * 목록을 나눠 달라는 요청의 값 (D7 2절 "나눠 주기", D2 AD-347).
 *
 * <p>틀린 값은 조용히 고쳐 받지 않고 어느 항목이 틀렸는지 알린다. 나누는 일은 순서를 정하는 모듈이 한다.
 * 여기서는 값을 읽고 확인해 넘기기만 한다.
 *
 * @param offset 건너뛸 수. 0 이상
 * @param limit 한 번에 받을 수. 1 부터 {@value #MAX_LIMIT} 까지
 */
record PageRequest(int offset, int limit) {

	/** 요청에 limit 이 없을 때의 값. */
	static final int DEFAULT_LIMIT = 50;

	/** 한 번에 받을 수 있는 가장 큰 수. */
	static final int MAX_LIMIT = 200;

	/**
	 * 요청의 글자에서 읽는다. 항목이 없으면 (null) 기본값이다. 항목이 있는데 비어 있으면 틀린 값이다.
	 *
	 * @throws ApiProblemException 수가 아니거나 범위 밖일 때 (400)
	 */
	static PageRequest of(String offset, String limit) {
		int skip = 0;
		if (offset != null) {
			skip = parse(offset, "INVALID_OFFSET", "offset",
					"건너뛸 수(offset)의 값이 틀렸습니다. 0 이상의 정수를 넣어 다시 보내세요.");
			if (skip < 0) {
				throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_OFFSET", "offset",
						"건너뛸 수(offset)의 값이 틀렸습니다. 0 이상의 정수를 넣어 다시 보내세요.");
			}
		}
		int size = DEFAULT_LIMIT;
		if (limit != null) {
			String message = "한 번에 받을 수(limit)의 값이 틀렸습니다. 1 부터 " + MAX_LIMIT + " 까지의 정수를 넣어 다시 보내세요.";
			size = parse(limit, "INVALID_LIMIT", "limit", message);
			if (size < 1 || size > MAX_LIMIT) {
				throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "limit", message);
			}
		}
		return new PageRequest(skip, size);
	}

	/**
	 * 요청의 글자를 수로 읽는다. 영어 숫자(0 ~ 9)만으로 된 열 자 이하의 글자를 받는다. 그 앞에 빼기표가 하나 붙은
	 * 것은 음수로 읽어 범위 밖이라고 알린다 (-0 도 받지 않는다).
	 * 더하기표, 빈칸, 소수점, 다른 글자의 숫자(전각, 다른 문자의 숫자)는 수가 아니다. 음수와 담을 수 없이 큰 수는
	 * 범위 밖의 값으로 돌려줘 부르는 쪽이 같은 문구로 알리게 한다.
	 */
	private static int parse(String value, String code, String field, String message) {
		boolean negative = value.startsWith("-");
		String digits = negative ? value.substring(1) : value;
		if (digits.isEmpty() || digits.length() > 10 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, code, field, message);
		}
		if (negative) {
			// 빼기표가 붙은 값은 0 이어도 받지 않는다 (-0). 범위 밖의 값으로 알린다.
			return -1;
		}
		long parsed = Long.parseLong(digits);
		if (parsed > Integer.MAX_VALUE) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, code, field, message);
		}
		return (int) parsed;
	}
}
