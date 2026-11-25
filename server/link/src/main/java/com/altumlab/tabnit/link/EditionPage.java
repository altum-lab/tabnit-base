package com.altumlab.tabnit.link;

import java.util.List;

/**
 * 나눠 준 판의 목록 (D7 2절 "나눠 주기").
 *
 * @param editions 이 쪽의 판. 순서는 나누기 전의 순서 그대로다
 * @param total 나누기 전의 전체 수
 */
public record EditionPage(List<EditionListEntry> editions, int total) {
    public EditionPage {
        editions = List.copyOf(editions);
    }
}
