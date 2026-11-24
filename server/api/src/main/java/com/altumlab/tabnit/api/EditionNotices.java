package com.altumlab.tabnit.api;

import com.altumlab.tabnit.link.IngestNotice;

/**
 * 판의 알림을 사용자에게 보일 문구로 푼다 (D7 5절, D2 AD-326, AD-348). link 는 종류와 코드와 값만 돌려준다.
 *
 * <p>문구는 몇 번째 행의 무엇이 왜 걸렸는지와 무엇을 하면 되는지를 적는다. 판은 이미 받아들여졌다: 알림은 그
 * 가운데 넣지 않았거나 다르게 넣은 것을 알린다. 문구를 따로 두지 않은 코드는 코드와 값을 그대로 넣은 문구로 낸다.
 * 화면이 가르는 데는 종류와 코드를 쓴다. 판을 통째로 거부할 때의 문구는 {@link EditionRejections} 에 있다.
 */
final class EditionNotices {

	private static final String FIX_ROW = " 그 행을 고친 판을 다시 올리세요.";

	private EditionNotices() {
	}

	static String message(IngestNotice notice) {
		String row = notice.rowNo() + "번째 행";
		String subject = notice.subject() == null || notice.subject().isEmpty() ? null : notice.subject();
		String detail = notice.detail() == null || notice.detail().isEmpty() ? null : notice.detail();
		// 열 이름이 없는 알림도 말이 되게 적는다.
		String column = subject == null ? " 열" : " " + subject + " 열";
		String value = detail == null ? "" : " (" + detail + ")";
		return switch (notice.code()) {
			// 건너뛴 행: 그 행은 넣지 않았다.
			case "BLANK_KEY" -> row + "을 건너뛰었습니다. 키가 되는" + column + "이(가) 비어 있습니다." + FIX_ROW;
			case "KEY_TOO_LONG" -> row + "을 건너뛰었습니다. 키가 되는" + column + "의 값이 너무 깁니다" + value
					+ ". 키를 줄여" + FIX_ROW;
			case "DUPLICATE_ITEM_KEY" -> row + "을 건너뛰었습니다. 같은 키의 행이 파일에 둘 이상 있습니다" + value
					+ ". 어느 것이 맞는지 정해 하나만 남기고" + FIX_ROW;
			case "VALUE_WITHOUT_KEY" -> row + "을 건너뛰었습니다." + column + "이(가) 비어 있는데 그 개체의 값은 적혀 있습니다."
					+ " 키를 적거나 값을 지우고" + FIX_ROW;
			case "INVALID_VALUE" -> row + "을 건너뛰었습니다." + column + "의 값을 읽을 수 없습니다" + value
					+ ". 그 열의 형식에 맞게 고치고" + FIX_ROW;
			case "INVALID_PERIOD" -> row + "을 건너뛰었습니다." + column + "의 종료일이 시작일보다 뒤가 아닙니다" + value
					+ ". 날짜를 고치고" + FIX_ROW;
			case "SELF_RELATION" -> row + "을 건너뛰었습니다." + column + "이(가) 자기 자신을 가리킵니다" + value
					+ ". 가리키는 대상을 고치고" + FIX_ROW;
			case "CELL_COUNT_MISMATCH" -> row + "을 건너뛰었습니다. 칸의 수가 열 이름의 수와 다릅니다" + value
					+ ". 쉼표와 큰따옴표를 확인하고" + FIX_ROW;
			case "TEXT_AFTER_QUOTE" -> row + "을 건너뛰었습니다. 닫는 큰따옴표 뒤에 글자가 이어집니다. 큰따옴표를 확인하고"
					+ FIX_ROW;
			case "FORMULA_WITHOUT_RESULT" -> row + "을 건너뛰었습니다." + column + "의 칸이 수식인데 파일에 계산 결과가 저장되어"
					+ " 있지 않습니다. 엑셀에서 파일을 열어 다시 저장하거나 값을 직접 적고" + FIX_ROW;
			case "CELL_ERROR" -> row + "을 건너뛰었습니다." + column + "의 칸이 오류 값입니다" + value
					+ ". 그 칸의 수식이나 값을 고치고" + FIX_ROW;
			case "INVALID_CHARACTER" -> row + "을 건너뛰었습니다. 쓸 수 없는 글자가 들어 있습니다. 그 글자를 빼고" + FIX_ROW;
			// 넣지 않은 값: 행의 나머지는 넣었다.
			case "CONFLICTING_VALUES" -> row + "의" + target(subject) + " 값을 넣지 않았습니다. 이 판의 다른 행이 같은 것을 다른 값이나"
					+ " 겹치는 다른 기간으로 적었습니다" + value + ". 어느 것이 맞는지 정해 고친 판을 다시 올리세요.";
			case "CONFLICTING_PERIODS" -> row + "의" + target(subject) + " 관계를 넣지 않았습니다. 이 판의 다른 행이 같은 관계를"
					+ " 겹치는 다른 기간으로 적었습니다" + value + ". 기간을 정해 고친 판을 다시 올리세요.";
			case "OVERLAPS_STORED_VALUE" -> row + "의" + target(subject) + " 값을 넣지 않았습니다. 이미 있는 값과 기간이 겹칩니다"
					+ value + ". 기간을 고치거나, 이미 있는 값을 넣은 입력 자료의 새 판으로 그 값을 끝낸 뒤 다시 올리세요.";
			// 다르게 넣은 기간.
			case "STARTS_AT_EFFECTIVE_DATE" -> row + "의" + named(subject) + "을(를) 적힌 시작일이 아니라 이 판의 적용일부터 넣었습니다"
					+ value + ". 지난 날짜로는 고쳐 넣지 않습니다. 뜻한 것이 맞는지 확인하세요.";
			case "ALREADY_PAST" -> row + "의" + named(subject) + "을(를) 넣지 않았습니다. 적힌 기간이 이 판의 적용일보다 앞에서 끝납니다"
					+ value + ". 지난 기간은 고쳐 넣지 않습니다. 그 행이 더 필요 없으면 파일에서 빼세요.";
			default -> row + "에 알림이 있습니다. 이유의 코드: " + notice.code() + (subject == null ? "" : ", 대상: " + subject)
					+ (detail == null ? "" : ", 값: " + detail) + ". 그 행을 확인하세요.";
		};
	}

	/** 어느 개체의 무엇인지를 그것만으로 가리킬 때. 없으면 "값"이라고 적는다. */
	private static String named(String subject) {
		return subject == null ? " 값" : " " + subject;
	}

	/** 어느 개체의 무엇인지. 없으면 적지 않는다. */
	private static String target(String subject) {
		return subject == null ? "" : " " + subject;
	}
}
