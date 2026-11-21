package com.altumlab.tabnit.verify;

/** 질문 파일이 형식(D4 3절)에 맞지 않는다. 어느 파일의 어느 항목인지를 문구에 담는다. */
public class QuestionFileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QuestionFileException(String message) {
        super(message);
    }

    public QuestionFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
