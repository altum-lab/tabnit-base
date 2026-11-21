package com.altumlab.tabnit.api;

import com.altumlab.tabnit.link.ChangeEvent;
import com.altumlab.tabnit.link.IngestResult;
import java.util.List;

/**
 * 판 하나를 투입하고 변동을 처리한 결과.
 *
 * @param ingest 투입의 결과. 받아들였는가, 내용이 같았는가, 거부했는가
 * @param changes 이 호출이 처리한 변동 이벤트. 처리한 차례다 (D2 AD-207). 앞서 실패해 남아 있던 것도 들 수 있다
 * @param editionChanges 투입이 만든 판의 변동 이벤트. 처리를 마친 직후, 잠금을 놓기 전에 읽은 것이다. 항목의 키의
 *        글자 코드 순이다. 판을 만들지 않았으면 비어 있다
 * @param processingNotStarted 판은 받아들였지만 변동의 처리를 시작하지 못했는가. 변동은 대기로 남아 있고,
 *        다음에 받아들여진 투입이나 남은 변동의 처리에서 처리된다
 */
record EditionProcessingResult(IngestResult ingest, List<ProcessedChange> changes, boolean processingNotStarted,
		List<ChangeEvent> editionChanges) {

	EditionProcessingResult {
		changes = List.copyOf(changes);
		editionChanges = List.copyOf(editionChanges);
	}
}
