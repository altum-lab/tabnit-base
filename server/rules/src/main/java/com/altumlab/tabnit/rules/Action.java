package com.altumlab.tabnit.rules;

import com.altumlab.tabnit.pack.RuleSubject;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 조치 하나: 해야 할 일 (D8 5절, D5 6절 action).
 *
 * @param id 조치의 식별자. (변동 이벤트, 규칙 번호, 대상 개체)를 이은 글자 (D2 AD-60)
 * @param packId 어느 Pack 의 조치인가
 * @param changeEventId 어느 변동에서 나왔는가
 * @param ruleNo 근거 규칙의 번호
 * @param ruleLabel 만들 때의 그 규칙의 이름 (D2 AD-226)
 * @param actionText 해야 할 일. 만들 때 그 규칙에 적혀 있던 문구 그대로다
 * @param priority 우선순위. 수가 작을수록 먼저다
 * @param appliesTo 대상이 영향 대상인가 변동 대상인가. 변동 대상이면 근거 경로가 없다 (D2 AD-102)
 * @param targetEntityId 대상 개체
 * @param targetEntityType 대상 개체의 유형. 만들 때의 값
 * @param basisEntityId 근거가 되는 개체. 변동이 생긴 행의 주 개체다 (D2 AD-120)
 * @param basisInputSourceId 그 변동이 나온 입력 자료
 * @param basisEditionNo 그 변동이 나온 판의 번호 (D2 AD-98)
 * @param effectiveDate 그 판의 적용일
 * @param dueDate 기한. 적용일에 규칙이 적은 일수를 더한 날짜 (D2 AD-99)
 * @param status 상태
 * @param createdAt 만든 때. 밖에서 받은 시각
 */
public record Action(String id, String packId, String changeEventId, String ruleNo, String ruleLabel, String actionText,
        int priority, RuleSubject appliesTo, String targetEntityId, String targetEntityType, String basisEntityId,
        String basisInputSourceId, int basisEditionNo, LocalDate effectiveDate, LocalDate dueDate, ActionStatus status,
        Instant createdAt) {
}
