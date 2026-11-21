package com.altumlab.tabnit.core;

/**
 * 없는 개체의 관계나 속성 값을 조회했다. 빈 목록으로 답하지 않고 오류로 알린다 (보드 CORE-02).
 * 개체가 있는지를 묻는 조회({@link EntityQuery})는 이 오류를 던지지 않고 없다고 답한다 (D2 AD-218).
 */
public class EntityNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EntityNotFoundException(String entityId) {
        super("개체를 찾을 수 없습니다: " + entityId + ". 식별자(Pack, 개체 유형, 키)를 확인하세요.");
    }
}
