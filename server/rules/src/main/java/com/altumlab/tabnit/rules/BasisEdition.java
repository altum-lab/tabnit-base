package com.altumlab.tabnit.rules;

import java.util.Objects;

/**
 * 변동이 나온 입력 자료의 판. 조치의 근거에 적는다 (D2 AD-98). 부르는 쪽(api)이 link 의 판에서 옮겨 담는다.
 * 이 모듈은 link 를 모른다. 값으로만 담는다.
 *
 * @param inputSourceId 입력 자료의 식별자
 * @param editionNo 그 입력 자료 안에서의 판 번호. 1 부터
 */
public record BasisEdition(String inputSourceId, int editionNo) {
    public BasisEdition {
        Objects.requireNonNull(inputSourceId, "입력 자료의 식별자는 반드시 있어야 합니다");
        if (inputSourceId.isBlank()) {
            throw new IllegalArgumentException("입력 자료의 식별자가 비어 있습니다. 변동이 나온 판의 입력 자료를 넣어 다시 보내세요.");
        }
        if (editionNo < 1) {
            throw new IllegalArgumentException("판 번호는 1 이상입니다: " + editionNo + ". 변동이 나온 판의 번호를 넣어 다시 보내세요.");
        }
    }
}
