package com.altumlab.tabnit.common;

/**
 * 사용자 계정. 비밀번호와 그 해시는 담지 않는다.
 *
 * @param loginId 아이디
 * @param role 역할
 */
public record UserAccount(String loginId, Role role) {
}
