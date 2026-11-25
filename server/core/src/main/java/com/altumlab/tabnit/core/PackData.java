package com.altumlab.tabnit.core;

import java.time.LocalDate;
import java.util.List;

/**
 * Pack 하나의 자료를 기준 날짜로 읽은 것 ({@link PackDataQuery}).
 *
 * <p>한도를 넘으면 자료는 비어 있고 수만 있다 ({@code complete} 가 거짓). 일부만 주지 않는다: 일부만 보고 "없다"고
 * 잘못 읽게 하지 않는다.
 *
 * @param packId Pack 의 식별자
 * @param asOf 기준 날짜
 * @param entities Pack 의 개체 전부. 식별자의 글자 코드 순이다. 개체에는 유효기간이 없다 (D2 AD-218)
 * @param relations 기준 날짜에 유효한 관계. 식별자의 글자 코드 순이다
 * @param values 기준 날짜에 유효한 속성 값. 개체의 식별자, 속성의 이름, 시작일, 값의 식별자 순이다
 * @param entityCount 개체의 수
 * @param relationCount 기준 날짜에 유효한 관계의 수
 * @param valueCount 기준 날짜에 유효한 속성 값의 수
 * @param complete 자료를 모두 담았는가. 세 수의 합이 한도를 넘으면 거짓이고 세 목록은 비어 있다
 */
public record PackData(String packId, LocalDate asOf, List<EntityRef> entities, List<Relation> relations,
        List<AttributeValue> values, int entityCount, int relationCount, int valueCount, boolean complete) {

    public PackData {
        entities = List.copyOf(entities);
        relations = List.copyOf(relations);
        values = List.copyOf(values);
    }
}
