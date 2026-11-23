package com.altumlab.tabnit.core;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 영향 범위 계산의 입력 (D3 7절).
 *
 * <p>출발 개체와 기준 날짜는 넣은 순서와 무관하다. 겹친 값을 하나로 줄이고 정해진 순서로 둔다.
 * 이 모듈에는 기준 날짜와 최대 깊이의 기본값이 없다. 부르는 쪽이 반드시 넘긴다 (D2 AD-35).
 *
 * @param startEntityIds 출발 개체의 식별자. 하나 이상. 부르는 쪽에서는 변동 대상이다. 함께 출발해 영향 범위 하나를 만든다 (D2 AD-64)
 * @param asOfDates 기준 날짜. 하나 이상. 여럿이면 날짜마다 따라가 합친다 (D2 AD-115)
 * @param directions 관계 유형마다의 영향 방향 (D2 AD-66)
 * @param maxDepth 최대 깊이. 1 이상. 출발 개체에서 관계 하나를 건넌 대상이 깊이 1 이다
 */
public record ImpactRequest(List<String> startEntityIds, List<LocalDate> asOfDates,
        Map<String, ImpactDirection> directions, int maxDepth) {

    public ImpactRequest {
        Objects.requireNonNull(startEntityIds, "출발 개체의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(asOfDates, "기준 날짜는 반드시 있어야 합니다");
        Objects.requireNonNull(directions, "관계 유형마다의 영향 방향은 반드시 있어야 합니다");
        if (startEntityIds.isEmpty()) {
            throw new IllegalArgumentException("출발 개체가 없습니다. 식별자를 하나 이상 넣어 다시 보내세요.");
        }
        if (asOfDates.isEmpty()) {
            throw new IllegalArgumentException("기준 날짜가 없습니다. 날짜를 하나 이상 넣어 다시 보내세요.");
        }
        if (maxDepth < 1) {
            throw new IllegalArgumentException("최대 깊이는 1 이상입니다: " + maxDepth + ". 1 이상의 값을 넣어 다시 보내세요.");
        }
        TreeSet<String> starts = new TreeSet<>(CodePointOrder.STRINGS);
        for (String id : startEntityIds) {
            starts.add(Identifiers.requireNotBlank(id, "출발 개체의 식별자"));
        }
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (LocalDate date : asOfDates) {
            Objects.requireNonNull(date, "기준 날짜는 비어 있을 수 없습니다");
            // 받는 범위 밖의 날짜로는 따라가지 않는다 (D5 3절, D2 AD-370).
            dates.add(StorableDates.requireAsOf(date));
        }
        TreeMap<String, ImpactDirection> byType = new TreeMap<>(CodePointOrder.STRINGS);
        for (Map.Entry<String, ImpactDirection> entry : directions.entrySet()) {
            byType.put(Identifiers.requireNotBlank(entry.getKey(), "관계 유형"), Objects.requireNonNull(entry.getValue(),
                    "관계 유형 " + entry.getKey() + " 의 영향 방향은 비어 있을 수 없습니다"));
        }
        startEntityIds = List.copyOf(starts);
        asOfDates = List.copyOf(dates);
        directions = Collections.unmodifiableMap(byType);
    }
}
