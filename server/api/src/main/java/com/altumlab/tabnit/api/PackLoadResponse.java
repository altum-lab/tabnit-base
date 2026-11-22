package com.altumlab.tabnit.api;

import java.util.List;

/**
 * Pack 을 적재한 결과. 규격의 PackLoad.
 *
 * @param warnings 경고. 적재를 막지 않는다. pack 이 준 순서 그대로다. 없으면 빈 목록 (D2 AD-323)
 */
record PackLoadResponse(String packId, String outcome, List<Warning> warnings) {

	/** 경고 하나. 규격의 PackWarning. */
	record Warning(String file, String item, String message) {
	}
}
