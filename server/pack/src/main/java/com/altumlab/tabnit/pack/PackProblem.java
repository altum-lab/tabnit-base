package com.altumlab.tabnit.pack;

/**
 * Pack 에서 찾은 문제 하나.
 *
 * @param file 파일 이름. Pack 폴더 안에서의 이름이다. 폴더 자체의 문제면 빈 글자
 * @param item 틀린 항목. 정의의 이름이다. 이름을 알 수 없으면 주소 그대로
 * @param message 무엇이 틀렸고 어떻게 고치면 되는지
 */
public record PackProblem(String file, String item, String message) {
}
