package com.altumlab.tabnit.api;

/**
 * 거부한 이유의 코드를 사용자에게 보일 문구로 푼다 (D7 5절). link 는 코드와 값만 돌려준다.
 *
 * <p>여기 있는 것은 link 가 파일 전체를 거부할 때 내는 코드다. 행 하나를 건너뛰는 이유의 코드는 알림(notices)으로
 * 나가고 여기 오지 않는다. 문구를 따로 두지 않은 코드는 코드와 값을 그대로 넣은 문구로 낸다.
 * 어느 문구든 무엇을 하면 되는지로 끝난다.
 */
final class EditionRejections {

	private static final String FIX_FILE = " 파일을 고쳐 다시 올리세요.";

	private EditionRejections() {
	}

	static String message(String code, String detail) {
		String value = detail == null || detail.isEmpty() ? "" : " (" + detail + ")";
		return switch (code) {
			case "NOT_UTF8" -> "파일이 UTF-8 이 아닙니다. UTF-8 로 저장해 다시 올리세요 (엑셀에서는 \"CSV UTF-8\" 형식).";
			case "NO_HEADER" -> "파일에 열 이름의 줄이 없습니다. 첫 줄에 열 이름을 적은 뒤 다시 올리세요 (CSV 는 쉼표로 나눠 적습니다).";
			case "INVALID_HEADER" -> "열 이름의 줄에서 닫는 큰따옴표 뒤에 글자가 이어집니다. 열 이름을 고친 뒤 다시 올리세요.";
			case "BLANK_COLUMN_NAME" ->
				"이름이 비어 있는 열이 있습니다. 열 이름을 빠짐없이 적은 뒤 다시 올리세요 (엑셀은 표를 시트의 첫 열부터 적습니다).";
			case "INVALID_COLUMN_NAME" -> "열 이름에 쓸 수 없는 글자가 있습니다. 그 글자를 뺀 뒤 다시 올리세요.";
			case "DUPLICATE_COLUMN_NAME" -> "같은 이름의 열이 둘 이상 있습니다. 열 이름을 다르게 고친 뒤 다시 올리세요.";
			case "UNCLOSED_QUOTE" -> "연 큰따옴표가 닫히지 않은 채 파일이 끝났습니다. 큰따옴표를 확인해 고친 뒤 다시 올리세요.";
			case "MISSING_COLUMN" -> "파일에 있어야 하는 열이 없습니다" + value + ". 파일의 열 이름을 확인해 고친 뒤 다시 올리세요.";
			case "EFFECTIVE_DATE_NOT_AFTER_PREVIOUS" ->
				"적용일이 바로 앞 판의 적용일보다 늦지 않습니다" + value + ". 앞 판보다 늦은 적용일로 다시 올리세요.";
			case "CANNOT_END" -> "앞 판이 넣은 것 가운데 이 적용일로 끝낼 수 없는 것이 있습니다" + value
					+ ". 그것의 시작일이나 적힌 종료일보다 늦은 적용일로 다시 올리세요.";
			case "NOT_XLSX" -> "이 입력 자료는 엑셀(xlsx) 파일로 받는데, 올린 파일을 엑셀로 읽을 수 없습니다. 옛 형식(xls), CSV,"
					+ " 암호가 걸린 파일은 받지 않습니다. 엑셀에서 \"Excel 통합 문서(.xlsx)\"로 암호 없이 저장해 다시 올리세요.";
			case "NOT_CSV" -> "이 입력 자료는 CSV 파일로 받는데 엑셀 파일을 올렸습니다. 엑셀에서 \"CSV UTF-8\" 형식으로 저장해 다시 올리세요.";
			case "XLSX_TOO_LARGE" -> "엑셀 파일의 내용이 너무 큽니다. 쓰지 않는 행과 열, 넓게 합친 칸을 지우거나 파일을 나눠 다시 올리세요.";
			case "SHEET_NOT_FOUND" -> "파일에 읽을 시트가 없습니다" + value
					+ ". 시트의 이름을 확인해 고친 뒤 다시 올리세요. 시트의 이름이 바뀌었으면 관리자에게 알려 주세요.";
			case "RECEIVED_BEFORE_PREVIOUS" ->
				"서버의 시각이 바로 앞 판을 받은 때보다 이릅니다. 서버의 시계에 문제가 있을 수 있으니 관리자에게 알려 주세요.";
			default -> "파일을 받아들이지 않았습니다. 이유의 코드: " + code + value + "." + FIX_FILE;
		};
	}
}
