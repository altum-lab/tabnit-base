package com.altumlab.tabnit.core;

/**
 * 영향 범위를 계산하다가 영향 방향을 넘겨받지 못한 관계 유형의 관계를 만났다 (D2 AD-123).
 *
 * <p>정의와 자료가 어긋난 것이다. 따라가지 않고 조용히 넘어가면 영향을 빠뜨린 결과가 맞는 것처럼 나가므로 멈춘다.
 */
public class UnknownImpactDirectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String relationType;

    public UnknownImpactDirectionException(String relationType, String relationId) {
        super("영향 방향을 모르는 관계 유형입니다: " + relationType + " (관계: " + relationId
                + "). Pack 의 온톨로지에 이 관계 유형과 영향 방향이 있는지 확인하세요.");
        this.relationType = relationType;
    }

    public String relationType() {
        return relationType;
    }
}
