package com.altumlab.tabnit.pack;

/**
 * 규칙이 보는 대상: 조치를 만들 대상(`tabnit:appliesTo`)과 조건이 보는 대상(`tabnit:subject`) (D8 2절, 3절).
 * 선언한 차례가 조건의 정해진 차례에 쓰인다 (변동 대상이 먼저). 차례를 바꾸면 저장된 조건의 번호가 달라진다.
 */
public enum RuleSubject {
    /** 변동 대상. `tabnit:ChangeTarget` */
    CHANGE_TARGET,
    /** 영향 대상. `tabnit:ImpactTarget` */
    IMPACT_TARGET,
    /**
     * 규칙의 대상과 어느 관계로 이어진, 그 변동의 영향 범위 안의 대상. `tabnit:LinkedTarget` (D2 AD-338).
     * 조건이 보는 것으로만 쓴다. 조치의 대상이나 견주는 상대는 될 수 없다.
     */
    LINKED_TARGET
}
