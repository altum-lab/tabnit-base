package com.altumlab.tabnit.core;

/**
 * 식별자 문자열을 만든다 (D5 1절, D2 AD-65).
 *
 * <p>식별자는 값을 정해진 차례로 세로 막대({@code |})로 이어 붙인 것이다. 값 안에 세로 막대나 역빗금이 있으면
 * 그 앞에 역빗금을 붙인다. 같은 값에서는 언제나 같은 식별자가 나온다.
 */
final class Identifiers {

    private static final char SEPARATOR = '|';

    private static final char ESCAPE = '\\';

    private Identifiers() {
    }

    static String join(String... values) {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                id.append(SEPARATOR);
            }
            String value = values[i];
            for (int j = 0; j < value.length(); j++) {
                char c = value.charAt(j);
                if (c == SEPARATOR || c == ESCAPE) {
                    id.append(ESCAPE);
                }
                id.append(c);
            }
        }
        return id.toString();
    }

    /** 식별자를 이루는 값은 비어 있을 수 없다 (D2 AD-75). */
    static String requireNotBlank(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + "은(는) 비어 있을 수 없습니다. 값을 넣어 다시 보내세요.");
        }
        return value;
    }
}
