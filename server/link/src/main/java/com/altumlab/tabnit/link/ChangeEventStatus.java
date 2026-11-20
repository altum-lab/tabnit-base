package com.altumlab.tabnit.link;

/** 변동 이벤트의 상태 (D5 5절 change_event, D2 AD-154). */
public enum ChangeEventStatus {
    /** 뒤 단계(영향 범위 계산, 규칙 실행)를 아직 하지 않았다. */
    PENDING,
    /** 뒤 단계를 마쳤다. */
    PROCESSED,
    /** 뒤 단계가 실패했다. 다시 실행할 수 있다. */
    FAILED
}
