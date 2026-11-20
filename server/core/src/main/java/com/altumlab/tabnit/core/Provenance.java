package com.altumlab.tabnit.core;

/**
 * 출처. 관계가 어느 입력 자료의 어느 판에서 왔는지 (D2 AD-30).
 *
 * @param inputSourceId 입력 자료의 식별자
 * @param editionNo 그 자료의 판 번호. 1 부터다 (D2 AD-75)
 */
public record Provenance(String inputSourceId, int editionNo) {

    public Provenance {
        Identifiers.requireNotBlank(inputSourceId, "입력 자료의 식별자");
        if (editionNo < 1) {
            throw new IllegalArgumentException("판 번호는 1 부터입니다: " + editionNo + ". 1 이상의 값을 넣어 다시 보내세요.");
        }
    }
}
