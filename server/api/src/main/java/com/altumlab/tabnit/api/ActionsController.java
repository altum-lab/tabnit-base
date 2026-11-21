package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.EntityQuery;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.rules.Action;
import com.altumlab.tabnit.rules.ActionRecords;
import com.altumlab.tabnit.rules.ActionStatus;
import com.altumlab.tabnit.rules.StoredImpactScope;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 조치 목록과 변동의 영향 범위를 읽는 API. 규격은 docs/api/openapi.yaml 의 listActions 와 getChangeImpactScope,
 * 규칙은 D7 6절이다.
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 무엇이 조치이고 어떤 순서인지, 영향 대상의 수는 rules 가 정한다.
 */
@RestController
class ActionsController {

	private final ActionRecords actions;

	private final PackDefinitions packs;

	private final EntityQuery entities;

	ActionsController(ActionRecords actions, PackDefinitions packs, EntityQuery entities) {
		this.actions = actions;
		this.packs = packs;
		this.entities = entities;
	}

	@GetMapping("/api/v1/actions")
	ActionListResponse list(@RequestParam(required = false) String packId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String changeEventId) {
		if (packId == null || packId.isBlank()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		}
		ActionStatus wanted = status == null ? null : status(status);
		if (changeEventId != null) {
			// 항목이 있는데 값이 비었으면 거르지 않는 것으로 보지 않는다. 조용히 빈 목록이 되지 않게 알린다.
			requireChangeEventId(changeEventId);
		}
		// NUL 이 든 식별자의 Pack 은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		if (packId.indexOf('\u0000') >= 0 || this.packs.find(packId).isEmpty()) {
			throw new ApiProblemException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "packId",
					"Pack " + packId + " 이 적재되어 있지 않습니다. Pack 의 식별자를 확인하세요.");
		}
		List<Action> found = this.actions.packActions(packId, wanted, changeEventId);
		// 근거가 되는 개체의 유형은 조치에 저장되어 있지 않다. core 에서 한 번에 읽는다. 찾는 데만 쓴다.
		TreeSet<String> basisIds = new TreeSet<>();
		for (Action action : found) {
			basisIds.add(action.basisEntityId());
		}
		Map<String, String> basisTypes = new TreeMap<>();
		for (EntityRef entity : this.entities.findAll(basisIds)) {
			basisTypes.put(entity.id(), entity.entityType());
		}
		return ActionListResponse.of(packId, wanted == null ? null : wanted.name(), changeEventId, found, basisTypes);
	}

	@GetMapping("/api/v1/impact-scopes")
	ChangeImpactScopeResponse impactScope(@RequestParam(required = false) String changeEventId) {
		if (changeEventId == null || changeEventId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_CHANGE_EVENT_ID", "changeEventId",
					"변동 이벤트의 id 가 없습니다. changeEventId 에 변동 이벤트의 id 를 넣어 다시 보내세요.");
		}
		requireChangeEventId(changeEventId);
		StoredImpactScope scope = this.actions.impactScope(changeEventId)
			.orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "IMPACT_SCOPE_NOT_FOUND", "changeEventId",
					"그 변동 이벤트의 저장된 영향 범위가 없습니다. 변동 이벤트의 id 를 확인하세요."
							+ " 아직 처리되지 않았거나 처리에 실패한 변동에는 영향 범위가 없습니다."));
		return ChangeImpactScopeResponse.of(scope);
	}

	private static ActionStatus status(String value) {
		for (ActionStatus candidate : ActionStatus.values()) {
			if (candidate.name().equals(value)) {
				return candidate;
			}
		}
		throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "status",
				"상태의 값이 틀렸습니다. PENDING, CONFIRMED, ON_HOLD 가운데 하나를 넣어 다시 보내세요.");
	}

	/** 변동 이벤트의 식별자가 될 수 없는 값(빈 값, 저장소가 담지 못하는 글자)은 읽기 전에 가린다. 무엇이 되는지는 rules 가 정한다. */
	private static void requireChangeEventId(String changeEventId) {
		if (!ActionRecords.isReadableChangeEventId(changeEventId)) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_CHANGE_EVENT_ID", "changeEventId",
					"변동 이벤트의 id 가 비었거나 쓸 수 없는 글자가 있습니다. 서버가 준 id 를 그대로 넣어 다시 보내세요.");
		}
	}
}
