package com.altumlab.tabnit.link;

/**
 * 값을 읽지 못한 칸 (D2 AD-375). 엑셀에만 있다.
 *
 * @param code 이유의 코드. `FORMULA_WITHOUT_RESULT` (수식 칸에 저장된 계산 결과가 없음), `CELL_ERROR` (칸이 오류 값임)
 * @param detail 이유에 딸린 값. 오류 값이면 그 오류의 표기(#N/A)다. 없으면 null
 */
record UnreadableCell(String code, String detail) {
}
