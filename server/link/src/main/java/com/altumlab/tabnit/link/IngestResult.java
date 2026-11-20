package com.altumlab.tabnit.link;

/**
 * 투입 하나의 결과.
 *
 * @param outcome 받아들였는가, 앞 판과 같았는가, 거부했는가
 * @param editionId 만든 판, 또는 내용이 같았던 앞 판. 거부했으면 null
 * @param reasonCode 거부한 이유의 코드. 거부하지 않았으면 null
 * @param reasonDetail 거부한 이유의 값. 없으면 null
 * @param mapping 매핑 실행의 결과 (받아들인 항목과 알림). 판을 만들었을 때만 있다
 */
public record IngestResult(IngestOutcome outcome, String editionId, String reasonCode, String reasonDetail,
        MappingOutcome mapping) {
}
