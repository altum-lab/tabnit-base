package com.altumlab.tabnit.pack;

import java.util.List;

/**
 * 개체 유형의 정의 (D6 5절).
 *
 * @param name 이름. 식별자와 저장소에 들어가는 글자다
 * @param label 화면에 보일 이름
 * @param parents 상위 유형의 이름. 이름의 글자 코드 순이다. 없으면 빈 목록
 * @param displayNameAttribute 표시 이름으로 쓸 속성의 이름. 이 유형이 스스로 적은 값이다. 적지 않았으면 null 이고,
 *        그때는 상위 유형의 값을 받는다 (D2 AD-88)
 */
public record EntityTypeDefinition(String name, String label, List<String> parents, String displayNameAttribute) {

    public EntityTypeDefinition {
        parents = List.copyOf(parents);
    }
}
