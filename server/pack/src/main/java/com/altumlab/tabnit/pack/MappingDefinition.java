package com.altumlab.tabnit.pack;

import java.util.List;

/**
 * 매핑 정의 하나 (D6 7절). 입력 자료 하나에 하나다.
 *
 * @param inputSourceId 입력 자료의 식별자. 출처의 입력 자료가 된다
 * @param fileFormat 파일의 종류
 * @param sheetName 엑셀일 때 읽을 시트. CSV 이면 null
 * @param defaultDatePattern 날짜의 기본 모양. 유효기간의 열과, 모양을 따로 적지 않은 날짜 속성의 열을 이 모양으로 읽는다.
 *        적지 않았으면 null 이고 표준 표기만 받는다 (D2 AD-143)
 * @param requiredColumns 반드시 있어야 하는 열. 적힌 차례 그대로
 * @param itemKeyColumns 항목의 키 열. 적힌 차례 그대로. 이 차례로 이어 항목의 키를 만든다 (D2 AD-122)
 * @param primaryEntity 행의 주 개체 (D2 AD-120)
 * @param entities 행이 만드는 개체. 개체 유형, 키 열 순
 * @param relations 행이 만드는 관계. 관계 유형, 출발, 도착 순
 */
public record MappingDefinition(String inputSourceId, MappingFileFormat fileFormat, String sheetName,
        String defaultDatePattern, List<String> requiredColumns, List<String> itemKeyColumns, MappedEntityRef primaryEntity,
        List<MappedEntity> entities, List<MappedRelation> relations) {

    public MappingDefinition {
        requiredColumns = List.copyOf(requiredColumns);
        itemKeyColumns = List.copyOf(itemKeyColumns);
        entities = List.copyOf(entities);
        relations = List.copyOf(relations);
    }
}
