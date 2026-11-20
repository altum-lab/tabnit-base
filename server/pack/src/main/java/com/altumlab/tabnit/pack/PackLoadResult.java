package com.altumlab.tabnit.pack;

/**
 * 적재의 결과.
 *
 * @param packId 적재한 Pack 의 식별자
 * @param outcome 무엇이 일어났는가
 */
public record PackLoadResult(String packId, Outcome outcome) {

    public enum Outcome {

        /** 처음 적재했다. */
        LOADED,

        /** 이미 있던 정의를 고친 것으로 바꿨다. */
        UPDATED,

        /** 이미 있는 것과 같아 아무것도 바꾸지 않았다. */
        UNCHANGED
    }
}
