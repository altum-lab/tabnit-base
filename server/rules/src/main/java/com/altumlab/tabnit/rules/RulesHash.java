package com.altumlab.tabnit.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 긴 식별자를 키로 쓸 때의 해시. UTF-8 바이트의 SHA-256 을 소문자 16진수 64자로 적는다 (D5 6절). */
final class RulesHash {

    private RulesHash() {
    }

    static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            // 모든 Java 실행 환경이 SHA-256 을 갖추고 있다. 여기 오면 실행 환경이 잘못된 것이다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다. Java 실행 환경을 확인하세요.", e);
        }
    }
}
