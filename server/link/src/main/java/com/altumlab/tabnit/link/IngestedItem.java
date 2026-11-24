package com.altumlab.tabnit.link;

import java.util.List;

/**
 * 받아들인 항목(행) 하나 (D5 5절 edition_item).
 *
 * @param itemKey 항목의 키. 키 열의 값을 적힌 차례로, 식별자와 같은 규칙으로 이은 글자다 (D2 AD-122)
 * @param rowNo 원본 파일에서의 행 번호
 * @param contentHash 항목의 내용 해시. 매핑이 쓰는 열의 읽은 값으로 정해진다 (D2 AD-156). 변경인지는 이 해시가 아니라
 *        그 행이 만든 것이 앞 판과 다른가로 가린다 (D2 AD-201)
 * @param primaryEntityId 그 행의 주 개체의 식별자 (D2 AD-120)
 * @param outputs 이 행이 만든 것. 종류, 식별자 순. 관계의 끝으로만 나온 개체도 이 행이 만든 개체다 (D2 AD-59)
 */
public record IngestedItem(String itemKey, int rowNo, String contentHash, String primaryEntityId,
        List<ItemOutput> outputs) {

    public IngestedItem {
        outputs = List.copyOf(outputs);
    }
}
