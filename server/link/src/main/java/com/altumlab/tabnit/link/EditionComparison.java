package com.altumlab.tabnit.link;

import java.util.List;

/**
 * 같은 입력 자료의 두 판을 견준 결과 (카드 C-16, D2 AD-418). 저장되지 않는다.
 *
 * @param from 앞에 적은 판. 견주는 기준이다
 * @param to 뒤에 적은 판
 * @param changes 바뀐 항목. 항목의 키의 글자 코드 순이다. from 에 없고 to 에 있으면 추가, 거꾸로면 삭제,
 *        둘에 있고 그 행이 만든 것이 다르면 변경이다. 두 판이 같으면 비어 있다
 */
public record EditionComparison(Edition from, Edition to, List<ItemChange> changes) {

    public EditionComparison {
        changes = List.copyOf(changes);
    }
}
