package com.altumlab.tabnit.api;

/**
 * 로그인한 사람. 규격의 Session.
 *
 * @param timeZone 설치 설정의 시간대. 화면이 시각을 이 시간대로 바꿔 보여 준다 (D2 AD-348)
 */
record SessionResponse(String loginId, String role, String timeZone) {
}
