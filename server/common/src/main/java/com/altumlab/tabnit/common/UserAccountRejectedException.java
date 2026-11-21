package com.altumlab.tabnit.common;

/**
 * 계정을 만들 수 없다 (규칙에 맞지 않는 아이디나 비밀번호, 이미 있는 아이디). 아무것도 저장되지 않았다.
 *
 * <p>문구에 비밀번호를 담지 않는다.
 */
public class UserAccountRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UserAccountRejectedException(String message) {
        super(message);
    }
}
