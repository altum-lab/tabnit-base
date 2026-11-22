package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.NewRelation;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.Relation;
import com.altumlab.tabnit.core.RelationQuery;
import com.altumlab.tabnit.core.RelationStore;
import com.altumlab.tabnit.core.ValidityPeriod;
import com.altumlab.tabnit.rules.Action;
import com.altumlab.tabnit.rules.ActionRecords;
import com.altumlab.tabnit.rules.StoredImpactScope;
import com.altumlab.tabnit.rules.StoredImpactTarget;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-13 수용 기준: 관계, 영향 대상, 조치의 목록이 정해진 크기로 나뉘어 오고 전체 수가 함께 온다.
 * 나눠 받은 것을 이으면 한 번에 받은 것과 순서까지 같다 (D7 2절 "나눠 주기", D2 AD-310, AD-347).
 *
 * <p>규격은 docs/api/openapi.yaml 의 getRelationsAsOf, listActions, getChangeImpactScope 다.
 * 관계는 지어낸 값을 core 의 저장 API 로 넣고, 조치와 영향 범위는 시험용 Pack 의 사본에 판을 올려 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PagingApiTests {

	private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

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

	// ── 관계 ─────────────────────────────────────────────────────

	@Test
	void 관계를_나눠_받아_이으면_한_번에_받은_것과_순서까지_같고_전체_수와_답에_쓴_값이_함께_온다() throws Exception {
		RelationStore store = new RelationStore(this.dataSource);
		EntityRef hub = entity(store, "page-rel", "hub");
		for (int i = 0; i < 123; i++) {
			// 넣는 차례를 섞는다 (7 은 123 과 서로소다).
			EntityRef other = entity(store, "page-rel", "t" + (i * 7) % 123);
			store.saveRelation(new NewRelation(hub, other, i % 3 == 0 ? "b-type" : "a-type",
					new ValidityPeriod(LocalDate.parse("2026-01-01"), null), new Provenance("src", 1)), RECORDED_AT);
		}
		List<String> all = new RelationQuery(this.dataSource).relationsAsOf(hub.id(), LocalDate.parse("2026-03-01"))
			.stream()
			.map(Relation::id)
			.toList();
		assertThat(all).hasSize(123);

		// 적지 않으면 0 부터 50개다.
		relations(hub.id(), null, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(123))
			.andExpect(jsonPath("$.offset").value(0))
			.andExpect(jsonPath("$.limit").value(50))
			.andExpect(jsonPath("$.relations[*].id", contains(all.subList(0, 50).toArray())));
		List<String> joined = new ArrayList<>();
		for (int offset = 0; offset < 160; offset += 40) {
			ResultActions page = relations(hub.id(), offset, 40).andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(123))
				.andExpect(jsonPath("$.offset").value(offset))
				.andExpect(jsonPath("$.limit").value(40));
			joined.addAll(ids(page, "$.relations[*].id"));
		}
		assertThat(joined).isEqualTo(all);
		// 가장 큰 크기와, 전체 수를 넘겨 건너뛴 것.
		relations(hub.id(), 0, 200).andExpect(jsonPath("$.relations", hasSize(123)));
		relations(hub.id(), 123, 10).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(123))
			.andExpect(jsonPath("$.relations", hasSize(0)));
	}

	// ── 조치 ─────────────────────────────────────────────────────

	@Test
	void 조치를_나눠_받아_이으면_한_번에_받은_것과_순서까지_같고_거른_전체_수가_함께_온다() throws Exception {
		String packId = "page-act";
		prepare(packId);
		List<Action> stored = new ActionRecords(this.dataSource).packActions(packId, null, null);
		assertThat(stored).hasSizeGreaterThan(4);
		List<String> all = stored.stream().map(Action::id).toList();

		List<String> joined = new ArrayList<>();
		for (int offset = 0; offset < all.size() + 3; offset += 3) {
			ResultActions page = this.mvc
				.perform(get("/api/v1/actions").param("packId", packId)
					.param("offset", String.valueOf(offset))
					.param("limit", "3"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(all.size()))
				.andExpect(jsonPath("$.offset").value(offset))
				.andExpect(jsonPath("$.limit").value(3));
			joined.addAll(ids(page, "$.actions[*].id"));
		}
		assertThat(joined).isEqualTo(all);
		// 걸러도 전체 수는 거른 것의 수다.
		String changeEventId = stored.get(0).changeEventId();
		List<String> ofEvent = stored.stream().filter(a -> a.changeEventId().equals(changeEventId)).map(Action::id).toList();
		this.mvc
			.perform(get("/api/v1/actions").param("packId", packId)
				.param("changeEventId", changeEventId)
				.param("status", "PENDING")
				.param("offset", "1")
				.param("limit", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(ofEvent.size()))
			.andExpect(jsonPath("$.actions[*].id",
					contains(ofEvent.subList(1, Math.min(3, ofEvent.size())).toArray())));
		// 적지 않으면 0 부터 50개다.
		this.mvc.perform(get("/api/v1/actions").param("packId", packId))
			.andExpect(jsonPath("$.offset").value(0))
			.andExpect(jsonPath("$.limit").value(50));
	}

	// ── 영향 대상 ──────────────────────────────────────────────────

	@Test
	void 영향_대상을_나눠_받아_이으면_한_번에_받은_것과_같고_수는_나누기_전의_것이다() throws Exception {
		String packId = "page-scope";
		prepare(packId);
		ActionRecords records = new ActionRecords(this.dataSource);
		String changeEventId = records.packActions(packId, null, null)
			.stream()
			.map(Action::changeEventId)
			.filter(id -> records.impactScope(id).orElseThrow().targets().size() > 2)
			.findFirst()
			.orElseThrow();
		StoredImpactScope whole = records.impactScope(changeEventId).orElseThrow();
		List<String> all = whole.targets().stream().map(StoredImpactTarget::entityId).toList();

		List<String> joined = new ArrayList<>();
		for (int offset = 0; offset < all.size() + 2; offset += 2) {
			ResultActions page = this.mvc
				.perform(scope(changeEventId).param("offset", String.valueOf(offset)).param("limit", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(all.size()))
				.andExpect(jsonPath("$.offset").value(offset))
				.andExpect(jsonPath("$.limit").value(2))
				// 유형별, 깊이별 수는 어느 쪽에서나 영향 범위 전체의 것이다.
				.andExpect(jsonPath("$.countsByType[*].entityType", contains(whole.countsByType().keySet().toArray())))
				.andExpect(jsonPath("$.countsByType[*].count", contains(whole.countsByType().values().toArray())))
				.andExpect(jsonPath("$.countsByDepth[*].count", contains(whole.countsByDepth().values().toArray())));
			joined.addAll(ids(page, "$.targets[*].entityId"));
		}
		assertThat(joined).isEqualTo(all);

		// 대상 하나만 묻는다: 그 대상과 근거 경로만 오고 수는 거르지 않은 것이다.
		StoredImpactTarget last = whole.targets().get(all.size() - 1);
		this.mvc.perform(scope(changeEventId).param("entityId", last.entityId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(all.size()))
			.andExpect(jsonPath("$.targets", hasSize(1)))
			.andExpect(jsonPath("$.targets[0].entityId").value(last.entityId()))
			.andExpect(jsonPath("$.targets[0].depth").value(last.depth()))
			.andExpect(jsonPath("$.targets[0].path.steps", hasSize(last.depth())));
		// 영향 대상이 아닌 개체는 빈 목록이고 200 이다.
		this.mvc.perform(scope(changeEventId).param("entityId", packId + "|없는 유형|없는 것"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(all.size()))
			.andExpect(jsonPath("$.targets", hasSize(0)));
		this.mvc.perform(scope(changeEventId).param("entityId", ""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_ENTITY_ID"))
			.andExpect(jsonPath("$.field").value("entityId"));
	}

	// ── 틀린 값: 조용히 고쳐 받지 않는다 ─────────────────────────────────

	@Test
	void 틀린_건너뛸_수와_크기는_세_목록_모두에서_400_으로_어느_항목인지_알린다() throws Exception {
		RelationStore store = new RelationStore(this.dataSource);
		EntityRef a = entity(store, "page-bad", "a");
		String packId = "page-bad-pack";
		prepare(packId);
		String changeEventId = new ActionRecords(this.dataSource).packActions(packId, null, null).get(0).changeEventId();
		List<MockHttpServletRequestBuilder> lists = List.of(get("/api/v1/relations").param("entityId", a.id()),
				get("/api/v1/actions").param("packId", packId), scope(changeEventId));
		String[][] bad = { { "offset", "-1", "INVALID_OFFSET" }, { "offset", "abc", "INVALID_OFFSET" },
				{ "offset", "1.5", "INVALID_OFFSET" }, { "offset", "", "INVALID_OFFSET" },
				{ "offset", "99999999999999999999", "INVALID_OFFSET" }, { "limit", "0", "INVALID_LIMIT" },
				{ "limit", "201", "INVALID_LIMIT" }, { "limit", "-3", "INVALID_LIMIT" }, { "limit", "많이", "INVALID_LIMIT" },
				{ "limit", "", "INVALID_LIMIT" } };
		for (int i = 0; i < lists.size(); i++) {
			for (String[] wrong : bad) {
				// 요청을 다시 만든다: 만든 요청에 항목을 더하면 앞의 것이 남는다.
				MockHttpServletRequestBuilder request = i == 0 ? get("/api/v1/relations").param("entityId", a.id())
						: i == 1 ? get("/api/v1/actions").param("packId", packId) : scope(changeEventId);
				this.mvc.perform(request.param(wrong[0], wrong[1]))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value(wrong[2]))
					.andExpect(jsonPath("$.field").value(wrong[0]));
			}
		}
		// 경계의 값은 받는다.
		this.mvc.perform(get("/api/v1/relations").param("entityId", a.id()).param("offset", "0").param("limit", "1"))
			.andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/relations").param("entityId", a.id()).param("offset", "2147483647").param("limit", "200"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.relations", hasSize(0)));
	}

	// ── 도우미 ──────────────────────────────────────────────────

	private ResultActions relations(String entityId, Integer offset, Integer limit) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/v1/relations").param("entityId", entityId)
			.param("asOf", "2026-03-01");
		if (offset != null) {
			request.param("offset", offset.toString());
		}
		if (limit != null) {
			request.param("limit", limit.toString());
		}
		return this.mvc.perform(request);
	}

	private static MockHttpServletRequestBuilder scope(String changeEventId) {
		return get("/api/v1/impact-scopes").param("changeEventId", changeEventId);
	}

	private static List<String> ids(ResultActions result, String path) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
				path);
	}

	private static EntityRef entity(RelationStore store, String pack, String key) {
		EntityRef ref = new EntityRef(pack, "T", key);
		store.saveEntity(ref, RECORDED_AT);
		return ref;
	}

	/** 시험용 Pack 의 사본을 적재하고 판을 차례로 올린다. 마지막 판에서 변동과 조치가 나온다. */
	private void prepare(String packId) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] edition : EDITIONS) {
			this.processing.submit(packId, edition[0], edition[1], Files.readAllBytes(pack.resolve("data/" + edition[1])),
					LocalDate.parse(edition[2]));
		}
	}
}
