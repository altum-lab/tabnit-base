package com.altumlab.tabnit.link;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 판: 받아들여진 투입 하나 (D5 5절 edition). 원본의 내용은 따로 읽는다 ({@link EditionRecords#originalContent}).
 *
 * @param id 판의 식별자. (Pack, 입력 자료, 판 번호)를 이은 글자
 * @param editionNo 그 입력 자료 안에서의 판 번호. 1 부터
 * @param contentHash 받은 파일의 바이트의 SHA-256
 * @param fileName 받은 파일의 이름. 없을 수 있다
 * @param receivedAt 받은 때
 */
public record Edition(String id, String packId, String inputSourceId, int editionNo, LocalDate effectiveDate,
        String contentHash, String fileName, Instant receivedAt) {
}
