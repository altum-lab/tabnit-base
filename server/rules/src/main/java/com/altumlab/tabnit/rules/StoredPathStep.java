package com.altumlab.tabnit.rules;

/**
 * 저장된 근거 경로의 단계 하나 (D5 6절 impact_path_step). 관계의 내용은 계산할 때의 값이다 (D2 AD-212).
 *
 * @param stepNo 변동 대상 쪽부터의 차례. 1 부터
 */
public record StoredPathStep(int stepNo, String relationId, String relationType, String sourceEntityId,
        String targetEntityId) {
}
