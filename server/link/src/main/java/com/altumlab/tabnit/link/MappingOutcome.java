package com.altumlab.tabnit.link;

import java.util.List;

/**
 * 매핑 실행의 결과 (보드 C-03).
 *
 * @param items 받아들인 항목. 항목의 키의 글자 코드 순이다
 * @param notices 알림. 행 번호, 종류, 코드, 내용 순이다 (D2 AD-153)
 * @param changes 앞 판과 비교해 바뀐 항목. 항목의 키의 글자 코드 순이다. 첫 판이면 비어 있다 (D2 AD-148)
 */
public record MappingOutcome(List<IngestedItem> items, List<IngestNotice> notices, List<ItemChange> changes) {

    public MappingOutcome {
        items = List.copyOf(items);
        notices = List.copyOf(notices);
        changes = List.copyOf(changes);
    }
}
