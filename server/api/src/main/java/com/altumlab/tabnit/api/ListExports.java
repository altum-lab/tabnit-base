package com.altumlab.tabnit.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * 목록을 파일로 내보내는 일의 공통 부분 (D7 "목록 내보내기", D2 AD-412).
 *
 * <p>무엇을 어떤 순서로 담는지는 목록의 API 와 같다: 여기서 정하지 않는다. 여기서는 형식을 읽고, 행의 한도를 보고,
 * CSV 로 적고, 파일로 답하는 머리를 붙인다. 같은 목록에서는 글자까지 같은 파일이 나온다: 파일에 받은 시각을 넣지 않는다.
 */
@Component
class ListExports {

	/** 한도의 기본값. 재어 보고 잡은 값이 아니다 (D9 의 D-21). */
	static final int DEFAULT_MAX_ROWS = 100_000;

	private static final MediaType CSV_TYPE = new MediaType("text", "csv", StandardCharsets.UTF_8);

	/** 엑셀이 UTF-8 로 읽게 하는 표지 (BOM). */
	private static final String BOM = "﻿";

	enum Format {

		CSV("csv"), JSON("json");

		private final String extension;

		Format(String extension) {
			this.extension = extension;
		}

	}

	/** 한 번에 내보내는 행의 한도. */
	private final int maxRows;

	ListExports(@Value("${tabnit.exports.max-rows:" + DEFAULT_MAX_ROWS + "}") int maxRows) {
		if (maxRows < 1) {
			throw new IllegalStateException("tabnit.exports.max-rows 는 1 이상이어야 합니다: " + maxRows
					+ ". 한 번에 내보낼 행의 한도를 정해 다시 실행하세요.");
		}
		this.maxRows = maxRows;
	}

	/**
	 * 요청의 형식을 읽는다. csv 와 json 만 받는다 (소문자 그대로). 없으면 틀린 값이다: 조용히 하나로 정하지 않는다.
	 *
	 * @throws ApiProblemException 없거나 다른 값일 때 (400)
	 */
	static Format format(String value) {
		if ("csv".equals(value)) {
			return Format.CSV;
		}
		if ("json".equals(value)) {
			return Format.JSON;
		}
		throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_FORMAT", "format",
				"파일의 형식이 없거나 틀렸습니다. format 에 csv 또는 json 을 넣어 다시 보내세요.");
	}

	/** 목록 전체를 한 번에 읽을 때 넘기는 값: 처음부터, 한도보다 하나 더. 하나 더 읽어야 넘었는지를 안다. */
	PageRequest whole() {
		return new PageRequest(0, this.maxRows == Integer.MAX_VALUE ? this.maxRows : this.maxRows + 1);
	}

	/** 답의 limit 에 적는 값: 한도다. */
	PageRequest answered() {
		return new PageRequest(0, this.maxRows);
	}

	/**
	 * 전체 수가 한도를 넘으면 거부한다. 일부만 담은 파일을 주지 않는다: 파일의 행 수가 전체 수와 달라진다.
	 *
	 * @throws ApiProblemException 넘었을 때 (422)
	 */
	void requireWithin(int total) {
		if (total > this.maxRows) {
			throw new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, "EXPORT_TOO_LARGE", null,
					"내보낼 행이 " + total + "건이라 한 번에 내보내는 한도 " + this.maxRows
							+ "건을 넘습니다. 조건을 좁혀 다시 받거나, 관리자에게 한도(tabnit.exports.max-rows)를 올려 달라고 하세요.");
		}
	}

	/** JSON 파일로 답한다. 본문은 목록의 API 와 같은 모양의 값이다. */
	static ResponseEntity<Object> json(String name, Object body) {
		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, attachment(name, Format.JSON))
			.contentType(MediaType.APPLICATION_JSON).body(body);
	}

	/** CSV 파일로 답한다. */
	static ResponseEntity<Object> csv(String name, List<String> header, List<List<String>> rows) {
		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, attachment(name, Format.CSV))
			.contentType(CSV_TYPE).body(csv(header, rows));
	}

	/** 파일 이름은 영어 글자, 숫자, 붙임표만 쓴다 (부르는 쪽이 그렇게 넘긴다). */
	private static String attachment(String name, Format format) {
		return "attachment; filename=\"" + name + "." + format.extension + "\"";
	}

	/**
	 * CSV 로 적는다 (RFC 4180). UTF-8 이고 맨 앞에 BOM 을 둔다. 칸은 쉼표로, 줄은 CRLF 로 나눈다. 쉼표, 따옴표, 줄바꿈이
	 * 든 칸은 따옴표로 싸고 따옴표는 둘로 적는다. 없는 값(null)은 빈 칸이다.
	 *
	 * <p>표 계산 프로그램이 수식으로 읽는 글자(=, +, -, @, 탭, CR)로 시작하는 칸은 앞에 작은따옴표를 붙인다: 파일을 연
	 * 사람의 PC 에서 값이 수식으로 실행되지 않게 한다. 그래서 그런 칸은 저장된 값과 한 글자 다르다 (JSON 에는 붙이지 않는다).
	 *
	 * @throws IllegalArgumentException 칸의 수가 머리 행과 다른 행이 있을 때
	 */
	static byte[] csv(List<String> header, List<List<String>> rows) {
		StringBuilder text = new StringBuilder(BOM);
		line(text, header, false);
		int rowNo = 0;
		for (List<String> row : rows) {
			rowNo++;
			if (row.size() != header.size()) {
				throw new IllegalArgumentException("내보낼 " + rowNo + "번째 행의 칸이 " + row.size() + "개입니다. 머리 행은 "
						+ header.size() + "개입니다.");
			}
			line(text, row, true);
		}
		return text.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void line(StringBuilder text, List<String> cells, boolean guard) {
		for (int i = 0; i < cells.size(); i++) {
			if (i > 0) {
				text.append(',');
			}
			String cell = cells.get(i) == null ? "" : cells.get(i);
			if (guard && !cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) {
				cell = "'" + cell;
			}
			if (cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0) {
				text.append('"').append(cell.replace("\"", "\"\"")).append('"');
			}
			else {
				text.append(cell);
			}
		}
		text.append("\r\n");
	}
}
