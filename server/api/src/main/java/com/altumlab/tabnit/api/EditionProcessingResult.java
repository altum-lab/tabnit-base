package com.altumlab.tabnit.api;

import com.altumlab.tabnit.link.IngestResult;
import java.util.List;

/**
 * 판 하나를 투입하고 변동을 처리한 결과.
 *
 * @param ingest 투입의 결과. 받아들였는가, 내용이 같았는가, 거부했는가
 * @param changes 이 호출이 처리한 변동 이벤트. 처리한 차례다 (D2 AD-207). 앞서 실패해 남아 있던 것도 들 수 있다
 */
record EditionProcessingResult(IngestResult ingest, List<ProcessedChange> changes) {

	EditionProcessingResult {
		changes = List.copyOf(changes);
	}
}
