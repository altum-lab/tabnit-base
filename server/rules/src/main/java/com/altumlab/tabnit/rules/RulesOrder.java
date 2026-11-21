package com.altumlab.tabnit.rules;

import java.util.Comparator;

/** 글자 코드 순의 비교 (D2 AD-38). */
final class RulesOrder {

    static final Comparator<String> STRINGS = RulesOrder::compare;

    private RulesOrder() {
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
}
