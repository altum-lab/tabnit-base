package com.altumlab.tabnit.link;

import java.time.LocalDate;

/**
 * 목록에 보일 변동 이벤트 한 줄 (카드 C-15, D2 AD-417): 변동 이벤트와, 그것이 나온 판.
 * 변동 대상과 바뀐 값은 담지 않는다. 그것은 변동 이벤트 하나를 읽을 때 본다 ({@link ChangeEvents#find}).
 *
 * @param id 변동 이벤트의 식별자
 * @param editionId 그 변동이 나온 판
 * @param inputSourceId 그 판의 입력 자료
 * @param editionNo 그 판의 번호
 * @param effectiveDate 그 판의 적용일
 * @param itemKey 항목의 키
 * @param kind 추가, 변경, 삭제
 * @param primaryEntityId 그 행의 주 개체
 * @param status 대기, 처리됨, 실패
 * @param failedStage 실패했을 때 어느 단계인가. 아니면 null
 * @param failureCode 실패했을 때의 이유의 코드. 아니면 null
 */
public record ChangeEventSummary(String id, String editionId, String inputSourceId, int editionNo, LocalDate effectiveDate,
        String itemKey, ChangeKind kind, String primaryEntityId, ChangeEventStatus status, String failedStage,
        String failureCode) {
}
