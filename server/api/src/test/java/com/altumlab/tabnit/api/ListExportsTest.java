package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-21: 목록을 CSV 로 적는 법 (D7 "목록 내보내기", D2 AD-412). 저장소를 쓰지 않는 일반 시험이다.
 */
class ListExportsTest {

	private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	@Test
	void 머리_행과_자료_행을_쉼표와_CRLF_로_적고_맨_앞에_BOM_을_둔다() {
		byte[] file = ListExports.csv(List.of("가", "나"), List.of(List.of("1", "둘"), List.of("3", "넷")));

		assertThat(Arrays.copyOf(file, 3)).isEqualTo(BOM);
		assertThat(text(file)).isEqualTo("가,나\r\n1,둘\r\n3,넷\r\n");
	}

	@Test
	void 빈_목록은_머리_행만_있다() {
		assertThat(text(ListExports.csv(List.of("가", "나"), List.of()))).isEqualTo("가,나\r\n");
	}

	@Test
	void 쉼표와_따옴표와_줄바꿈이_든_값은_따옴표로_싸고_따옴표는_둘로_적는다() {
		byte[] file = ListExports.csv(List.of("값"),
				List.of(List.of("a,b"), List.of("그는 \"예\"라고 했다"), List.of("첫 줄\n둘째 줄"), List.of("끝\r"), List.of(" 빈칸 ")));

		assertThat(text(file))
			.isEqualTo("값\r\n\"a,b\"\r\n\"그는 \"\"예\"\"라고 했다\"\r\n\"첫 줄\n둘째 줄\"\r\n\"끝\r\"\r\n 빈칸 \r\n");
	}

	@Test
	void 없는_값은_빈_칸이다() {
		assertThat(text(ListExports.csv(List.of("가", "나", "다"), List.of(Arrays.asList("1", null, "3")))))
			.isEqualTo("가,나,다\r\n1,,3\r\n");
	}

	@Test
	void 수식으로_읽힐_글자로_시작하는_칸은_앞에_작은따옴표를_붙인다() {
		byte[] file = ListExports.csv(List.of("값"), List.of(List.of("=1+1"), List.of("+82"), List.of("-5"), List.of("@합계"),
				List.of("\t탭"), List.of("가=나"), List.of("5-3")));

		assertThat(text(file)).isEqualTo("값\r\n'=1+1\r\n'+82\r\n'-5\r\n'@합계\r\n'\t탭\r\n가=나\r\n5-3\r\n");
	}

	@Test
	void 앞의_빈칸과_줄바꿈을_건너뛴_첫_글자가_수식의_글자여도_막는다() {
		// 앞의 빈칸을 잘라 읽는 프로그램에서는 수식으로 읽힌다 (D2 AD-425).
		byte[] file = ListExports.csv(List.of("값"), List.of(List.of(" =1+1"), List.of("  @합계"), List.of("\u00A0-5"),
				List.of("\u3000+82"), List.of(" 가=나"), List.of("   ")));

		assertThat(text(file)).isEqualTo("값\r\n' =1+1\r\n'  @합계\r\n'\u00A0-5\r\n'\u3000+82\r\n 가=나\r\n   \r\n");
		// 줄바꿈 뒤의 것도 막는다 (그런 칸은 따옴표로도 싼다).
		assertThat(text(ListExports.csv(List.of("값"), List.of(List.of("\n=1+1"))))).isEqualTo("값\r\n\"'\n=1+1\"\r\n");
	}

	@Test
	void 전각의_수식_글자로_시작하는_칸도_막는다() {
		// 한글 입력기의 전각 글자다. 표 계산 프로그램에 따라 반각으로 바꿔 수식으로 읽는다.
		byte[] file = ListExports.csv(List.of("값"), List.of(List.of("\uFF1D1+1"), List.of("\uFF0B82"), List.of("\uFF0D5"),
				List.of("\uFF20합계"), List.of(" \uFF1D1"), List.of("가\uFF1D나")));

		assertThat(text(file)).isEqualTo(
				"값\r\n'\uFF1D1+1\r\n'\uFF0B82\r\n'\uFF0D5\r\n'\uFF20합계\r\n' \uFF1D1\r\n가\uFF1D나\r\n");
	}

	@Test
	void 수식을_막는_글자와_따옴표가_함께_필요한_값() {
		assertThat(text(ListExports.csv(List.of("값"), List.of(List.of("=A1,\"B\""))))).isEqualTo("값\r\n\"'=A1,\"\"B\"\"\"\r\n");
	}

	@Test
	void 같은_입력이면_글자까지_같다() {
		List<List<String>> rows = List.of(List.of("1", "가"), List.of("2", "나"));

		assertThat(ListExports.csv(List.of("번호", "이름"), rows)).isEqualTo(ListExports.csv(List.of("번호", "이름"), rows));
	}

	@Test
	void 칸의_수가_머리와_다른_행은_거부한다() {
		assertThatThrownBy(() -> ListExports.csv(List.of("가", "나"), List.of(List.of("1"))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 형식은_csv_와_json_만_받는다() {
		assertThat(ListExports.format("csv")).isEqualTo(ListExports.Format.CSV);
		assertThat(ListExports.format("json")).isEqualTo(ListExports.Format.JSON);
		for (String bad : Arrays.asList(null, "", "CSV", "xlsx", "csv ")) {
			assertThatThrownBy(() -> ListExports.format(bad)).as(String.valueOf(bad)).isInstanceOf(ApiProblemException.class);
		}
	}

	private static String text(byte[] file) {
		return new String(file, 3, file.length - 3, StandardCharsets.UTF_8);
	}
}
