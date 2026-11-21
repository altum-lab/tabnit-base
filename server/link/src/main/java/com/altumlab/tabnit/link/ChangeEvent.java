package com.altumlab.tabnit.link;

import java.util.List;

/**
 * 변동 이벤트 하나 (D5 5절 change_event, change_target). 바뀐 항목 하나다.
 *
 * @param id 변동 이벤트의 식별자. (판, 항목의 키)를 이은 글자
 * @param editionId 어느 판에서 나온 변동인가 (새 판)
 * @param primaryEntityId 그 행의 주 개체. 뒤 단계가 조치의 근거로 쓴다
 * @param failedStage 실패했을 때 어느 단계인가 (`IMPACT`, `RULES`). 아니면 null
 * @param failureCode 실패한 이유의 코드. 아니면 null
 * @param targetEntityIds 변동 대상. 식별자의 글자 코드 순이다
 * @param changedAttributes 그 행에서 바뀐 속성의 이름. 글자 코드 순이다. 추가된 행은 넣은 것 전부, 삭제된 행은 넣었던 것
 *        전부다 (D2 AD-291). 바뀐 것을 남기기 전에 만들어진 변동 이벤트이면 null 이다: 바뀐 것이 없는 것이 아니라 모른다
 * @param changedRelationTypes 그 행에서 바뀐 관계 유형의 이름. 글자 코드 순이다. 모르면 null 이다
 */
public record ChangeEvent(String id, String editionId, String itemKey, ChangeKind kind, String primaryEntityId,
        ChangeEventStatus status, String failedStage, String failureCode, List<String> targetEntityIds,
        List<String> changedAttributes, List<String> changedRelationTypes) {

    public ChangeEvent {
        targetEntityIds = List.copyOf(targetEntityIds);
        changedAttributes = changedAttributes == null ? null : List.copyOf(changedAttributes);
        changedRelationTypes = changedRelationTypes == null ? null : List.copyOf(changedRelationTypes);
    }
}
