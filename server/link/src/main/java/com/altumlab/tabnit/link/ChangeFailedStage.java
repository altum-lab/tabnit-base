package com.altumlab.tabnit.link;

/** 변동 이벤트의 뒤 단계 가운데 어느 것이 실패했는가 (D5 5절 change_event, D2 AD-154). */
public enum ChangeFailedStage {
    /** 영향 범위 계산. */
    IMPACT,
    /** 규칙 실행. */
    RULES
}
