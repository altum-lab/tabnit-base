package com.altumlab.tabnit.rules;

import com.altumlab.tabnit.pack.RuleChangeKind;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 규칙을 적용할 변동 하나 (D8 1절 "받는 것"). 부르는 쪽(api)이 link 의 변동 이벤트에서 옮겨 담는다.
 * 이 모듈은 link 를 모른다.
 *
 * @param changeEventId 변동 이벤트의 식별자. 값으로만 담는다
 * @param kind 변동의 종류
 * @param primaryEntityId 변동이 생긴 행의 주 개체. 조치의 근거가 된다 (D2 AD-120)
 * @param targetEntityIds 변동 대상. 하나 이상. 행이 만든 모든 개체다 (D2 AD-43)
 * @param effectiveDate 그 변동이 나온 판의 적용일. 조건의 값은 이 날짜와 그 전날로 본다 (D2 AD-101)
 * @param inputSourceId 그 변동이 나온 입력 자료의 식별자. 모르면 null (D2 AD-290)
 * @param changedAttributes 그 행에서 바뀐 속성의 이름. 추가된 행은 넣은 것 전부, 삭제된 행은 넣었던 것 전부다.
 *        바뀐 것을 모르면 null (D2 AD-291)
 * @param changedRelationTypes 그 행에서 바뀐 관계 유형의 이름. 바뀐 것을 모르면 null.
 *        속성과 관계 유형 가운데 하나라도 null 이면 바뀐 것을 모르는 변동이다
 */
public record RuleChange(String changeEventId, RuleChangeKind kind, String primaryEntityId, List<String> targetEntityIds,
        LocalDate effectiveDate, String inputSourceId, List<String> changedAttributes,
        List<String> changedRelationTypes) {

    public RuleChange {
        Objects.requireNonNull(changeEventId, "변동 이벤트의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(kind, "변동의 종류는 반드시 있어야 합니다");
        Objects.requireNonNull(primaryEntityId, "주 개체의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(targetEntityIds, "변동 대상은 반드시 있어야 합니다");
        Objects.requireNonNull(effectiveDate, "적용일은 반드시 있어야 합니다");
        if (effectiveDate.isBefore(LocalDate.of(1, 1, 1)) || effectiveDate.isAfter(LocalDate.of(9999, 12, 31))) {
            // 저장소가 담는 날짜의 범위다. 조건이 값을 읽을 때에야 걸리지 않도록 여기서 막는다.
            throw new IllegalArgumentException(
                    "적용일은 0001-01-01 부터 9999-12-31 까지만 받습니다: " + effectiveDate + ". 적용일을 확인하세요.");
        }
        targetEntityIds = List.copyOf(targetEntityIds);
        if (changedAttributes == null || changedRelationTypes == null) {
            changedAttributes = null;
            changedRelationTypes = null;
        }
        else {
            changedAttributes = List.copyOf(changedAttributes);
            changedRelationTypes = List.copyOf(changedRelationTypes);
        }
    }

    /** 입력 자료와 바뀐 것을 모르는 변동. 그것을 가리는 규칙은 이 변동에서 평가에 실패한다 (D8 7절). */
    public RuleChange(String changeEventId, RuleChangeKind kind, String primaryEntityId, List<String> targetEntityIds,
            LocalDate effectiveDate) {
        this(changeEventId, kind, primaryEntityId, targetEntityIds, effectiveDate, null, null, null);
    }

    /** 그 행에서 바뀐 것을 아는 변동인가. */
    public boolean changedItemsKnown() {
        return changedAttributes != null;
    }
}
