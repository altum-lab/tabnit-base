package com.altumlab.tabnit.pack;

import java.util.List;

/**
 * 적재의 결과.
 *
 * @param packId 적재한 Pack 의 식별자
 * @param outcome 무엇이 일어났는가
 * @param warnings 경고. 적재를 막지 않는다. 같은 Pack 을 다시 적재해 바뀐 것이 없어도 같은 경고가 나온다 (D2 AD-323).
 *        규칙의 경고는 규칙 번호 순이다. 없으면 비어 있다
 */
public record PackLoadResult(String packId, Outcome outcome, List<PackWarning> warnings) {

    public PackLoadResult {
        warnings = List.copyOf(warnings);
    }

    public enum Outcome {

        /** 처음 적재했다. */
        LOADED,

        /** 이미 있던 정의를 고친 것으로 바꿨다. */
        UPDATED,

        /** 이미 있는 것과 같아 아무것도 바꾸지 않았다. */
        UNCHANGED
    }
}
