package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.core.EntityQuery;
import com.altumlab.tabnit.link.ChangeEvent;
import com.altumlab.tabnit.link.EditionRecords;
import com.altumlab.tabnit.rules.Action;
import com.altumlab.tabnit.rules.ActionRecords;
import com.altumlab.tabnit.rules.StoredImpactScope;
import com.altumlab.tabnit.rules.StoredImpactTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-06 (서버 쪽): 조치 목록과 변동의 영향 범위를 읽는 API 가 규격(docs/api/openapi.yaml 의 listActions,
 * getChangeImpactScope)대로 답한다.
 *
 * <p>D7 6절: 조치에는 근거가 되는 주 개체와 그 판이, 영향 대상에는 근거 경로가 붙는다. 순서는 서버가 정한다.
 * 시험용 Pack 의 사본에 판을 올려 만든 조치를 읽는다. 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ActionsApiTests {

	/** 시험용 Pack 의 자료를 올리는 차례. 입력 자료, 파일, 적용일. 마지막 줄에서 변동과 조치가 나온다. */
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

	@TempDir
	Path temp;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
	}

	// ── 조치 목록 ──────────────────────────────────────────────────

	@Test
	void 조치_목록은_저장된_조치를_기한이_이른_것부터_근거와_함께_돌려준다() throws Exception {
		String packId = "act-list";
		prepare(packId);
		List<Action> stored = new ActionRecords(this.dataSource).packActions(packId, null, null);
		assertThat(stored).hasSizeGreaterThan(1);
		// 기한이 이른 것부터인지를 저장된 것과 따로 한 번 더 본다.
		assertThat(stored).extracting(Action::dueDate).isSorted();
		Action first = stored.get(0);
		EntityQuery entities = new EntityQuery(this.dataSource);

		ResultActions result = this.mvc.perform(get("/api/v1/actions").param("packId", packId));

		result.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("application/json"))
			.andExpect(jsonPath("$.packId").value(packId))
			.andExpect(jsonPath("$.status").value(nullValue()))
			.andExpect(jsonPath("$.changeEventId").value(nullValue()))
			.andExpect(jsonPath("$.total").value(stored.size()))
			// 순서는 rules 가 준 그대로다.
			.andExpect(jsonPath("$.actions[*].id", contains(stored.stream().map(Action::id).toArray())))
			.andExpect(jsonPath("$.actions[0].changeEventId").value(first.changeEventId()))
			.andExpect(jsonPath("$.actions[0].ruleNo").value(first.ruleNo()))
			.andExpect(jsonPath("$.actions[0].ruleLabel").value(first.ruleLabel()))
			.andExpect(jsonPath("$.actions[0].actionText").value(first.actionText()))
			.andExpect(jsonPath("$.actions[0].priority").value(first.priority()))
			.andExpect(jsonPath("$.actions[0].dueDate").value(first.dueDate().toString()))
			.andExpect(jsonPath("$.actions[0].status").value("PENDING"))
			.andExpect(jsonPath("$.actions[0].appliesTo").value(first.appliesTo().name()))
			.andExpect(jsonPath("$.actions[0].target.entityId").value(first.targetEntityId()))
			.andExpect(jsonPath("$.actions[0].target.entityType").value(first.targetEntityType()))
			// 근거: 변동이 생긴 행의 주 개체와 그 변동이 나온 판. 조치마다 반드시 있다.
			.andExpect(jsonPath("$.actions[0].basis.entity.entityId").value(first.basisEntityId()))
			.andExpect(jsonPath("$.actions[0].basis.entity.entityType")
				.value(entities.find(first.basisEntityId()).orElseThrow().entityType()))
			.andExpect(jsonPath("$.actions[0].basis.inputSourceId").value("guideline"))
			.andExpect(jsonPath("$.actions[0].basis.editionNo").value(2))
			.andExpect(jsonPath("$.actions[0].basis.effectiveDate").value("2026-03-01"))
			.andExpect(jsonPath("$.actions[0].createdAt").isString())
			.andExpect(jsonPath("$.actions[*].basis.entity.entityId", hasSize(stored.size())))
			.andExpect(jsonPath("$.actions[*].basis.editionNo", everyItem(is(2))));
	}

	@Test
	void 조치_목록을_상태와_변동으로_거른다() throws Exception {
		String packId = "act-filter";
		prepare(packId);
		ActionRecords records = new ActionRecords(this.dataSource);
		ChangeEvent event = new EditionRecords(this.dataSource).changeEvents(packId + "|guideline|2")
			.stream()
			.filter(candidate -> !records.actions(candidate.id()).isEmpty())
			.findFirst()
			.orElseThrow();
		List<Action> ofEvent = records.packActions(packId, null, event.id());
		assertThat(ofEvent).isNotEmpty().hasSizeLessThan(records.packActions(packId, null, null).size());

		this.mvc.perform(get("/api/v1/actions").param("packId", packId).param("changeEventId", event.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.changeEventId").value(event.id()))
			.andExpect(jsonPath("$.total").value(ofEvent.size()))
			.andExpect(jsonPath("$.actions[*].id", contains(ofEvent.stream().map(Action::id).toArray())))
			.andExpect(jsonPath("$.actions[*].changeEventId", everyItem(is(event.id()))));
		this.mvc.perform(get("/api/v1/actions").param("packId", packId).param("status", "PENDING"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.total").value(records.packActions(packId, null, null).size()));
		// 해당하는 것이 없으면 빈 목록이다. 오류가 아니다.
		this.mvc.perform(get("/api/v1/actions").param("packId", packId).param("status", "CONFIRMED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.actions", hasSize(0)));
		// 상태가 섞인 자료: 조치 하나의 상태를 저장소에서 직접 바꾼다 (확정하는 기능은 아직 없다).
		Action one = ofEvent.get(0);
		new org.springframework.jdbc.core.JdbcTemplate(this.dataSource)
			.update("update action set status = 'CONFIRMED' where id = ?", one.id());
		this.mvc.perform(get("/api/v1/actions").param("packId", packId).param("status", "CONFIRMED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.actions[0].id").value(one.id()))
			.andExpect(jsonPath("$.actions[0].status").value("CONFIRMED"));
		this.mvc
			.perform(get("/api/v1/actions").param("packId", packId)
				.param("status", "PENDING")
				.param("changeEventId", event.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(ofEvent.size() - 1))
			.andExpect(jsonPath("$.actions[*].status", everyItem(is("PENDING"))));
		// 다른 Pack 의 변동으로 거르면 그 Pack 의 조치를 주지 않는다.
		String other = "act-filter-other";
		prepare(other);
		ChangeEvent foreign = new EditionRecords(this.dataSource).changeEvents(other + "|guideline|2").get(0);
		this.mvc.perform(get("/api/v1/actions").param("packId", packId).param("changeEventId", foreign.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void 조치_목록의_틀린_요청은_어느_항목인지_알린다() throws Exception {
		String packId = "act-bad";
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);

		expectProblem(this.mvc.perform(get("/api/v1/actions")), 400, "MISSING_PACK_ID", "packId");
		expectProblem(this.mvc.perform(get("/api/v1/actions").param("packId", " ")), 400, "MISSING_PACK_ID", "packId");
		expectProblem(this.mvc.perform(get("/api/v1/actions").param("packId", packId).param("status", "pending")), 400,
				"INVALID_STATUS", "status");
		expectProblem(this.mvc.perform(get("/api/v1/actions").param("packId", packId).param("status", "DONE")), 400,
				"INVALID_STATUS", "status");
		expectProblem(this.mvc.perform(get("/api/v1/actions").param("packId", packId).param("changeEventId", "ev\u0000")),
				400, "INVALID_CHANGE_EVENT_ID", "changeEventId");
		// 항목은 있는데 값이 빈 것. 조용히 빈 목록이 되지 않는다.
		expectProblem(this.mvc.perform(get("/api/v1/actions").param("packId", packId).param("changeEventId", "")), 400,
				"INVALID_CHANGE_EVENT_ID", "changeEventId");
		expectProblem(this.mvc.perform(get("/api/v1/actions").param("packId", "act-없는-pack")), 404, "PACK_NOT_FOUND",
				"packId");
		expectProblem(this.mvc.perform(get("/api/v1/actions").param("packId", "act\u0000")), 404, "PACK_NOT_FOUND",
				"packId");
		// 조치가 하나도 없는 Pack 은 빈 목록이다.
		this.mvc.perform(get("/api/v1/actions").param("packId", packId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.actions", hasSize(0)));
	}

	// ── 변동의 영향 범위 ────────────────────────────────────────────

	@Test
	void 변동의_영향_범위는_저장된_영향_대상을_근거_경로와_수와_함께_돌려준다() throws Exception {
		String packId = "act-scope";
		prepare(packId);
		ActionRecords records = new ActionRecords(this.dataSource);
		// 영향 대상이 있는 변동 하나.
		StoredImpactScope stored = new EditionRecords(this.dataSource).changeEvents(packId + "|guideline|2")
			.stream()
			.map(event -> records.impactScope(event.id()).orElseThrow())
			.filter(scope -> scope.targets().size() > 1)
			.findFirst()
			.orElseThrow();
		List<StoredImpactTarget> targets = stored.targets();

		ResultActions result = this.mvc
			.perform(get("/api/v1/impact-scopes").param("changeEventId", stored.changeEventId()));

		result.andExpect(status().isOk())
			.andExpect(jsonPath("$.changeEventId").value(stored.changeEventId()))
			.andExpect(jsonPath("$.packId").value(packId))
			// 판의 적용일 전날과 적용일, 두 날짜로 본 것이다.
			.andExpect(jsonPath("$.asOfPrevious").value("2026-02-28"))
			.andExpect(jsonPath("$.asOfEffective").value("2026-03-01"))
			.andExpect(jsonPath("$.maxDepth").value(stored.maxDepth()))
			.andExpect(jsonPath("$.truncated").value(stored.truncated()))
			.andExpect(jsonPath("$.storedAt").isString())
			.andExpect(jsonPath("$.total").value(targets.size()))
			.andExpect(jsonPath("$.countsByType[*].entityType", contains(stored.countsByType().keySet().toArray())))
			.andExpect(jsonPath("$.countsByType[*].count", contains(stored.countsByType().values().toArray())))
			.andExpect(jsonPath("$.countsByDepth[*].depth", contains(stored.countsByDepth().keySet().toArray())))
			.andExpect(jsonPath("$.countsByDepth[*].count", contains(stored.countsByDepth().values().toArray())))
			// 순서는 rules 가 준 그대로다 (깊이, 개체 유형, 개체의 식별자).
			.andExpect(jsonPath("$.targets[*].entityId",
					contains(targets.stream().map(StoredImpactTarget::entityId).toArray())))
			.andExpect(jsonPath("$.targets[*].entityType",
					contains(targets.stream().map(StoredImpactTarget::entityType).toArray())))
			.andExpect(jsonPath("$.targets[*].depth", contains(targets.stream().map(StoredImpactTarget::depth).toArray())));
		// 영향 대상마다 근거 경로가 있고, 단계의 수는 깊이와 같다.
		for (int i = 0; i < targets.size(); i++) {
			StoredImpactTarget target = targets.get(i);
			String path = "$.targets[" + i + "].path";
			result.andExpect(jsonPath(path + ".startEntityId").value(target.pathStartEntityId()))
				.andExpect(jsonPath(path + ".asOf").value(target.pathAsOf().toString()))
				.andExpect(jsonPath(path + ".steps", hasSize(target.depth())))
				.andExpect(jsonPath(path + ".steps[0].stepNo").value(1))
				.andExpect(jsonPath(path + ".steps[0].relationId").value(target.steps().get(0).relationId()))
				.andExpect(jsonPath(path + ".steps[0].relationType").value(target.steps().get(0).relationType()))
				.andExpect(jsonPath(path + ".steps[0].sourceEntityId").value(target.steps().get(0).sourceEntityId()))
				.andExpect(jsonPath(path + ".steps[0].targetEntityId").value(target.steps().get(0).targetEntityId()));
		}
		// 합계가 맞는다.
		assertThat(stored.countsByType().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(targets.size());
		assertThat(stored.countsByDepth().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(targets.size());
	}

	@Test
	void 영향_대상에_거는_조치의_대상은_그_변동의_영향_범위에_있고_바뀐_개체_자신에_거는_조치의_대상은_없다() throws Exception {
		String packId = "act-join";
		prepare(packId);
		ActionRecords records = new ActionRecords(this.dataSource);
		List<Action> all = records.packActions(packId, null, null);
		// 두 가지 조치가 모두 있어야 이 시험이 뜻이 있다.
		assertThat(all).extracting(action -> action.appliesTo().name()).contains("IMPACT_TARGET", "CHANGE_TARGET");

		for (Action action : all) {
			// 조치의 근거 경로는 조치가 가리키는 변동의 영향 범위에서 대상으로 찾는다 (D7 6절).
			String body = this.mvc.perform(get("/api/v1/impact-scopes").param("changeEventId", action.changeEventId()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
			List<String> targetIds = com.jayway.jsonpath.JsonPath.read(body, "$.targets[*].entityId");
			if (action.appliesTo().name().equals("IMPACT_TARGET")) {
				assertThat(targetIds).as("조치 %s", action.id()).contains(action.targetEntityId());
			}
			else {
				assertThat(targetIds).as("조치 %s", action.id()).doesNotContain(action.targetEntityId());
			}
		}
	}

	@Test
	void 영향_범위가_없는_변동과_틀린_요청은_오류로_알린다() throws Exception {
		expectProblem(this.mvc.perform(get("/api/v1/impact-scopes")), 400, "MISSING_CHANGE_EVENT_ID", "changeEventId");
		expectProblem(this.mvc.perform(get("/api/v1/impact-scopes").param("changeEventId", "")), 400,
				"MISSING_CHANGE_EVENT_ID", "changeEventId");
		expectProblem(this.mvc.perform(get("/api/v1/impact-scopes").param("changeEventId", "ev\u0000")), 400,
				"INVALID_CHANGE_EVENT_ID", "changeEventId");
		expectProblem(this.mvc.perform(get("/api/v1/impact-scopes").param("changeEventId", "act-없는 변동")), 404,
				"IMPACT_SCOPE_NOT_FOUND", "changeEventId");
	}

	// ── 도우미 ──────────────────────────────────────────────────

	/** 시험용 Pack 의 사본을 적재하고 판을 차례로 올린다. 마지막 판에서 변동과 조치가 나온다. */
	private void prepare(String packId) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] edition : EDITIONS) {
			this.processing.submit(packId, edition[0], edition[1], Files.readAllBytes(pack.resolve("data/" + edition[1])),
					LocalDate.parse(edition[2]));
		}
	}

	private static void expectProblem(ResultActions result, int status, String code, String field) throws Exception {
		result.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.field").value(field))
			.andExpect(jsonPath("$.detail").isString());
	}
}
