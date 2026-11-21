package com.altumlab.tabnit.api;

/**
 * 변동 이벤트 하나를 처리한 결과.
 *
 * @param changeEventId 변동 이벤트의 식별자
 * @param editionId 그 변동이 나온 판
 * @param itemKey 바뀐 항목의 키
 * @param processed 처리됨으로 표시했는가. 아니면 실패로 표시했다
 * @param failedStage 실패했을 때 어느 단계인가 (`IMPACT`, `RULES`). 아니면 null. 결과를 표시하지 못했을 때도 null 이다
 * @param failureCode 실패한 이유의 코드. 아니면 null. `NOT_RECORDED` 는 결과를 변동 이벤트에 표시하지 못했다는 뜻이다:
 *        그 변동의 상태는 바뀌지 않았고 다음 처리에서 다시 시도된다
 * @param actionCount 그 변동의 조치의 수. 실패했으면 0
 */
record ProcessedChange(String changeEventId, String editionId, String itemKey, boolean processed, String failedStage,
		String failureCode, int actionCount) {
}
