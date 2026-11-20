package com.altumlab.tabnit.link;

/**
 * 읽다가 건너뛴 행 하나. 문구가 아니라 이유의 코드와 값으로 남긴다 (D2 AD-153).
 *
 * @param rowNo 행 번호
 * @param code 이유의 코드. `CELL_COUNT_MISMATCH` (칸 수가 열 이름의 수와 다름), `TEXT_AFTER_QUOTE` (닫는 큰따옴표 뒤에
 *        글자가 이어짐), `INVALID_CHARACTER` (저장소가 받지 않는 글자가 듦)
 * @param detail 이유에 딸린 값. 칸 수가 다르면 그 행의 칸 수다. 없으면 null
 */
record SkippedRow(int rowNo, String code, String detail) {
}
