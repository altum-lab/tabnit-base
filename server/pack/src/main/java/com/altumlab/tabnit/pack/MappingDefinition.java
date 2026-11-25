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
 * @param label 화면에 보일 입력 자료의 이름표. 적지 않았으면 null 이고 화면은 식별자를 보인다 (D2 AD-384).
 *        읽는 법과 적재되는 것에는 닿지 않는다
 */
public record MappingDefinition(String inputSourceId, MappingFileFormat fileFormat, String sheetName,
        String defaultDatePattern, List<String> requiredColumns, List<String> itemKeyColumns, MappedEntityRef primaryEntity,
        List<MappedEntity> entities, List<MappedRelation> relations, String label) {

    public MappingDefinition {
        requiredColumns = List.copyOf(requiredColumns);
        itemKeyColumns = List.copyOf(itemKeyColumns);
        entities = List.copyOf(entities);
        relations = List.copyOf(relations);
    }

    /** 이름표가 없는 매핑. */
    public MappingDefinition(String inputSourceId, MappingFileFormat fileFormat, String sheetName, String defaultDatePattern,
            List<String> requiredColumns, List<String> itemKeyColumns, MappedEntityRef primaryEntity,
            List<MappedEntity> entities, List<MappedRelation> relations) {
        this(inputSourceId, fileFormat, sheetName, defaultDatePattern, requiredColumns, itemKeyColumns, primaryEntity, entities,
                relations, null);
    }

    /** 화면에 보일 이름: 이름표가 있으면 이름표, 없으면 식별자다. */
    public String shownLabel() {
        return label == null ? inputSourceId : label;
    }

    /**
     * 이름표를 빼고 같은가: 파일을 읽는 법과 행이 만드는 것이 같은가. 판이 들어온 입력 자료의 매핑을 바꿔도 되는지 가리는 데
     * 쓴다. 이름표는 화면에 보이는 글자라 바꿔도 된다 (D2 AD-129, AD-384).
     */
    public boolean readsLike(MappingDefinition other) {
        return inputSourceId.equals(other.inputSourceId) && fileFormat == other.fileFormat
                && java.util.Objects.equals(sheetName, other.sheetName)
                && java.util.Objects.equals(defaultDatePattern, other.defaultDatePattern)
                && requiredColumns.equals(other.requiredColumns) && itemKeyColumns.equals(other.itemKeyColumns)
                && primaryEntity.equals(other.primaryEntity) && entities.equals(other.entities)
                && relations.equals(other.relations);
    }
}
