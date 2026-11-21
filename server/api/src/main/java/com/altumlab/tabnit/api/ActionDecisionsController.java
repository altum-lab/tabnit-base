package com.altumlab.tabnit.api;

import com.altumlab.tabnit.rules.ActionStatus;
import com.altumlab.tabnit.rules.ActionWithHistory;
import com.altumlab.tabnit.rules.ActionDecisionInputException;
import com.altumlab.tabnit.rules.ActionDecisionRejectedException;
import com.altumlab.tabnit.rules.ActionDecisions;
import com.altumlab.tabnit.rules.ActionNotFoundException;
import com.altumlab.tabnit.rules.ActionRecords;
import java.time.Clock;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 조치의 확정과 보류, 조치 하나와 그 이력을 읽는 API. 규격은 docs/api/openapi.yaml 의 decideAction 과 getAction,
 * 규칙은 D7 7절이다.
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 어떤 바뀜이 되는지, 사유가 맞는지는 rules 가 정한다.
 *
 * <p>누가 했는지와 언제 했는지는 요청에서 받지 않는다 (D2 AD-251). 언제는 서버의 시계에서 읽는다. 누가는 로그인한
 * 사람의 아이디다.
 * 확정과 보류는 트랜잭션 밖에서 부른다: rules 가 자기 트랜잭션을 연다.
 */
@RestController
class ActionDecisionsController {

	private final ActionDecisions decisions;

	private final ActionRecords records;

	private final ActionViews views;

	private final Clock clock;

	ActionDecisionsController(ActionDecisions decisions, ActionRecords records, ActionViews views, Clock clock) {
		this.decisions = decisions;
		this.records = records;
		this.views = views;
		this.clock = clock;
	}

	@GetMapping("/api/v1/action")
	@Audited(action = "getAction", params = "actionId")
	ActionDetailResponse get(@RequestParam(required = false) String actionId) {
		requireActionId(actionId);
		// 조치와 이력은 rules 가 한 시점의 것으로 함께 읽어 준다.
		return detail(this.records.detail(actionId).orElseThrow(ActionDecisionsController::notFound));
	}

	@PostMapping("/api/v1/action-decisions")
	@Audited(action = "decideAction")
	ActionDetailResponse decide(@RequestBody Map<String, Object> request, Authentication authentication) {
		if (authentication == null) {
			// 로그인하지 않은 요청은 여기까지 오지 않는다. 왔다면 서버 쪽의 문제다. 누가 했는지 없이 이력을 남기지 않는다.
			throw new IllegalStateException("로그인한 사람 없이 확정이나 보류의 요청이 들어왔습니다");
		}
		String actor = authentication.getName();
		// 항목의 형식은 여기서 본다. 글자가 아닌 값(수, 참거짓, 객체)을 글자로 바꿔 받지 않는다.
		String actionId = text(request.get("actionId"), ActionDecisionsController::invalidActionId);
		// 어느 조치에 무엇을 하려 했는지를 감사 기록에 남긴다. 사유는 남기지 않는다.
		AuditTrail.target("actionId", actionId);
		requireActionId(actionId);
		String decision = text(request.get("decision"), ActionDecisionsController::invalidDecision);
		if (decision == null || decision.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_DECISION", "decision",
					"무엇을 할지가 없습니다. decision 에 CONFIRM (확정) 또는 HOLD (보류)를 넣어 다시 보내세요.");
		}
		boolean confirm = decision.equals("CONFIRM");
		if (!confirm && !decision.equals("HOLD")) {
			throw invalidDecision();
		}
		AuditTrail.detail("decision", decision);
		String note = text(request.get("note"), () -> new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_NOTE", "note",
				"사유는 글자로 적어야 합니다. 사유를 글자로 적어 다시 보내세요."));
		try {
			return detail(confirm ? this.decisions.confirm(actionId, actor, note, this.clock.instant())
					: this.decisions.hold(actionId, actor, note, this.clock.instant()));
		}
		catch (ActionNotFoundException e) {
			throw notFound();
		}
		catch (ActionDecisionRejectedException e) {
			// 지금의 상태와 마지막 이력은 rules 가 같은 때에 읽어 준 것이다. 누가 먼저 했는지는 화면이 마지막 이력으로
			// 보여 준다 (D2 AD-253). 자기가 한 것을 다시 보낸 경우는 여기로 오지 않는다: rules 가 성공으로 답한다 (D2 AD-325).
			throw new ActionDecisionConflictException(e.currentStatus(), e.lastDecision(),
					"이 조치는 지금 " + label(e.currentStatus()) + " 상태라 " + label(e.requestedStatus())
							+ "(으)로 바꿀 수 없습니다. 대기인 조치는 확정하거나 보류할 수 있고, 보류인 조치는 확정할 수 있습니다."
							+ " 확정된 조치는 바꿀 수 없습니다. 목록을 새로 고쳐 지금의 상태를 확인하세요.");
		}
		catch (ActionDecisionInputException e) {
			if (e.field() == ActionDecisionInputException.Field.NOTE) {
				// 사유가 틀렸다 (보류인데 없음, 너무 김, 쓸 수 없는 글자). 문구는 rules 가 쓴 사용자 문구다. 요청의 값은 담기지 않는다.
				throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_NOTE", "note", e.getMessage());
			}
			if (e.field() == ActionDecisionInputException.Field.ACTION_ID) {
				throw invalidActionId();
			}
			// 누가와 언제는 서버가 채운 값이다. 틀렸다면 요청이 아니라 서버 쪽의 문제다.
			throw new IllegalStateException("서버가 채운 값을 rules 가 받지 않았습니다: " + e.field(), e);
		}
	}

	private ActionDetailResponse detail(ActionWithHistory found) {
		return ActionDetailResponse.of(this.views.item(found.action()), found.history());
	}

	/** 항목의 값을 글자로 읽는다. 없거나 null 이면 null. 글자가 아니면 그 항목의 오류다. */
	private static String text(Object value, Supplier<ApiProblemException> wrongType) {
		if (value == null) {
			return null;
		}
		if (value instanceof String text) {
			return text;
		}
		throw wrongType.get();
	}

	/** 화면에 보일 상태의 이름. */
	private static String label(ActionStatus status) {
		return switch (status) {
			case PENDING -> "대기";
			case CONFIRMED -> "확정";
			case ON_HOLD -> "보류";
		};
	}

	private static ApiProblemException invalidDecision() {
		return new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_DECISION", "decision",
				"무엇을 할지의 값이 틀렸습니다. CONFIRM (확정) 또는 HOLD (보류)를 넣어 다시 보내세요.");
	}

	private static void requireActionId(String actionId) {
		if (actionId == null || actionId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_ACTION_ID", "actionId",
					"조치의 id 가 없습니다. actionId 에 조치의 id 를 넣어 다시 보내세요.");
		}
		// 무엇이 조치의 식별자가 될 수 있는지는 rules 가 정한다.
		if (!ActionRecords.isReadableActionId(actionId)) {
			throw invalidActionId();
		}
	}

	private static ApiProblemException invalidActionId() {
		return new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_ACTION_ID", "actionId",
				"조치의 id 에 쓸 수 없는 글자가 있습니다. 서버가 준 id 를 그대로 넣어 다시 보내세요.");
	}

	private static ApiProblemException notFound() {
		// 요청의 값을 문구에 되돌려 담지 않는다. 길이의 한도가 없고 줄바꿈이 들 수 있다.
		return new ApiProblemException(HttpStatus.NOT_FOUND, "ACTION_NOT_FOUND", "actionId",
				"그런 조치가 없습니다. 조치의 id 를 확인하세요.");
	}
}
