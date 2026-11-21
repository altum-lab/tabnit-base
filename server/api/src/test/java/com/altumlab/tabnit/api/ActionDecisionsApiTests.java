package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.rules.Action;
import com.altumlab.tabnit.rules.ActionDecision;
import com.altumlab.tabnit.rules.ActionRecords;
import com.altumlab.tabnit.rules.ActionStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-07 (서버 쪽): 확정과 보류의 API 와 조치 하나를 읽는 API 가 규격(docs/api/openapi.yaml 의 decideAction,
 * getAction)대로 답한다.
 *
 * <p>D7 7절: 확정하거나 보류하면 상태와 이력이 바뀐다. 누가 했는지와 언제 했는지는 요청에서 받지 않고 서버가 채운다.
 * 로그인이 붙기 전에는 설치 설정의 임시 아이디를 적는다: 이 시험에서는 그 값을 따로 정해 설정에서 오는지 본다.
 * 시험용 Pack 의 사본에 판을 올려 만든 조치를 쓴다. 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "tabnit.interim-actor=임시 담당자")
class ActionDecisionsApiTests {

	private static final String ACTOR = "임시 담당자";

	private static final String[][] EDITIONS = { { "program", "program-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v1.csv", "2026-01-01" },
			{ "guideline-program", "guideline-program-v1.csv", "2026-01-01" },
			{ "partner-health", "partner-health-v1.csv", "2026-01-01" },
			{ "partner-private", "partner-private-v1.csv", "2026-01-01" }, { "team", "team-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v2.csv", "2026-03-01" } };

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@Autowired
	EditionProcessing processing;

	/** 서버의 시계. 확정한 때가 여기서 오는지 본다. */
	@Autowired
	java.time.Clock clock;

	@TempDir
	Path temp;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
	}

	// ── 수용 기준: 확정하면 상태와 이력이 바뀐다 ────────────────────────

	@Test
	void 보류한_뒤_확정하면_상태가_바뀌고_이력에_서버가_채운_누가와_언제가_남는다() throws Exception {
		List<Action> actions = prepare("dec-flow");
		Action action = actions.get(0);
		ActionRecords records = new ActionRecords(this.dataSource);

		// 아직 아무도 바꾸지 않은 조치.
		this.mvc.perform(get("/api/v1/action").param("actionId", action.id()))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("application/json"))
			.andExpect(jsonPath("$.action.id").value(action.id()))
			.andExpect(jsonPath("$.action.status").value("PENDING"))
			.andExpect(jsonPath("$.action.ruleNo").value(action.ruleNo()))
			.andExpect(jsonPath("$.action.target.entityId").value(action.targetEntityId()))
			.andExpect(jsonPath("$.action.basis.entity.entityId").value(action.basisEntityId()))
			.andExpect(jsonPath("$.action.basis.entity.entityType").isString())
			.andExpect(jsonPath("$.history", hasSize(0)));

		Instant before = this.clock.instant().minusSeconds(1);
		// 요청에 누가와 언제를 넣어 보내도 쓰지 않는다.
		decide("""
				{ "actionId": %s, "decision": "HOLD", "note": "담당 부서의 답을 기다린다",
				  "decidedBy": "남의 이름", "decidedAt": "2000-01-01T00:00:00Z" }
				""".formatted(json(action.id()))).andExpect(status().isOk())
			.andExpect(jsonPath("$.action.id").value(action.id()))
			.andExpect(jsonPath("$.action.status").value("ON_HOLD"))
			.andExpect(jsonPath("$.history", hasSize(1)))
			.andExpect(jsonPath("$.history[0].seq").value(1))
			.andExpect(jsonPath("$.history[0].fromStatus").value("PENDING"))
			.andExpect(jsonPath("$.history[0].toStatus").value("ON_HOLD"))
			.andExpect(jsonPath("$.history[0].decidedBy").value(ACTOR))
			.andExpect(jsonPath("$.history[0].note").value("담당 부서의 답을 기다린다"));
		// 저장된 이력의 누가는 본문에 적어 보낸 이름이 아니라 서버가 채운 값이다.
		assertThat(new ActionRecords(this.dataSource).history(action.id())).extracting(ActionDecision::decidedBy)
			.containsExactly(ACTOR);
		decide("""
				{ "actionId": %s, "decision": "CONFIRM" }
				""".formatted(json(action.id()))).andExpect(status().isOk())
			.andExpect(jsonPath("$.action.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.history", hasSize(2)))
			.andExpect(jsonPath("$.history[1].seq").value(2))
			.andExpect(jsonPath("$.history[1].fromStatus").value("ON_HOLD"))
			.andExpect(jsonPath("$.history[1].toStatus").value("CONFIRMED"))
			.andExpect(jsonPath("$.history[1].decidedBy").value(ACTOR))
			// 사유가 없으면 항목을 빼지 않고 null 로 적는다.
			.andExpect(jsonPath("$.history[1]").value(org.hamcrest.Matchers.hasKey("note")))
			.andExpect(jsonPath("$.history[1].note").value(nullValue()));
		Instant after = this.clock.instant().plusSeconds(1);

		// 저장된 것이 같다. 언제는 서버의 시각이다 (요청에 적은 2000년이 아니다).
		assertThat(records.find(action.id()).orElseThrow().status()).isEqualTo(ActionStatus.CONFIRMED);
		List<ActionDecision> history = records.history(action.id());
		assertThat(history).hasSize(2).allSatisfy(decision -> {
			assertThat(decision.decidedBy()).isEqualTo(ACTOR);
			assertThat(decision.decidedAt()).isBetween(before, after);
		});
		// 읽는 API 도 같은 것을 준다. 조치 목록의 상태도 바뀌어 있다.
		this.mvc.perform(get("/api/v1/action").param("actionId", action.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.action.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.history[*].toStatus", org.hamcrest.Matchers.contains("ON_HOLD", "CONFIRMED")));
		this.mvc.perform(get("/api/v1/actions").param("packId", "dec-flow").param("status", "CONFIRMED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.actions[0].id").value(action.id()));
		// 다른 조치는 그대로 대기다.
		assertThat(records.find(actions.get(1).id()).orElseThrow().status()).isEqualTo(ActionStatus.PENDING);
	}

	// ── 거부 ─────────────────────────────────────────────────────

	@Test
	void 갈_수_없는_바뀜은_409_로_지금의_상태와_마지막_이력을_알리고_아무것도_바꾸지_않는다() throws Exception {
		Action action = prepare("dec-conflict").get(0);
		String confirm = """
				{ "actionId": %s, "decision": "CONFIRM", "note": "확인했다" }
				""".formatted(json(action.id()));
		decide(confirm).andExpect(status().isOk());

		// 같은 요청을 다시 보낸 것도, 다른 사람이 뒤에 보낸 것도 같은 응답이다.
		decide(confirm).andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.code").value("ACTION_DECISION_CONFLICT"))
			.andExpect(jsonPath("$.title").value("지금은 할 수 없는 요청"))
			.andExpect(jsonPath("$.detail").isString())
			.andExpect(jsonPath("$.currentStatus").value("CONFIRMED"))
			.andExpect(jsonPath("$.lastDecision.seq").value(1))
			.andExpect(jsonPath("$.lastDecision.toStatus").value("CONFIRMED"))
			.andExpect(jsonPath("$.lastDecision.decidedBy").value(ACTOR))
			.andExpect(jsonPath("$.lastDecision.note").value("확인했다"));
		decide("""
				{ "actionId": %s, "decision": "HOLD", "note": "미룬다" }
				""".formatted(json(action.id()))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.currentStatus").value("CONFIRMED"));

		assertThat(new ActionRecords(this.dataSource).history(action.id())).hasSize(1);

		// 보류된 조치를 다시 보류: 지금의 상태와 마지막 이력이 서로 맞는다. 사유 없는 확정의 note 는 null 로 남는다.
		Action other = new ActionRecords(this.dataSource).packActions("dec-conflict", ActionStatus.PENDING, null).get(0);
		String hold = """
				{ "actionId": %s, "decision": "HOLD", "note": "미룬다" }
				""".formatted(json(other.id()));
		decide(hold).andExpect(status().isOk());
		decide(hold).andExpect(status().isConflict())
			.andExpect(jsonPath("$.currentStatus").value("ON_HOLD"))
			.andExpect(jsonPath("$.lastDecision.toStatus").value("ON_HOLD"))
			// 문구에 조치의 식별자를 되돌려 담지 않는다.
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(other.id()))));
		decide("""
				{ "actionId": %s, "decision": "CONFIRM" }
				""".formatted(json(other.id()))).andExpect(status().isOk());
		decide(hold).andExpect(status().isConflict())
			.andExpect(jsonPath("$.currentStatus").value("CONFIRMED"))
			.andExpect(jsonPath("$.lastDecision.seq").value(2))
			.andExpect(jsonPath("$.lastDecision").value(org.hamcrest.Matchers.hasKey("note")))
			.andExpect(jsonPath("$.lastDecision.note").value(nullValue()));
	}

	// ── 틀린 요청 ──────────────────────────────────────────────────

	@Test
	void 틀린_요청은_어느_항목인지_알리고_아무것도_바꾸지_않는다() throws Exception {
		Action action = prepare("dec-bad").get(0);
		String id = json(action.id());

		expectProblem(decide("이것은 JSON 이 아니다"), 400, "INVALID_BODY", null);
		expectProblem(decide(""), 400, "INVALID_BODY", null);
		// JSON 이지만 항목을 담은 객체가 아닌 본문.
		for (String notObject : List.of("null", "[]", "\"글자\"", "12")) {
			expectProblem(decide(notObject), 400, "INVALID_BODY", null)
				.andExpect(jsonPath("$.detail").value("요청의 본문을 읽지 못했습니다. 항목을 담은 JSON 객체로 적어 다시 보내세요."));
		}
		// 항목의 형식이 글자가 아니면 글자로 바꿔 받지 않는다.
		expectProblem(decide("{ \"actionId\": 12, \"decision\": \"CONFIRM\" }"), 400, "INVALID_ACTION_ID", "actionId");
		expectProblem(decide("{ \"actionId\": " + id + ", \"decision\": 1 }"), 400, "INVALID_DECISION", "decision");
		expectProblem(decide("{ \"actionId\": " + id + ", \"decision\": \"HOLD\", \"note\": true }"), 400, "INVALID_NOTE",
				"note");
		expectProblem(decide("{ \"actionId\": " + id + ", \"decision\": \"CONFIRM\", \"note\": 123 }"), 400, "INVALID_NOTE",
				"note");
		expectProblem(decide("{ \"actionId\": " + id + ", \"decision\": \"CONFIRM\", \"note\": {} }"), 400, "INVALID_NOTE",
				"note");
		// 공백뿐인 사유도 한도를 넘으면 받지 않는다.
		expectProblem(decide("{ \"actionId\": " + id + ", \"decision\": \"CONFIRM\", \"note\": \"" + " ".repeat(1001) + "\" }"),
				400, "INVALID_NOTE", "note");
		expectProblem(decide("{ \"decision\": \"CONFIRM\" }"), 400, "MISSING_ACTION_ID", "actionId");
		expectProblem(decide("{ \"actionId\": \"\", \"decision\": \"CONFIRM\" }"), 400, "MISSING_ACTION_ID", "actionId");
		expectProblem(decide("{ \"actionId\": \"a\\u0000\", \"decision\": \"CONFIRM\" }"), 400, "INVALID_ACTION_ID", "actionId");
		expectProblem(decide("{ \"actionId\": " + id + " }"), 400, "MISSING_DECISION", "decision");
		expectProblem(decide("{ \"actionId\": " + id + ", \"decision\": \"confirm\" }"), 400, "INVALID_DECISION", "decision");
		expectProblem(decide("{ \"actionId\": " + id + ", \"decision\": \"REOPEN\" }"), 400, "INVALID_DECISION", "decision");
		// 보류에는 사유가 반드시 있어야 한다. 눈에 보이는 글자가 없는 사유도 없는 것이다.
		expectProblem(decide("{ \"actionId\": " + id + ", \"decision\": \"HOLD\" }"), 400, "INVALID_NOTE", "note");
		expectProblem(decide("{ \"actionId\": " + id + ", \"decision\": \"HOLD\", \"note\": \" \\n\\u00A0\" }"), 400,
				"INVALID_NOTE", "note");
		expectProblem(decide("{ \"actionId\": " + id + ", \"decision\": \"CONFIRM\", \"note\": \"" + "가".repeat(1001) + "\" }"),
				400, "INVALID_NOTE", "note");
		expectProblem(decide("{ \"actionId\": \"dec-없는 조치\", \"decision\": \"CONFIRM\" }"), 404, "ACTION_NOT_FOUND",
				"actionId");

		expectProblem(this.mvc.perform(get("/api/v1/action")), 400, "MISSING_ACTION_ID", "actionId");
		expectProblem(this.mvc.perform(get("/api/v1/action").param("actionId", "")), 400, "MISSING_ACTION_ID", "actionId");
		expectProblem(this.mvc.perform(get("/api/v1/action").param("actionId", "a\u0000")), 400, "INVALID_ACTION_ID",
				"actionId");
		expectProblem(this.mvc.perform(get("/api/v1/action").param("actionId", "dec-없는 조치")), 404, "ACTION_NOT_FOUND",
				"actionId");

		ActionRecords records = new ActionRecords(this.dataSource);
		assertThat(records.find(action.id()).orElseThrow().status()).isEqualTo(ActionStatus.PENDING);
		assertThat(records.history(action.id())).isEmpty();
	}

	// ── 도우미 ──────────────────────────────────────────────────

	private ResultActions decide(String body) throws Exception {
		return this.mvc.perform(post("/api/v1/action-decisions").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	/** 시험용 Pack 의 사본에 판을 차례로 올려 조치를 만든다. 대기인 조치 둘 이상을 돌려준다. */
	private List<Action> prepare(String packId) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] edition : EDITIONS) {
			this.processing.submit(packId, edition[0], edition[1], Files.readAllBytes(pack.resolve("data/" + edition[1])),
					LocalDate.parse(edition[2]));
		}
		List<Action> actions = new ActionRecords(this.dataSource).packActions(packId, null, null);
		assertThat(actions).hasSizeGreaterThan(1);
		return actions;
	}

	/** JSON 의 글자 값으로 적는다. 조치의 식별자에는 역슬래시와 세로 막대가 들어 있다. */
	private static String json(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static ResultActions expectProblem(ResultActions result, int status, String code, String field)
			throws Exception {
		result.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.detail").isString());
		if (field != null) {
			result.andExpect(jsonPath("$.field").value(field));
		}
		return result;
	}
}
