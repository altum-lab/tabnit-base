package com.altumlab.tabnit.api;

import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.rules.Action;
import com.altumlab.tabnit.rules.ActionPage;
import com.altumlab.tabnit.rules.ActionRecords;
import com.altumlab.tabnit.rules.ActionStatus;
import com.altumlab.tabnit.rules.StoredImpactScope;
import com.altumlab.tabnit.rules.StoredImpactScopePage;
import com.altumlab.tabnit.rules.StoredPathStep;
import com.altumlab.tabnit.rules.StoredImpactTarget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

	private final ActionViews views;

	private final EntityViews entityViews;

	private final ListExports exports;

	ActionsController(ActionRecords actions, PackDefinitions packs, ActionViews views, EntityViews entityViews,
			ListExports exports) {
		this.exports = exports;
		this.entityViews = entityViews;
		this.actions = actions;
		this.packs = packs;
		this.views = views;
	}

	@GetMapping("/api/v1/actions")
	@Audited(action = "listActions", params = { "packId", "status", "changeEventId" })
	ActionListResponse list(@RequestParam(required = false) String packId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String changeEventId, @RequestParam(required = false) String offset,
			@RequestParam(required = false) String limit) {
		if (packId == null || packId.isBlank()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		}
		ActionStatus wanted = status == null ? null : status(status);
		PageRequest page = PageRequest.of(offset, limit);
		return readActions(packId, wanted, changeEventId, page, page);
	}

	/**
	 * 조치의 목록을 파일로 내보낸다 (규격의 exportActions, D7 "목록 내보내기", D2 AD-412). 거르는 인자와 순서는 위의
	 * 목록과 같고 나누지 않는다.
	 */
	@GetMapping("/api/v1/action-exports")
	@Audited(action = "exportActions", params = { "packId", "status", "changeEventId", "format" })
	ResponseEntity<Object> exportActions(@RequestParam(required = false) String packId,
			@RequestParam(required = false) String status, @RequestParam(required = false) String changeEventId,
			@RequestParam(required = false) String format) {
		if (packId == null || packId.isBlank()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		}
		ActionStatus wanted = status == null ? null : status(status);
		ListExports.Format file = ListExports.format(format);
		ActionListResponse all = readActions(packId, wanted, changeEventId, this.exports.whole(), this.exports.answered());
		this.exports.requireWithin(all.total());
		if (file == ListExports.Format.JSON) {
			return ListExports.json("actions", all);
		}
		List<List<String>> rows = new ArrayList<>();
		for (ActionListResponse.Item action : all.actions()) {
			rows.add(Arrays.asList(action.id(), action.changeEventId(), action.ruleNo(), action.ruleLabel(),
					action.actionText(), String.valueOf(action.priority()), action.dueDate().toString(), action.status(),
					action.target().entityId(), action.target().entityTypeLabel(), action.target().entityKey(),
					action.target().displayName(), action.basis().entity().entityId(),
					action.basis().entity().entityTypeLabel(), action.basis().entity().entityKey(),
					action.basis().entity().displayName(), action.basis().inputSourceLabel(),
					String.valueOf(action.basis().editionNo()), action.basis().effectiveDate().toString(),
					action.createdAt().toString()));
		}
		return ListExports.csv("actions", ACTION_COLUMNS, rows);
	}

	/** 조치의 CSV 의 머리 행. 조치에는 조회 시점이 없다: 근거가 된 판의 적용일을 적는다. */
	private static final List<String> ACTION_COLUMNS = List.of("조치 id", "변동 이벤트 id", "규칙 번호", "규칙 이름", "할 일",
			"우선순위", "기한", "상태", "대상 개체 id", "대상 유형", "대상 키", "대상 이름", "근거 개체 id", "근거 유형", "근거 키",
			"근거 이름", "근거 입력 자료", "근거 판", "근거 판의 적용일", "만든 때");

	/** 조치를 읽어 응답의 모양으로 옮긴다. 목록과 내보내기가 함께 쓴다. */
	private ActionListResponse readActions(String packId, ActionStatus wanted, String changeEventId, PageRequest page,
			PageRequest answered) {
		if (changeEventId != null) {
			// 항목이 있는데 값이 비었으면 거르지 않는 것으로 보지 않는다. 조용히 빈 목록이 되지 않게 알린다.
			requireChangeEventId(changeEventId);
		}
		// NUL 이 든 식별자의 Pack 은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		if (packId.indexOf('\u0000') >= 0 || this.packs.find(packId).isEmpty()) {
			throw new ApiProblemException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "packId",
					"Pack " + packId + " 이 적재되어 있지 않습니다. Pack 의 식별자를 확인하세요.");
		}
		// 나누는 일은 rules 가 한다 (D2 AD-347).
		ActionPage found = this.actions.packActions(packId, wanted, changeEventId, page.offset(), page.limit());
		return ActionListResponse.of(packId, wanted == null ? null : wanted.name(), changeEventId, found.total(), answered,
				this.views.items(found.actions()));
	}

	@GetMapping("/api/v1/impact-scopes")
	@Audited(action = "getChangeImpactScope", params = "changeEventId")
	ChangeImpactScopeResponse impactScope(@RequestParam(required = false) String changeEventId,
			@RequestParam(required = false) String entityId, @RequestParam(required = false) String offset,
			@RequestParam(required = false) String limit) {
		if (changeEventId == null || changeEventId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_CHANGE_EVENT_ID", "changeEventId",
					"변동 이벤트의 id 가 없습니다. changeEventId 에 변동 이벤트의 id 를 넣어 다시 보내세요.");
		}
		requireChangeEventId(changeEventId);
		if (entityId != null) {
			// 항목이 있는데 값이 비었으면 거르지 않는 것으로 보지 않는다. 쓸 수 없는 글자가 든 개체는 있을 수 없다.
			if (entityId.isEmpty()) {
				throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_ENTITY_ID", "entityId",
						"개체의 id 가 비어 있습니다. entityId 에 개체의 id 를 넣거나 항목을 빼고 다시 보내세요.");
			}
			if (!ActionRecords.isReadableEntityId(entityId)) {
				throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_ENTITY_ID", "entityId",
						"개체의 id 에 쓸 수 없는 글자가 있습니다. 서버가 준 id 를 그대로 넣어 다시 보내세요.");
			}
		}
		PageRequest page = PageRequest.of(offset, limit);
		return readScope(changeEventId, entityId, page, page);
	}

	/**
	 * 변동의 영향 대상을 파일로 내보낸다 (규격의 exportImpactTargets, D7 "목록 내보내기", D2 AD-412). 순서는 영향 범위의
	 * 영향 대상과 같고 나누지 않는다. 대상마다 근거 경로를 함께 적는다.
	 */
	@GetMapping("/api/v1/impact-target-exports")
	@Audited(action = "exportImpactTargets", params = { "changeEventId", "format" })
	ResponseEntity<Object> exportImpactTargets(@RequestParam(required = false) String changeEventId,
			@RequestParam(required = false) String format) {
		if (changeEventId == null || changeEventId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_CHANGE_EVENT_ID", "changeEventId",
					"변동 이벤트의 id 가 없습니다. changeEventId 에 변동 이벤트의 id 를 넣어 다시 보내세요.");
		}
		requireChangeEventId(changeEventId);
		ListExports.Format file = ListExports.format(format);
		ChangeImpactScopeResponse all = readScope(changeEventId, null, this.exports.whole(), this.exports.answered());
		this.exports.requireWithin(all.total());
		String name = "impact-targets-" + all.asOfEffective();
		if (file == ListExports.Format.JSON) {
			return ListExports.json(name, all);
		}
		List<List<String>> rows = new ArrayList<>();
		for (ChangeImpactScopeResponse.Target target : all.targets()) {
			rows.add(Arrays.asList(all.changeEventId(), all.asOfPrevious().toString(), all.asOfEffective().toString(),
					target.entityId(), target.entity().entityTypeLabel(), target.entity().entityKey(),
					target.entity().displayName(), String.valueOf(target.depth()), target.path().startEntityId(),
					target.path().asOf().toString(), pathText(target.path()), String.valueOf(all.maxDepth()),
					all.truncated() ? "예" : "아니오"));
		}
		return ListExports.csv(name, IMPACT_TARGET_COLUMNS, rows);
	}

	/** 영향 대상의 CSV 의 머리 행. 영향 범위는 판의 적용일 전날과 적용일 두 날짜로 본 것이다 (D8). */
	private static final List<String> IMPACT_TARGET_COLUMNS = List.of("변동 이벤트 id", "적용일 전날", "적용일", "영향 대상 id",
			"유형", "키", "이름", "깊이", "경로가 시작한 개체 id", "경로의 날짜", "근거 경로", "최대 깊이", "최대 깊이에서 잘림");

	/** 근거 경로를 한 칸의 글로 적는다: 단계마다 "출발 id -[관계 유형]-> 도착 id" 를 단계의 차례대로 잇는다. */
	private static String pathText(ChangeImpactScopeResponse.Path path) {
		StringBuilder text = new StringBuilder();
		for (ChangeImpactScopeResponse.Step step : path.steps()) {
			if (!text.isEmpty()) {
				text.append(" ; ");
			}
			text.append(step.sourceEntityId()).append(" -[").append(step.relationType()).append("]-> ")
				.append(step.targetEntityId());
		}
		return text.toString();
	}

	/** 영향 범위를 읽어 응답의 모양으로 옮긴다. 영향 범위의 API 와 내보내기가 함께 쓴다. */
	private ChangeImpactScopeResponse readScope(String changeEventId, String entityId, PageRequest page,
			PageRequest answered) {
		// 나누는 일과 대상 하나를 찾는 일은 rules 가 한다 (D2 AD-347).
		StoredImpactScopePage scope = this.actions.impactScopePage(changeEventId, entityId, page.offset(), page.limit())
			.orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "IMPACT_SCOPE_NOT_FOUND", "changeEventId",
					"그 변동 이벤트의 저장된 영향 범위가 없습니다. 변동 이벤트의 id 를 확인하세요."
							+ " 아직 처리되지 않았거나 처리에 실패한 변동에는 영향 범위가 없습니다."));
		// 이 쪽의 영향 대상과 그 근거 경로에 나오는 개체를 한 번에 읽는다. 표시 이름은 판의 적용일의 값이다 (D2 AD-349).
		TreeSet<String> ids = new TreeSet<>();
		for (StoredImpactTarget target : scope.scope().targets()) {
			ids.add(target.entityId());
			ids.add(target.pathStartEntityId());
			for (StoredPathStep step : target.steps()) {
				ids.add(step.sourceEntityId());
				ids.add(step.targetEntityId());
			}
		}
		return ChangeImpactScopeResponse.of(scope, answered, this.entityViews.items(ids, scope.scope().asOfEffective()));
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
