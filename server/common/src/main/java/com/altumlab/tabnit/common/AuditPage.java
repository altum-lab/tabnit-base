package com.altumlab.tabnit.common;

import java.util.List;

/**
 * 나눠 준 감사 기록의 한 쪽 (D2 AD-414).
 *
 * @param records 이 쪽의 줄. 일련번호의 내림차순이다 (가장 최근 것부터)
 * @param total 나누기 전의 전체 수. 조건에 맞는 줄의 수다
 */
public record AuditPage(List<AuditRecord> records, int total) {

    public AuditPage {
        records = List.copyOf(records);
    }
}
