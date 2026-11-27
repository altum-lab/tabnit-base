package com.altumlab.tabnit.api;

import com.altumlab.tabnit.link.ChangeEventPage;
import com.altumlab.tabnit.link.ChangeEventStatus;
import com.altumlab.tabnit.link.ChangeEventSummary;
import com.altumlab.tabnit.link.ChangeEvents;
import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.rules.ActionRecords;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pack 의 변동 이벤트의 목록을 주는 API (규격의 listChangeEvents, D7 5절 "변동 이벤트의 목록", D2 AD-417).
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 어느 변동이 어떤 순서로 나오는지와 나누는 일은 link 가 한다. 변동마다의 조치의 수와
 * 영향 대상의 수는 rules 가 센다. 여기서는 그것을 한 줄에 모으고 주 개체에 유형, 키, 표시 이름을 붙인다.
 * 다시 세거나 차례를 바꾸지 않는다.
 */
@RestController
class ChangeEventsController {

	private final ChangeEvents changeEvents;

	private final ActionRecords actions;

	private final PackDefinitions packs;

	private final EntityViews entityViews;

	ChangeEventsController(ChangeEvents changeEvents, ActionRecords actions, PackDefinitions packs, EntityViews entityViews) {
		this.changeEvents = changeEvents;
		this.actions = actions;
		this.packs = packs;
		this.entityViews = entityViews;
	}

	/** 규격의 ChangeEventList. */
	record ListResponse(String packId, String status, int total, int offset, int limit, List<Item> changes) {
	}

	/**
	 * 규격의 ChangeEventListItem.
	 *
	 * @param actionCount 그 변동에 저장된 조치의 수. 상태를 가리지 않고 센다
	 * @param impactTargetCount 저장된 영향 범위의 영향 대상의 수. 영향 범위가 없으면 (대기, 어느 단계에서든 실패) null 이다:
	 *        0 은 계산했더니 영향 대상이 없다는 뜻이다
	 */
	record Item(String changeEventId, String editionId, String inputSourceId, String inputSourceLabel, int editionNo,
			LocalDate effectiveDate, String itemKey, String kind, String primaryEntityId, EntityViews.Item primaryEntity,
			String status, String failedStage, String failureCode, int actionCount, Integer impactTargetCount) {
	}

	@GetMapping("/api/v1/change-events")
	@Audited(action = "listChangeEvents", params = { "packId", "status" })
	ListResponse list(@RequestParam(required = false) String packId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String offset, @RequestParam(required = false) String limit) {
		if (packId == null || packId.isBlank()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		}
		ChangeEventStatus wanted = status == null ? null : status(status);
		PageRequest page = PageRequest.of(offset, limit);
		// NUL 이 든 식별자의 Pack 은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		if (packId.indexOf('\u0000') >= 0 || this.packs.find(packId).isEmpty()) {
			throw new ApiProblemException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "packId",
					"Pack " + packId + " 이 적재되어 있지 않습니다. Pack 의 식별자를 확인하세요.");
		}
		// 나누는 일은 link 가 한다 (D2 AD-347).
		ChangeEventPage found = this.changeEvents.packChangeEvents(packId, wanted, page.offset(), page.limit());
		List<String> ids = found.events().stream().map(ChangeEventSummary::id).toList();
		// 이 쪽의 변동의 수를 한 번에 읽는다 (D2 AD-385). 찾는 데만 쓴다.
		Map<String, Integer> actionCounts = this.actions.actionCounts(packId, ids);
		Map<String, Integer> targetCounts = this.actions.impactTargetCounts(ids);
		// 주 개체의 표시 이름은 그 변동이 나온 판의 적용일의 값이다 (D2 AD-381). 적용일마다 한 번씩 읽는다.
		Map<LocalDate, TreeSet<String>> idsByDate = new TreeMap<>();
		for (ChangeEventSummary event : found.events()) {
			idsByDate.computeIfAbsent(event.effectiveDate(), date -> new TreeSet<>()).add(event.primaryEntityId());
		}
		Map<LocalDate, Map<String, EntityViews.Item>> entitiesByDate = new TreeMap<>();
		idsByDate.forEach((date, entityIds) -> entitiesByDate.put(date, this.entityViews.items(entityIds, date)));
		InputSourceLabels labels = this.entityViews.inputSourceLabels();
		List<Item> items = new ArrayList<>();
		for (ChangeEventSummary event : found.events()) {
			items.add(new Item(event.id(), event.editionId(), event.inputSourceId(),
					labels.label(packId, event.inputSourceId()), event.editionNo(), event.effectiveDate(), event.itemKey(),
					event.kind().name(), event.primaryEntityId(),
					EntityViews.require(entitiesByDate.get(event.effectiveDate()), event.primaryEntityId(), "주 개체"),
					event.status().name(), event.failedStage(), event.failureCode(),
					actionCounts.getOrDefault(event.id(), 0), targetCounts.get(event.id())));
		}
		return new ListResponse(packId, wanted == null ? null : wanted.name(), found.total(), page.offset(), page.limit(),
				items);
	}

	private static ChangeEventStatus status(String value) {
		for (ChangeEventStatus candidate : ChangeEventStatus.values()) {
			if (candidate.name().equals(value)) {
				return candidate;
			}
		}
		throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "status",
				"상태의 값이 틀렸습니다. PENDING, PROCESSED, FAILED 가운데 하나를 넣어 다시 보내세요.");
	}
}
