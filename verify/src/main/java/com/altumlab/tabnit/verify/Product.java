package com.altumlab.tabnit.verify;

import java.time.LocalDate;

/** 검증 질문을 묻는 상대: 띄워 둔 제품. 실행기는 이 길로만 묻는다 (D2 AD-111, AD-279: 모두 HTTP 다). */
interface Product {

    /** 어떤 날짜에 어떤 개체의 유효한 관계. */
    Reply relations(String entityId, LocalDate asOf);

    /** 변동 이벤트 하나의 저장된 영향 범위. */
    Reply impactScope(String changeEventId);

    /** 변동 이벤트 하나에서 만들어진 조치. 상태로 거르지 않는다. */
    Reply actions(String packId, String changeEventId);

    /**
     * 제품의 답.
     *
     * @param status HTTP 상태
     * @param body 본문을 읽은 것 (JSON 의 객체는 Map, 목록은 List). 본문이 없으면 null
     */
    record Reply(int status, Object body) {
    }
}
