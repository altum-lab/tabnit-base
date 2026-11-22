package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.NewRelation;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.RelationStore;
import com.altumlab.tabnit.core.ValidityPeriod;
import com.altumlab.tabnit.rules.Action;
import com.altumlab.tabnit.rules.ActionRecords;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-09 (둘째): 개체를 유형, 키, 표시 이름과 함께 준다 (D7 2절 "개체", D2 AD-326, AD-349).
 *
 * <p>관계 조회는 조회 시점의, 조치와 변동의 영향 범위는 그 변동이 나온 판의 적용일의 표시 이름을 준다.
 * 값이 없으면 null 이다 (화면은 키를 보여 준다). 시험용 Pack 의 사본에 판을 올려 만든 자료와,
 * Pack 없이 core 에 직접 넣은 지어낸 자료를 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EntityNamesApiTests {

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

	// ── 관계 조회: 조회 시점의 표시 이름 ─────────────────────────────────

	@Test
	void 관계의_출발과_도착_개체가_유형의_이름표와_키와_조회_시점의_표시_이름과_함께_온다() throws Exception {
		String packId = "name-rel";
		prepare(packId);
		String guideline = packId + "|운영지침|G-01";

		ResultActions result = this.mvc
			.perform(get("/api/v1/relations").param("entityId", guideline).param("asOf", "2026-02-01"));

		// 식별자는 그대로 있고, 개체마다 사람이 읽을 것이 붙는다. 순서는 그대로다 (상대 개체의 id 순).
		result.andExpect(status().isOk())
			.andExpect(jsonPath("$.relations[0].sourceEntityId").value(guideline))
			.andExpect(jsonPath("$.relations[0].source.entityId").value(guideline))
			.andExpect(jsonPath("$.relations[0].source.entityType").value("운영지침"))
			.andExpect(jsonPath("$.relations[0].source.entityTypeLabel").value("운영지침"))
			.andExpect(jsonPath("$.relations[0].source.entityKey").value("G-01"))
			.andExpect(jsonPath("$.relations[0].source.displayName").value("방문 건강 관리 운영 기준"))
			.andExpect(jsonPath("$.relations[0].target.entityId").value(packId + "|의료사업|P-01"))
			.andExpect(jsonPath("$.relations[0].target.entityType").value("의료사업"))
			.andExpect(jsonPath("$.relations[0].target.entityKey").value("P-01"))
			.andExpect(jsonPath("$.relations[0].target.displayName").value("방문 건강 관리"))
			.andExpect(jsonPath("$.relations[1].target.displayName").value("감염병 대응 교육"));
		// 값이 아직 없던 날짜에는 표시 이름이 없다. 뒤의 값을 끌어다 쓰지 않는다.
		this.mvc.perform(get("/api/v1/relations").param("entityId", guideline).param("asOf", "2025-12-31"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void 상위_유형에서_받은_표시_이름_속성으로도_표시_이름이_온다() throws Exception {
		String packId = "name-inherit";
		prepare(packId);
		// 보건기관과 민간기관은 표시 이름 속성을 스스로 적지 않았다. 상위 유형(협력기관)의 것을 받는다.
		List<Action> actions = new ActionRecords(this.dataSource).packActions(packId, null, null);
		Action onPartner = actions.stream()
			.filter(action -> action.targetEntityType().equals("보건기관") || action.targetEntityType().equals("민간기관"))
			.findFirst()
			.orElseThrow();

		ResultActions result = this.mvc.perform(get("/api/v1/action").param("actionId", onPartner.id()));

		result.andExpect(status().isOk())
			.andExpect(jsonPath("$.action.target.entityId").value(onPartner.targetEntityId()))
			.andExpect(jsonPath("$.action.target.entityType").value(onPartner.targetEntityType()))
			.andExpect(jsonPath("$.action.target.entityTypeLabel").isNotEmpty())
			.andExpect(jsonPath("$.action.target.entityKey").isNotEmpty())
			.andExpect(jsonPath("$.action.target.displayName").isNotEmpty());
	}

	@Test
	void Pack_이_적재되지_않은_개체와_표시_이름이_없는_개체는_유형의_이름과_키만_오고_표시_이름은_null_이다() throws Exception {
		RelationStore store = new RelationStore(this.dataSource);
		EntityRef a = new EntityRef("name-no-pack", "T", "가|나");
		EntityRef b = new EntityRef("name-no-pack", "T", "b");
		store.saveEntity(a, Instant.parse("2026-01-01T00:00:00Z"));
		store.saveEntity(b, Instant.parse("2026-01-01T00:00:00Z"));
		store.saveRelation(new NewRelation(a, b, "R", new ValidityPeriod(LocalDate.parse("2026-01-01"), null),
				new Provenance("src", 1)), Instant.parse("2026-01-01T00:00:00Z"));

		this.mvc.perform(get("/api/v1/relations").param("entityId", a.id()).param("asOf", "2026-03-01"))
			.andExpect(status().isOk())
			// 키에 구분 글자가 들어 있어도 키는 넣은 그대로다. 화면이 id 를 쪼개지 않아도 된다.
			.andExpect(jsonPath("$.relations[0].source.entityKey").value("가|나"))
			.andExpect(jsonPath("$.relations[0].source.entityType").value("T"))
			// 이름표를 알 수 없으면 유형의 이름을 그대로 준다.
			.andExpect(jsonPath("$.relations[0].source.entityTypeLabel").value("T"))
			.andExpect(jsonPath("$.relations[0].source.displayName").value(nullValue()))
			.andExpect(jsonPath("$.relations[0].target.entityKey").value("b"))
			.andExpect(jsonPath("$.relations[0].target.displayName").value(nullValue()));
	}

	// ── 조치와 근거 경로: 그 변동이 나온 판의 적용일의 표시 이름 ───────────────────

	@Test
	void 조치의_대상과_근거가_되는_주_개체가_판의_적용일의_표시_이름과_함께_온다() throws Exception {
		String packId = "name-act";
		prepare(packId);
		List<Action> actions = new ActionRecords(this.dataSource).packActions(packId, null, null);
		// 영향 대상(의료사업)에 거는 조치. 근거는 바뀐 운영지침이다.
		Action action = actions.stream()
			.filter(candidate -> candidate.targetEntityId().equals(packId + "|의료사업|P-01")
					&& candidate.basisEntityId().equals(packId + "|운영지침|G-01"))
			.findFirst()
			.orElseThrow();
		int index = actions.indexOf(action);

		ResultActions list = this.mvc.perform(get("/api/v1/actions").param("packId", packId));

		String at = "$.actions[" + index + "]";
		list.andExpect(status().isOk())
			.andExpect(jsonPath(at + ".target.entityId").value(packId + "|의료사업|P-01"))
			.andExpect(jsonPath(at + ".target.entityType").value("의료사업"))
			.andExpect(jsonPath(at + ".target.entityTypeLabel").value("의료사업"))
			.andExpect(jsonPath(at + ".target.entityKey").value("P-01"))
			.andExpect(jsonPath(at + ".target.displayName").value("방문 건강 관리"))
			.andExpect(jsonPath(at + ".basis.entity.entityKey").value("G-01"))
			.andExpect(jsonPath(at + ".basis.entity.displayName").value("방문 건강 관리 운영 기준"));
		// 조치 하나를 읽어도, 두 번 읽어도 같다.
		String once = this.mvc.perform(get("/api/v1/action").param("actionId", action.id()))
			.andExpect(jsonPath("$.action.target.displayName").value("방문 건강 관리"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(this.mvc.perform(get("/api/v1/action").param("actionId", action.id()))
			.andReturn()
			.getResponse()
			.getContentAsString()).isEqualTo(once);
	}

	@Test
	void 근거_경로의_영향_대상과_시작한_변동_대상과_단계의_개체가_판의_적용일의_표시_이름과_함께_온다() throws Exception {
		String packId = "name-path";
		prepare(packId);
		Action action = new ActionRecords(this.dataSource).packActions(packId, null, null)
			.stream()
			.filter(candidate -> candidate.targetEntityId().equals(packId + "|의료사업|P-01")
					&& candidate.basisEntityId().equals(packId + "|운영지침|G-01"))
			.findFirst()
			.orElseThrow();

		ResultActions result = this.mvc.perform(get("/api/v1/impact-scopes").param("changeEventId", action.changeEventId())
			.param("entityId", action.targetEntityId()));

		result.andExpect(status().isOk())
			.andExpect(jsonPath("$.targets[0].entityId").value(packId + "|의료사업|P-01"))
			.andExpect(jsonPath("$.targets[0].entity.entityTypeLabel").value("의료사업"))
			.andExpect(jsonPath("$.targets[0].entity.entityKey").value("P-01"))
			.andExpect(jsonPath("$.targets[0].entity.displayName").value("방문 건강 관리"))
			.andExpect(jsonPath("$.targets[0].path.startEntityId").value(packId + "|운영지침|G-01"))
			.andExpect(jsonPath("$.targets[0].path.start.entityKey").value("G-01"))
			.andExpect(jsonPath("$.targets[0].path.start.displayName").value("방문 건강 관리 운영 기준"))
			// 단계의 출발과 도착은 그 관계에 적힌 방향이다.
			.andExpect(jsonPath("$.targets[0].path.steps[0].sourceEntityId").value(packId + "|운영지침|G-01"))
			.andExpect(jsonPath("$.targets[0].path.steps[0].source.displayName").value("방문 건강 관리 운영 기준"))
			.andExpect(jsonPath("$.targets[0].path.steps[0].target.entityKey").value("P-01"))
			.andExpect(jsonPath("$.targets[0].path.steps[0].target.displayName").value("방문 건강 관리"));
	}

	@Test
	void 그_판에서_없어진_개체는_적용일에_값이_없어_표시_이름이_null_이고_키가_온다() throws Exception {
		String packId = "name-removed";
		prepare(packId);
		// 운영지침 G-03 은 둘째 판에서 없어졌다. 그 이름은 그 판의 적용일에 끝났다.
		List<Action> actions = new ActionRecords(this.dataSource).packActions(packId, null, null);
		Action action = actions.stream()
			.filter(candidate -> candidate.basisEntityId().equals(packId + "|운영지침|G-03"))
			.findFirst()
			.orElseThrow();

		this.mvc.perform(get("/api/v1/action").param("actionId", action.id()))
			.andExpect(status().isOk())
			// 앞의 값을 끌어다 쓰지 않는다 (D2 AD-55). 유형과 키는 그대로 온다.
			.andExpect(jsonPath("$.action.basis.entity.displayName").value(nullValue()))
			.andExpect(jsonPath("$.action.basis.entity.entityKey").value("G-03"))
			.andExpect(jsonPath("$.action.basis.entity.entityTypeLabel").value("운영지침"));
		// 그 전날에는 이름이 있었다: 관계 조회는 조회 시점의 값을 준다.
		this.mvc.perform(get("/api/v1/relations").param("entityId", packId + "|운영지침|G-03").param("asOf", "2026-02-28"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.relations[0].source.displayName").value("검진 지원 운영 기준"));
	}

	@Test
	void 판의_적용일이_다른_조치가_섞인_목록에서도_조치마다_그_판의_적용일의_표시_이름이_온다() throws Exception {
		String packId = "name-mixed";
		Path pack = prepare(packId);
		// 사업의 둘째 판을 더 올린다: 적용일이 다른 조치가 생긴다.
		this.processing.submit(packId, "program", "program-v2.csv", Files.readAllBytes(pack.resolve("data/program-v2.csv")),
				LocalDate.parse("2026-04-01"));
		List<Action> actions = new ActionRecords(this.dataSource).packActions(packId, null, null);
		assertThat(actions.stream().map(Action::effectiveDate).distinct()).hasSizeGreaterThan(1);

		ResultActions list = this.mvc.perform(get("/api/v1/actions").param("packId", packId).param("limit", "200"));

		list.andExpect(status().isOk());
		com.altumlab.tabnit.core.AttributeValueQuery values = new com.altumlab.tabnit.core.AttributeValueQuery(this.dataSource);
		for (int i = 0; i < actions.size(); i++) {
			Action action = actions.get(i);
			// 근거가 되는 개체의 표시 이름 속성(운영지침은 지침제목, 의료사업은 사업명)의 그 조치의 판의 적용일의 값.
			String attribute = action.basisEntityId().contains("|운영지침|") ? "지침제목" : "사업명";
			String expected = values.valueAsOf(action.basisEntityId(), attribute, action.effectiveDate())
				.map(com.altumlab.tabnit.core.AttributeValue::value)
				.orElse(null);
			list.andExpect(jsonPath("$.actions[" + i + "].id").value(action.id()))
				.andExpect(jsonPath("$.actions[" + i + "].basis.entity.displayName").value(expected));
		}
	}

	/** 시험용 Pack 의 사본을 적재하고 판을 차례로 올린다. 마지막 판에서 변동과 조치가 나온다. */
	private Path prepare(String packId) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] edition : EDITIONS) {
			this.processing.submit(packId, edition[0], edition[1], Files.readAllBytes(pack.resolve("data/" + edition[1])),
					LocalDate.parse(edition[2]));
		}
		return pack;
	}
}
