package com.altumlab.tabnit.verify;

import java.util.List;
import java.util.Map;

/**
 * 정답에 적힌 것을 제품의 식별자로 바꾼다 (D4 3절: 실행기가 식별자와 같은 규칙으로 이어 견준다).
 *
 * <p>규칙은 D5 1절이다 (D2 AD-65): 값을 세로 막대로 잇고, 값 안의 세로 막대와 역빗금 앞에는 역빗금을 붙인다.
 * 개체의 식별자는 Pack, 개체 유형, 입력의 키 차례다 (D2 AD-37). 항목의 키는 키 열의 값을 차례대로 이은 것이다 (D2 AD-122).
 */
final class Ids {

    private Ids() {
    }

    static String join(List<String> values) {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                id.append('|');
            }
            String value = values.get(i);
            for (int j = 0; j < value.length(); j++) {
                char c = value.charAt(j);
                if (c == '|' || c == '\\') {
                    id.append('\\');
                }
                id.append(c);
            }
        }
        return id.toString();
    }

    /**
     * 정답에 유형의 이름과 입력의 키로 적힌 개체를 식별자로 바꾼다.
     *
     * @param reference 정답의 { type, key }
     */
    static String entity(String packId, Object reference) {
        if (!(reference instanceof Map<?, ?> map) || map.get("type") == null || map.get("key") == null) {
            throw new QuestionFileException("개체는 { type: 유형, key: 키 } 로 적어야 합니다: " + reference);
        }
        return join(List.of(packId, String.valueOf(map.get("type")), String.valueOf(map.get("key"))));
    }
}
