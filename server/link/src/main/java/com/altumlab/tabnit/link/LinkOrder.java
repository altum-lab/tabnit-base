package com.altumlab.tabnit.link;

import java.util.Comparator;

/** 글자 코드 순의 비교 (D2 AD-38)와, 값을 식별자의 규칙으로 잇는 일 (D2 AD-65, AD-122). */
final class LinkOrder {

    static final Comparator<String> STRINGS = LinkOrder::compare;

    /** null 은 값이 있는 것보다 앞이다. */
    static final Comparator<String> NULLS_FIRST = Comparator.nullsFirst(STRINGS);

    private LinkOrder() {
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

    /** 값을 세로 막대로 잇는다. 값 안의 세로 막대와 역빗금 앞에는 역빗금을 붙인다. core 의 식별자와 같은 규칙이다. */
    static String join(Iterable<String> values) {
        StringBuilder joined = new StringBuilder();
        boolean first = true;
        for (String value : values) {
            if (!first) {
                joined.append('|');
            }
            first = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '|' || c == '\\') {
                    joined.append('\\');
                }
                joined.append(c);
            }
        }
        return joined.toString();
    }
}
