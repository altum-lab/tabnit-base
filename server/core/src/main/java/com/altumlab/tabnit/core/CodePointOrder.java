package com.altumlab.tabnit.core;

import java.util.Comparator;
import java.util.List;

/**
 * 글자 코드 순의 비교 (D2 AD-38). 저장소의 정렬 규칙 "C" 와 같은 순서다.
 *
 * <p>{@link String#compareTo} 는 UTF-16 단위로 비교해, 기본 평면 밖의 글자에서 저장소와 순서가 달라진다.
 * 그래서 글자 코드(코드 포인트)로 비교한다.
 */
final class CodePointOrder {

    static final Comparator<String> STRINGS = CodePointOrder::compare;

    private CodePointOrder() {
    }

    static int compare(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int a = left.codePointAt(i);
            int b = right.codePointAt(j);
            if (a != b) {
                return Integer.compare(a, b);
            }
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    /** 두 목록을 앞에서부터 차례로 비교한다. 처음 다른 자리에서 앞선 쪽이 앞이다. 한쪽이 먼저 끝나면 짧은 쪽이 앞이다. */
    static int compareLists(List<String> left, List<String> right) {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int c = compare(left.get(i), right.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
