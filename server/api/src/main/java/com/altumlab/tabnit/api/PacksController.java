package com.altumlab.tabnit.api;

import com.altumlab.tabnit.link.EditionRecords;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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
}
