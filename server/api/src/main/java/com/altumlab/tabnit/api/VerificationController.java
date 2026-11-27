package com.altumlab.tabnit.api;

import com.altumlab.tabnit.pack.PackDefinitions;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 검증 질문을 화면에서 돌리는 API. 규격은 docs/api/openapi.yaml 의 getVerificationResults, 규칙은 D7 10절 "검증 질문을
 * 화면에서 돌린다"다 (카드 E-23, D2 AD-422).
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 관리자만 부른다 (보안 설정이 막는다). 읽기만 한다.
 */
@RestController
class VerificationController {

	static final String PATH = "/api/v1/verification-results";

	private final VerificationRuns runs;

	private final PackDefinitions packs;

	VerificationController(VerificationRuns runs, PackDefinitions packs) {
		this.runs = runs;
		this.packs = packs;
	}

	@GetMapping(PATH)
	@Audited(action = "getVerificationResults", params = "packId")
	VerificationRuns.Results results(@RequestParam(required = false) String packId) {
		if (packId == null || packId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		}
		// NUL 이 든 식별자의 Pack 은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		if (packId.indexOf('\u0000') >= 0 || this.packs.find(packId).isEmpty()) {
			throw new ApiProblemException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "packId",
					"Pack " + packId + " 이 적재되어 있지 않습니다. Pack 의 식별자를 확인하세요.");
		}
		// 질문마다 부르는 조회가 이 요청의 감사 기록(대상, 조회 시점)을 바꾸지 못하게 한다: 이 요청 하나만 남는다.
		return AuditTrail.keeping(() -> this.runs.run(packId));
	}
}
