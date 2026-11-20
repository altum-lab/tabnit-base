package com.altumlab.tabnit.core;

/** 없는 개체를 조회했다. 빈 목록으로 답하지 않고 오류로 알린다 (보드 CORE-02). */
public class EntityNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EntityNotFoundException(String entityId) {
        super("개체를 찾을 수 없습니다: " + entityId + ". 식별자(Pack, 개체 유형, 키)를 확인하세요.");
    }
}
