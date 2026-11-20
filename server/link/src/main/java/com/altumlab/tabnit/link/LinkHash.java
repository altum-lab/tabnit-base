package com.altumlab.tabnit.link;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 내용의 해시. SHA-256 을 소문자 16진수 64자로 적는다 (D2 AD-158). */
final class LinkHash {

    private LinkHash() {
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException e) {
            // 모든 Java 실행 환경이 SHA-256 을 갖추고 있다. 여기 오면 실행 환경이 잘못된 것이다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다. Java 실행 환경을 확인하세요.", e);
        }
    }
}
