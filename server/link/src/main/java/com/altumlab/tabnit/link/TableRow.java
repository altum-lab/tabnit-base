package com.altumlab.tabnit.link;

import java.util.List;

/**
 * 표 형식 파일의 행 하나.
 *
 * @param rowNo 행 번호. 파일에서 이 행이 시작하는 줄의 번호다. 열 이름의 줄이 1 이다 (D2 AD-171)
 * @param values 칸의 값. 열 이름과 같은 차례이고 같은 수다. 앞뒤 빈칸을 뗀 값이고, 빈 칸은 빈 글자다 (D2 AD-167)
 */
record TableRow(int rowNo, List<String> values) {

    TableRow {
        values = List.copyOf(values);
    }
}
