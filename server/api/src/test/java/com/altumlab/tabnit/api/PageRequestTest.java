package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * D-13: 나눠 달라는 요청의 값을 읽는 규칙 (D7 2절 "나눠 주기", D2 AD-347).
 * 틀린 값은 조용히 고쳐 받지 않는다.
 */
class PageRequestTest {

	@Test
	void 항목이_없으면_기본값이고_영어_숫자로_적은_값을_받는다() {
		assertThat(PageRequest.of(null, null)).isEqualTo(new PageRequest(0, 50));
		assertThat(PageRequest.of("0", "1")).isEqualTo(new PageRequest(0, 1));
		assertThat(PageRequest.of("2147483647", "200")).isEqualTo(new PageRequest(2147483647, 200));
		// 앞에 0 을 붙인 것은 그 수다. 열 자까지 읽는다.
		assertThat(PageRequest.of("007", "050")).isEqualTo(new PageRequest(7, 50));
		assertThat(PageRequest.of("0000000001", "0000000200")).isEqualTo(new PageRequest(1, 200));
	}

	@Test
	void 수가_아니거나_범위_밖인_값은_어느_항목인지_알리며_거부한다() {
		String[] notNumbers = { "", " ", " 5", "5 ", "+5", "1.5", "1e2", "abc", "많이", "５", "٣", "0x10", "1,000", "--1",
				"-", "00000000001", "99999999999999999999" };
		for (String wrong : notNumbers) {
			expect(() -> PageRequest.of(wrong, null), "INVALID_OFFSET", "offset");
			expect(() -> PageRequest.of(null, wrong), "INVALID_LIMIT", "limit");
		}
		// 빼기표가 붙은 것은 0 이어도 받지 않는다.
		for (String outOfRange : new String[] { "-1", "-0", "-000", "-2147483648", "2147483648", "9999999999" }) {
			expect(() -> PageRequest.of(outOfRange, null), "INVALID_OFFSET", "offset");
		}
		for (String outOfRange : new String[] { "0", "-0", "-1", "201", "2147483647", "2147483648" }) {
			expect(() -> PageRequest.of(null, outOfRange), "INVALID_LIMIT", "limit");
		}
		// 둘 다 틀리면 건너뛸 수부터 알린다.
		expect(() -> PageRequest.of("x", "y"), "INVALID_OFFSET", "offset");
	}

	private static void expect(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code, String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiProblemException.class, problem -> {
			assertThat(problem.code()).isEqualTo(code);
			assertThat(problem.field()).isEqualTo(field);
		});
	}
}
