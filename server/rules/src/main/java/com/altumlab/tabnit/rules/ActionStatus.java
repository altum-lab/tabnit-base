package com.altumlab.tabnit.rules;

/** 조치의 상태 (D2 AD-47). 조치는 대기로 만들어진다. 확정과 보류는 담당자가 한다. */
public enum ActionStatus {
    PENDING, CONFIRMED, ON_HOLD
}
