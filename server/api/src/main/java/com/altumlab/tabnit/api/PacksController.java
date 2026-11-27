package com.altumlab.tabnit.api;

import com.altumlab.tabnit.link.EditionRecords;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pack 과 입력 자료의 목록을 주는 API. 규격은 docs/api/openapi.yaml 의 listPacks, 규칙은 D7 8절이다.
 *
 * <p>받고, 넘기고, 돌려준다. 어떤 Pack 이 있고 어떤 입력 자료를 받는지는 pack 이, 가장 새 판이 무엇인지는 link 가 정한다.
 * 순서는 두 모듈이 준 그대로다.
 */
@RestController
class PacksController {

	private final PackDefinitions packs;

	private final EditionRecords editions;

	PacksController(PackDefinitions packs, EditionRecords editions) {
		this.packs = packs;
		this.editions = editions;
	}

	@GetMapping("/api/v1/packs")
	@Audited(action = "listPacks")
	PackListResponse list() {
		List<PackListResponse.Pack> found = new ArrayList<>();
		for (String packId : this.packs.packIds()) {
			// 목록을 읽은 뒤 그 Pack 이 없어지는 일은 없다 (Pack 을 지우는 기능이 없다). 그래도 없으면 건너뛴다.
			PackDefinition pack = this.packs.find(packId).orElse(null);
			if (pack == null) {
				continue;
			}
			List<PackListResponse.InputSource> sources = new ArrayList<>();
			for (MappingDefinition mapping : pack.mappings()) {
				PackListResponse.Edition latest = this.editions.latestEdition(packId, mapping.inputSourceId())
					.map(edition -> new PackListResponse.Edition(edition.id(), edition.editionNo(), edition.effectiveDate()))
					.orElse(null);
				sources.add(new PackListResponse.InputSource(mapping.inputSourceId(), mapping.shownLabel(), mapping.fileFormat().name(), latest));
			}
			found.add(new PackListResponse.Pack(pack.packId(), pack.label(), sources));
		}
		return new PackListResponse(found);
	}

	/**
	 * 적재된 Pack 하나의 정의를 읽는다 (규격의 getPackDefinition, D7 8절 "Pack 의 정의", D2 AD-415). 읽기만 한다:
	 * 정의를 고치는 API 는 없다. 고치려면 Pack 의 파일을 고쳐 다시 적재한다.
	 */
	@GetMapping("/api/v1/pack-definition")
	@Audited(action = "getPackDefinition", params = "packId")
	PackDefinitionResponse definition(@RequestParam(required = false) String packId) {
		if (packId == null || packId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		}
		// NUL 이 든 식별자의 Pack 은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		Optional<PackDefinition> found = packId.indexOf('\u0000') >= 0 ? Optional.empty() : this.packs.find(packId);
		return PackDefinitionResponse.of(found.orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
				"PACK_NOT_FOUND", "packId", "Pack " + packId + " 이 적재되어 있지 않습니다. Pack 의 식별자를 확인하세요.")));
	}
}
