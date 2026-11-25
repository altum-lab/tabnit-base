package com.altumlab.tabnit.link;

/**
 * 판의 목록의 한 줄: 받아들인 판 하나와 그 판에 저장된 것의 수 (D7 5절 "지난 판", D2 AD-383).
 *
 * @param edition 판
 * @param itemCount 받아들인 항목의 수 (앞 판의 것을 이어 둔 항목 포함)
 * @param changeCount 변동 이벤트의 수. 첫 판이면 0 이다
 * @param noticeCount 투입의 알림의 수
 */
public record EditionListEntry(Edition edition, int itemCount, int changeCount, int noticeCount) {
}
