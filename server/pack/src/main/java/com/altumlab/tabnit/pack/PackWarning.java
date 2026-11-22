package com.altumlab.tabnit.pack;

/**
 * Pack 을 적재하면서 알리는 경고 하나. 적재를 막지 않는다: Pack 은 형식에 맞고 적재된다.
 * 뜻대로 동작하지 않을 수 있는 곳을 Pack 을 만드는 사람에게 알린다 (D2 AD-323).
 *
 * @param file Pack 폴더 안에서의 자리. 규칙의 경고는 `rules` 다
 * @param item 경고의 대상. 규칙의 경고는 규칙 번호다
 * @param message 사용자에게 보이는 문구. 무엇이 왜 걸리는지와 무엇을 하면 되는지를 담는다
 */
public record PackWarning(String file, String item, String message) {
}
