package com.altumlab.tabnit.common;

import java.time.Instant;

/**
 * 로그인을 한 번 시도한 결과.
 *
 * @param outcome 어떻게 끝났는가
 * @param account 로그인한 계정. 성공했을 때만 있다
 * @param lockedUntil 막혀 있을 때, 언제까지인가. 아니면 null
 */
public record LoginAttempt(Outcome outcome, UserAccount account, Instant lockedUntil) {

    public enum Outcome {
        /** 아이디와 비밀번호가 맞다. */
        SUCCEEDED,
        /** 아이디가 없거나 비밀번호가 틀렸다. 둘을 가리지 않는다: 계정이 있는지를 알려 주지 않는다. */
        FAILED,
        /** 잇달아 여러 번 틀려 막혀 있다. 비밀번호가 맞아도 받지 않는다. */
        LOCKED
    }
}
