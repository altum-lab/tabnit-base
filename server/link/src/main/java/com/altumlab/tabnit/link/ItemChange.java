package com.altumlab.tabnit.link;

import java.util.List;

/**
 * 판 비교에서 나온 바뀐 항목 하나 (D3 6절).
 *
 * @param itemKey 항목의 키
 * @param kind 추가, 변경, 삭제
 * @param primaryEntityId 그 행의 주 개체. 삭제된 행은 앞 판의 그 행의 주 개체다 (D2 AD-120)
 * @param targetEntityIds 변동 대상. 추가는 새 판에서, 삭제는 앞 판에서 그 행이 만든 개체이고 변경은 둘을 합친 것이다
 *        (D2 AD-58). 식별자의 글자 코드 순이다
 * @param changedAttributes 그 행에서 바뀐 속성의 이름. 추가는 새 판에서 넣은 것 전부, 삭제는 앞 판에서 넣었던 것 전부이고
 *        변경은 두 판에서 그 행이 만든 것이 다른 속성이다 (D2 AD-291). 글자 코드 순이다
 * @param changedRelationTypes 그 행에서 바뀐 관계 유형의 이름. 같은 규칙이다. 글자 코드 순이다
 */
public record ItemChange(String itemKey, ChangeKind kind, String primaryEntityId, List<String> targetEntityIds,
        List<String> changedAttributes, List<String> changedRelationTypes) {

    public ItemChange {
        targetEntityIds = List.copyOf(targetEntityIds);
        changedAttributes = List.copyOf(changedAttributes);
        changedRelationTypes = List.copyOf(changedRelationTypes);
    }
}
