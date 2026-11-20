package com.altumlab.tabnit.pack;

/**
 * 언어별 이름표 하나 (D6 5절 "이름표", D2 AD-131). 기본 이름표는 각 정의의 label 에 있다.
 *
 * @param name 이름표가 붙은 정의의 이름. Pack 자신의 이름표이면 빈 글자다
 * @param language 언어 표시. 소문자다
 * @param label 그 언어의 이름표
 */
public record LocalizedLabel(String name, String language, String label) {
}
