package com.altumlab.tabnit.common;

/** 감사 기록 한 줄의 결과 (D5 7절). */
public enum AuditOutcome {
    /** 하기 전에 남긴 줄. 누가 무엇을 하려 했는지다. 결과는 이 줄을 가리키는 다른 줄에 있다. */
    STARTED,
    /** 성공. */
    SUCCESS,
    /** 거부. 권한이 없거나 로그인하지 못했다. */
    DENIED,
    /** 실패. 요청이 틀렸거나 서버가 처리하지 못했다. */
    FAILED
}
