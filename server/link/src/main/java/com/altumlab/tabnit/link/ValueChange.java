package com.altumlab.tabnit.link;

/**
 * 변동 이벤트의 행에서 바뀐 속성 값 하나 (D5 5절 change_event_changed_value, D2 AD-367).
 *
 * @param entityId 어느 개체의 속성인가
 * @param attributeName 속성의 이름
 * @param before 앞 판에서 그 행이 넣었던 값. 저장한 글자 그대로다. 없었으면 null (추가된 행, 빈 칸이던 속성)
 * @param after 새 판에서 그 행이 넣은 값. 없어졌으면 null (삭제된 행, 빈 칸이 된 속성)
 */
public record ValueChange(String entityId, String attributeName, String before, String after) {
}
