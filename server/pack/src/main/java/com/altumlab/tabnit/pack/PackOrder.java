package com.altumlab.tabnit.pack;

import java.util.Comparator;

/** 글자 코드 순의 비교 (D2 AD-38). 저장소의 정렬 규칙 "C" 와 같은 순서다. UTF-16 단위가 아니라 글자 코드로 비교한다. */
final class PackOrder {

    static final Comparator<String> STRINGS = PackOrder::compare;

    static final Comparator<PackProblem> PROBLEMS = Comparator.comparing(PackProblem::file, STRINGS)
        .thenComparing(PackProblem::item, STRINGS)
        .thenComparing(PackProblem::message, STRINGS);

    static final Comparator<LocalizedLabel> LABELS = Comparator.comparing(LocalizedLabel::name, STRINGS)
        .thenComparing(LocalizedLabel::language, STRINGS);

    private PackOrder() {
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
