package com.altumlab.tabnit.link;

/**
 * 투입을 저장하다가 실패했다. 판도, 원본도, 그 투입에서 넣은 개체·관계·속성 값도 남지 않는다.
 * 투입 기록에는 거부로 남는다 (D2 AD-185).
 *
 * <p>파일이 틀려 거부한 것은 예외가 아니라 결과({@link IngestResult})로 돌려준다.
 */
public class IngestFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IngestFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
