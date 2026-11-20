package com.altumlab.tabnit.link;

import java.util.List;

/**
 * 받아들인 항목(행) 하나 (D5 5절 edition_item).
 *
 * @param itemKey 항목의 키. 키 열의 값을 적힌 차례로, 식별자와 같은 규칙으로 이은 글자다 (D2 AD-122)
 * @param rowNo 원본 파일에서의 행 번호
 * @param outputs 이 행이 만든 것. 종류, 식별자 순. 관계의 끝으로만 나온 개체도 이 행이 만든 개체다 (D2 AD-59)
 */
public record IngestedItem(String itemKey, int rowNo, List<ItemOutput> outputs) {

    public IngestedItem {
        outputs = List.copyOf(outputs);
    }
}
