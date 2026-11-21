package com.altumlab.tabnit.common;

/** 역할 (D1 5절). 역할마다 할 수 있는 일을 나누는 것은 E-06 이다. */
public enum Role {
    /** 관리자. Pack 적재까지 할 수 있다. */
    ADMIN,
    /** 담당자. 조치 확정까지 할 수 있다. */
    OFFICER
}
