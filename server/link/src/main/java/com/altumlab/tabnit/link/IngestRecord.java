package com.altumlab.tabnit.link;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 투입 기록 하나 (D5 5절 ingest_record). 받아들여졌든 거부됐든 투입마다 하나씩 남는다 (D2 AD-152).
 *
 * @param id 일련번호. 데이터베이스가 매긴다
 * @param editionId 만든 판, 또는 내용이 같았던 앞 판. 거부했으면 null
 * @param reasonCode 거부한 이유의 코드. 거부하지 않았으면 null
 */
public record IngestRecord(long id, String packId, String inputSourceId, LocalDate effectiveDate, String contentHash,
        String fileName, Instant receivedAt, IngestOutcome outcome, String editionId, String reasonCode,
        String reasonDetail) {
}
