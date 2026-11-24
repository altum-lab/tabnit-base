package com.altumlab.tabnit.link;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 표 형식 파일의 행 하나.
 *
 * @param rowNo 행 번호. 파일에서 이 행이 시작하는 줄의 번호다. 열 이름의 줄이 1 이다 (D2 AD-171). 엑셀에서는 시트의 행 번호다
 * @param values 칸의 값. 열 이름과 같은 차례이고 같은 수다. 앞뒤 빈칸을 뗀 값이고, 빈 칸은 빈 글자다 (D2 AD-167)
 * @param typed 파일이 형식을 아는 칸의 자리 (0 부터). 엑셀의 수, 날짜, 참·거짓 칸이다. 이 칸의 값은 표준 표기로 적혀 있어
 *        매핑에 적힌 글자의 모양(날짜의 모양, 자릿수 구분 기호, 참과 거짓의 글자)을 따르지 않는다 (D2 AD-374).
 *        CSV 에는 없다
 * @param unreadable 값을 읽지 못한 칸. 칸의 자리(0 부터)에서 그 이유. 그 칸의 값은 빈 글자로 들어 있다. 매핑이 그 칸을
 *        읽으려 할 때 그 행을 건너뛴다 (D2 AD-375). 찾는 데만 쓴다. 순회하지 않는다. CSV 에는 없다
 */
record TableRow(int rowNo, List<String> values, Set<Integer> typed, Map<Integer, UnreadableCell> unreadable) {
    TableRow {
        values = List.copyOf(values);
        typed = Set.copyOf(typed);
        unreadable = Map.copyOf(unreadable);
    }

    TableRow(int rowNo, List<String> values) {
        this(rowNo, values, Set.of(), Map.of());
    }

    TableRow(int rowNo, List<String> values, Set<Integer> typed) {
        this(rowNo, values, typed, Map.of());
    }
}
