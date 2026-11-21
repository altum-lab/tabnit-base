package com.altumlab.tabnit.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Pack 과 입력 자료의 목록의 응답 (docs/api/openapi.yaml 의 PackList, D7 8절).
 *
 * <p>목록의 순서는 pack 이 준 순서 그대로다.
 */
record PackListResponse(List<Pack> packs) {

	record Pack(String packId, String label, List<InputSource> inputSources) {
	}

	record InputSource(String inputSourceId, String fileFormat, Edition latestEdition) {
	}

	record Edition(String editionId, int editionNo, LocalDate effectiveDate) {
	}
}
