package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.altumlab.tabnit.link.IngestNotice;
import org.junit.jupiter.api.Test;

/**
 * D-09: 판의 알림을 사용자에게 보일 문구로 푼다 (D7 5절, D2 AD-326, AD-348).
 * 문구는 몇 번째 행의 무엇이 왜 걸렸는지와 무엇을 하면 되는지를 적는다. 모든 값은 지어낸 것이다.
 */
class EditionNoticesTest {

	@Test
	void 참조로만_만들어진_개체는_어느_행이_가리킨_어느_개체인지와_할_일을_적는다() {
		assertThat(message("REFERENCE_ONLY_ENTITY", 4, "NO_ATTRIBUTE_VALUES", "p|Other|O-9", null)).startsWith("4번째 행")
			.contains("p|Other|O-9")
			.contains("확인하세요")
			.doesNotContain("NO_ATTRIBUTE_VALUES");
	}

	@Test
	void 건너뛴_행은_몇_번째_행의_어느_열이_왜_걸렸는지와_할_일을_적는다() {
		assertThat(message("SKIPPED_ROW", 3, "BLANK_KEY", "code", null)).startsWith("3번째 행")
			.contains("code")
			.contains("비어 있")
			.endsWith("올리세요.");
		assertThat(message("SKIPPED_ROW", 7, "INVALID_VALUE", "count", "열둘")).startsWith("7번째 행")
			.contains("count")
			.contains("열둘")
			.endsWith("올리세요.");
		assertThat(message("SKIPPED_ROW", 4, "CELL_COUNT_MISMATCH", null, "2")).startsWith("4번째 행").contains("2");
		assertThat(message("SKIPPED_ROW", 5, "DUPLICATE_ITEM_KEY", null, "B-4")).startsWith("5번째 행").contains("B-4");
		assertThat(message("SKIPPED_ROW", 9, "INVALID_PERIOD", "to", "2026-03-01~2026-01-01")).contains("to")
			.contains("2026-03-01~2026-01-01");
		// 열 이름이 없는 알림도 문구가 된다.
		assertThat(message("SKIPPED_ROW", 2, "KEY_TOO_LONG", null, "250")).startsWith("2번째 행").contains("250");
		for (String code : new String[] { "VALUE_WITHOUT_KEY", "SELF_RELATION", "TEXT_AFTER_QUOTE", "INVALID_CHARACTER" }) {
			assertThat(message("SKIPPED_ROW", 6, code, "col", "값")).as(code).startsWith("6번째 행").doesNotContain(code);
		}
	}

	@Test
	void 넣지_않은_값과_고쳐_넣은_기간은_어느_개체의_무엇인지와_까닭을_적는다() {
		assertThat(message("ATTRIBUTE_CONFLICT", 3, "CONFLICTING_VALUES", "p|T|a 의 name", "값")).startsWith("3번째 행")
			.contains("p|T|a 의 name")
			.contains("값");
		assertThat(message("ATTRIBUTE_CONFLICT", 3, "OVERLAPS_STORED_VALUE", "p|T|a 의 name",
				"둘 (2026-03-06~). 이미 있는 값: 하나 (2026-03-01~, 입력 자료 one 의 1번째 판)")).startsWith("3번째 행")
			.contains("p|T|a 의 name")
			.contains("입력 자료 one 의 1번째 판");
		assertThat(message("RELATION_CONFLICT", 8, "CONFLICTING_PERIODS", "p|T|a → p|T|b", "2026-01-01~"))
			.startsWith("8번째 행")
			.contains("p|T|a → p|T|b");
		assertThat(message("PERIOD_ADJUSTED", 2, "STARTS_AT_EFFECTIVE_DATE", "p|T|a 의 name", "2026-01-01~"))
			.startsWith("2번째 행")
			.contains("적용일");
		assertThat(message("PERIOD_ADJUSTED", 2, "ALREADY_PAST", "p|T|a 의 name", "2026-01-01~2026-02-01"))
			.startsWith("2번째 행")
			.contains("2026-01-01~2026-02-01");
	}

	@Test
	void 문구를_따로_두지_않은_코드는_코드와_값을_그대로_넣은_문구로_낸다() {
		String unknown = message("SKIPPED_ROW", 11, "지어낸_새_코드", "열", "값");

		assertThat(unknown).startsWith("11번째 행").contains("지어낸_새_코드").contains("열").contains("값");
		// 대상과 값이 없어도 문구가 되고 null 이라는 글자가 나오지 않는다.
		assertThat(message("지어낸_종류", 1, "지어낸_새_코드", null, null)).startsWith("1번째 행")
			.contains("지어낸_새_코드")
			.doesNotContain("null");
		assertThat(message("SKIPPED_ROW", 3, "BLANK_KEY", null, null)).doesNotContain("null");
		// 대상이 없어도 말이 끊기지 않는다: 조사만 남거나 빈칸이 겹치지 않는다.
		String[][] codes = { { "SKIPPED_ROW", "BLANK_KEY" }, { "SKIPPED_ROW", "KEY_TOO_LONG" },
				{ "SKIPPED_ROW", "VALUE_WITHOUT_KEY" }, { "SKIPPED_ROW", "INVALID_VALUE" }, { "SKIPPED_ROW", "INVALID_PERIOD" },
				{ "SKIPPED_ROW", "SELF_RELATION" }, { "ATTRIBUTE_CONFLICT", "CONFLICTING_VALUES" },
				{ "RELATION_CONFLICT", "CONFLICTING_PERIODS" }, { "ATTRIBUTE_CONFLICT", "OVERLAPS_STORED_VALUE" },
				{ "PERIOD_ADJUSTED", "STARTS_AT_EFFECTIVE_DATE" }, { "PERIOD_ADJUSTED", "ALREADY_PAST" } };
		for (String[] code : codes) {
			for (String subject : new String[] { null, "", "대상" }) {
				String text = message(code[0], 3, code[1], subject, null);
				assertThat(text).as(code[1] + " / " + subject)
					.doesNotContain("null")
					.doesNotContain("  ")
					.doesNotContain("는이(가)")
					.doesNotContain(".이(가)")
					.doesNotContain("의 을(를)")
					.doesNotContain(" 을(를)")
					.doesNotContain("의을(를)");
			}
		}
	}

	private static String message(String kind, int rowNo, String code, String subject, String detail) {
		return EditionNotices.message(new IngestNotice(kind, rowNo, code, subject, detail));
	}
}
