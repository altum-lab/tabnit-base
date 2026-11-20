package com.altumlab.tabnit.link;

import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.NewAttributeValue;
import com.altumlab.tabnit.core.NewRelation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 판 하나를 넣는 계획: 끝낼 것, 넣을 것, 남길 기록. 저장소에 닿기 전에 모두 정해진다 (D5 5절).
 * 모든 모음은 식별자나 키의 글자 코드 순이다.
 */
final class EditionPlan {

    /**
     * 항목이 만든 것 하나의 기록 (D5 5절 edition_item_output).
     *
     * @param subject 무엇에 대한 것인가. 개체, (개체, 속성), (출발, 도착, 관계 유형)을 이은 글자
     * @param factHash 행이 적은 것 한 줄의 해시. 다음 판의 행이 같은 것을 적으면 이 기록의 것을 잇는다
     * @param validFrom 저장된 시작일. 개체에는 없다
     * @param validTo 만들 때의 종료일. 없으면 열려 있다
     */
    record OutputDetail(ItemOutput.Kind kind, String objectId, String subject, String factHash, LocalDate validFrom,
            LocalDate validTo) {
    }

    /** 앞 판의 항목 하나. */
    record PreviousItem(String itemKey, String contentHash, String primaryEntityId, List<OutputDetail> outputs) {

        PreviousItem {
            outputs = List.copyOf(outputs);
        }
    }

    /** 새 판의 항목 하나와 그 기록. */
    record PlannedItem(IngestedItem item, List<OutputDetail> outputs) {

        PlannedItem {
            outputs = List.copyOf(outputs);
        }
    }

    final LocalDate effectiveDate;

    final List<PlannedItem> items = new ArrayList<>();

    /** 새 판의 적용일로 끝낼 관계와 속성 값의 식별자. */
    final TreeSet<String> relationsToEnd = new TreeSet<>(LinkOrder.STRINGS);

    final TreeSet<String> attributeValuesToEnd = new TreeSet<>(LinkOrder.STRINGS);

    final TreeMap<String, EntityRef> entities = new TreeMap<>(LinkOrder.STRINGS);

    final TreeMap<String, NewAttributeValue> attributeValues = new TreeMap<>(LinkOrder.STRINGS);

    final TreeMap<String, NewRelation> relations = new TreeMap<>(LinkOrder.STRINGS);

    MappingOutcome outcome;

    EditionPlan(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }
}
