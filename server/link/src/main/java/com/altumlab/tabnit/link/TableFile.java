package com.altumlab.tabnit.link;

import java.util.List;

/**
 * 읽은 표 형식 파일 (D6 7절 "입력 파일").
 *
 * @param columns 열 이름. 파일에 적힌 차례다. 비어 있지 않고 겹치지 않는다
 * @param rows 읽은 행. 파일에 적힌 차례다. 정렬하지 않는다
 * @param skipped 건너뛴 행. 파일에 적힌 차례다
 */
record TableFile(List<String> columns, List<TableRow> rows, List<SkippedRow> skipped) {

    TableFile {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        skipped = List.copyOf(skipped);
    }

    boolean hasColumn(String column) {
        return columns.contains(column);
    }

    /** 그 행에서 그 열의 값을 돌려준다. 빈 칸이면 빈 글자다. */
    String value(TableRow row, String column) {
        int index = columns.indexOf(column);
        if (index < 0) {
            throw new IllegalArgumentException("파일에 그런 열이 없습니다: " + column + ". 있는 열: " + columns);
        }
        return row.values().get(index);
    }
}
