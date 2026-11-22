package com.altumlab.tabnit.api;

import com.altumlab.tabnit.pack.PackLoadResult;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pack 을 적재하는 API. 규격은 docs/api/openapi.yaml 의 loadPack, 규칙은 D7 10절이다.
 *
 * <p>관리자만 부를 수 있다. 그 확인은 보안 설정이 요청을 받기 전에 한다 (D7 2절).
 * 받고, 넘기고, 돌려준다. 읽고 검증하고 저장하는 것은 pack 이 한다.
 */
@RestController
class PackLoadsController {

	static final String PATH = "/api/v1/pack-loads";

	private final PackLoading loading;

	PackLoadsController(PackLoading loading) {
		this.loading = loading;
	}

	@PostMapping(PATH)
	@Audited(action = "loadPack")
	PackLoadResponse load(@RequestBody Map<String, Object> request) {
		Object packId = request.get("packId");
		if (packId == null || "".equals(packId)) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"적재할 Pack 의 이름이 없습니다. packId 에 서버의 Pack 폴더에 있는 폴더의 이름을 넣어 다시 보내세요.");
		}
		// 글자가 아닌 값(수, 목록)은 글자로 바꿔 받지 않는다. 이름으로 받을 수 없는 값이다.
		String name = packId instanceof String text ? text : null;
		AuditTrail.target("packId", name);
		PackLoadResult result = this.loading.load(name);
		AuditTrail.detail("outcome", result.outcome().name());
		if (!result.warnings().isEmpty()) {
			// 감사 기록에는 경고의 수만 남긴다. 경고의 문구는 응답으로 간다.
			AuditTrail.detail("warnings", Integer.toString(result.warnings().size()));
		}
		return new PackLoadResponse(result.packId(), result.outcome().name(),
				result.warnings()
					.stream()
					.map(warning -> new PackLoadResponse.Warning(warning.file(), warning.item(), warning.message()))
					.toList());
	}

}
