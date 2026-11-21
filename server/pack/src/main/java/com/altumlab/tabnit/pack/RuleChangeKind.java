package com.altumlab.tabnit.pack;

/**
 * 규칙이 걸리는 변동의 종류 (D8 2절 `tabnit:whenChangeKind`).
 * 규칙의 목록에는 여기 선언한 차례(추가, 변경, 삭제)로 담긴다. 규칙 파일에 적은 차례가 아니다.
 */
public enum RuleChangeKind {
    /** 추가. `tabnit:Added` */
    ADDED,
    /** 변경. `tabnit:Changed` */
    CHANGED,
    /** 삭제. `tabnit:Removed` */
    REMOVED
}
