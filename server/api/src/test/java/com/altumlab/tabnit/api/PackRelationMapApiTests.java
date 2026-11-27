package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditRecord;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.NewRelation;
import com.altumlab.tabnit.core.PackData;
import com.altumlab.tabnit.core.PackDataQuery;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.Relation;
import com.altumlab.tabnit.core.RelationStore;
import com.altumlab.tabnit.core.ValidityPeriod;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A-20: 가운데 개체 없이 Pack 의 지도를 여는 API (docs/api/openapi.yaml 의 getPackRelationMap, D7 4절 "Pack 의 전체
 * 지도", D2 AD-419).
 *
 * <p>정답은 이 시험이 따로 센다: 그 날짜에 유효한 관계를 모두 읽어 (core 의 PackDataQuery) 닿은 개체와 유형별 수를
 * 센다. 제품의 전체 지도 조회를 쓰지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PackRelationMapApiTests {

	private static final String[][] EDITIONS = { { "program", "program-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v1.csv", "2026-01-01" },
			{ "guideline-program", "guideline-program-v1.csv", "2026-01-01" }, { "team", "team-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v2.csv", "2026-03-01" },
			{ "guideline-program", "guideline-program-v2.csv", "2026-03-01" } };

	private static final RequestPostProcessor OFFICER = user("pm-officer").roles("OFFICER");

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@Autowired
	PackDataQuery packData;

	@Autowired
	AuditLog audit;

	@TempDir
	Path temp;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	@Test
	void 한도_안의_Pack_은_개체와_관계가_따로_센_것과_같고_조회_시점마다_다르다() throws Exception {
		String packId = "pm-small";
		load(packId);
		String early = null;
		for (String day : List.of("2026-01-01", "2026-03-01")) {
			PackData data = this.packData.packDataAsOf(packId, LocalDate.parse(day), 1_000_000);
			assertThat(data.relations()).as("관계가 있어야 이 시험에 뜻이 있다").isNotEmpty();
			TreeSet<String> connected = new TreeSet<>();
			TreeSet<String> relationIds = new TreeSet<>();
			for (Relation relation : data.relations()) {
				connected.add(relation.sourceEntityId());
				connected.add(relation.targetEntityId());
				relationIds.add(relation.id());
			}
			Map<String, Integer> byType = new TreeMap<>();
			for (EntityRef entity : data.entities()) {
				if (connected.contains(entity.id())) {
					byType.merge(entity.entityType(), 1, Integer::sum);
				}
			}

			String body = body("/api/v1/pack-relation-map?packId=" + packId + "&asOf=" + day);

			assertThat(JsonPath.<Boolean>read(body, "$.grouped")).isFalse();
			assertThat(JsonPath.<Integer>read(body, "$.connectedEntities")).isEqualTo(connected.size());
			assertThat(JsonPath.<Integer>read(body, "$.isolatedEntities")).isEqualTo(data.entities().size() - connected.size());
			assertThat(JsonPath.<Integer>read(body, "$.relations")).isEqualTo(relationIds.size());
			assertThat(new TreeSet<>(JsonPath.<List<String>>read(body, "$.nodes[*].entityId"))).isEqualTo(connected);
			assertThat(JsonPath.<List<String>>read(body, "$.nodes[*].entityId")).hasSize(connected.size());
			assertThat(new TreeSet<>(JsonPath.<List<String>>read(body, "$.edges[*].id"))).isEqualTo(relationIds);
			// 유형마다의 수가 따로 센 것과 같고, 더하면 전체다. 유형의 이름 순이다.
			assertThat(JsonPath.<List<String>>read(body, "$.typeGroups[*].entityType")).containsExactlyElementsOf(byType.keySet());
			assertThat(JsonPath.<List<Integer>>read(body, "$.typeGroups[*].entityCount")).containsExactlyElementsOf(byType.values());
			assertThat(JsonPath.<List<Integer>>read(body, "$.relationGroups[*].relationCount").stream().mapToInt(i -> i).sum())
				.isEqualTo(relationIds.size());
			// 노드에는 개체가 유형, 키와 함께 있고 이름표는 Pack 의 정의의 것이다.
			assertThat(JsonPath.<List<String>>read(body, "$.nodes[*].entity.entityKey")).doesNotContainNull();
			assertThat(JsonPath.<String>read(body, "$.typeGroups[0].entityTypeLabel")).isNotBlank();
			assertThat(JsonPath.<String>read(body, "$.asOf")).isEqualTo(day);
			assertThat(JsonPath.<Integer>read(body, "$.maxNodes")).isEqualTo(200);
			// 두 번 물어도 같다.
			assertThat(body("/api/v1/pack-relation-map?packId=" + packId + "&asOf=" + day)).isEqualTo(body);
			if (early == null) {
				early = body;
			}
			else {
				assertThat(JsonPath.<List<String>>read(body, "$.edges[*].id")).as("판이 바뀐 날의 관계는 앞의 날과 다르다")
					.isNotEqualTo(JsonPath.<List<String>>read(early, "$.edges[*].id"));
			}
		}
		// 판이 들어오기 전의 날: 이어진 것이 없다.
		this.mvc.perform(get("/api/v1/pack-relation-map").with(OFFICER).param("packId", packId).param("asOf", "2025-12-31"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.connectedEntities", is(0)))
			.andExpect(jsonPath("$.grouped", is(false)))
			.andExpect(jsonPath("$.nodes.length()", is(0)))
			.andExpect(jsonPath("$.typeGroups.length()", is(0)));
	}

	@Test
	void 이어진_개체가_한도를_넘으면_유형별로_묶고_수를_더한_것이_전체와_같다() throws Exception {
		String packId = "pm-large";
		load(packId);
		String before = body("/api/v1/pack-relation-map?packId=" + packId + "&asOf=2026-03-01");
		int already = JsonPath.<Integer>read(before, "$.connectedEntities");
		// 한도를 넘도록 지어낸 개체와 관계를 그 Pack 에 더 넣는다 (정의에 없는 유형이다: 이름표는 유형의 이름이 된다).
		RelationStore store = new RelationStore(this.dataSource);
		Instant at = Instant.parse("2026-01-01T00:00:00Z");
		EntityRef hub = new EntityRef(packId, "MadeHub", "h");
		store.saveEntity(hub, at);
		for (int i = 0; i < 230; i++) {
			EntityRef leaf = new EntityRef(packId, "MadeLeaf", String.format("l%03d", i));
			store.saveEntity(leaf, at);
			store.saveRelation(new NewRelation(hub, leaf, "made-of", new ValidityPeriod(LocalDate.parse("2026-01-01"), null),
					new Provenance("made", 1)), at);
		}

		String body = body("/api/v1/pack-relation-map?packId=" + packId + "&asOf=2026-03-01");

		assertThat(JsonPath.<Boolean>read(body, "$.grouped")).isTrue();
		assertThat(JsonPath.<List<Object>>read(body, "$.nodes")).isEmpty();
		assertThat(JsonPath.<List<Object>>read(body, "$.edges")).isEmpty();
		assertThat(JsonPath.<Integer>read(body, "$.connectedEntities")).isEqualTo(already + 231);
		assertThat(JsonPath.<List<Integer>>read(body, "$.typeGroups[*].entityCount").stream().mapToInt(i -> i).sum())
			.isEqualTo(already + 231);
		assertThat(JsonPath.<List<Integer>>read(body, "$.relationGroups[*].relationCount").stream().mapToInt(i -> i).sum())
			.isEqualTo(JsonPath.<Integer>read(body, "$.relations"));
		assertThat(JsonPath.<List<Integer>>read(body, "$.typeGroups[?(@.entityType == 'MadeLeaf')].entityCount"))
			.containsExactly(230);
		assertThat(JsonPath.<List<String>>read(body, "$.typeGroups[?(@.entityType == 'MadeLeaf')].entityTypeLabel"))
			.containsExactly("MadeLeaf");
		assertThat(JsonPath.<List<Integer>>read(body,
				"$.relationGroups[?(@.relationType == 'made-of' && @.sourceEntityType == 'MadeHub' && @.targetEntityType == 'MadeLeaf')].relationCount"))
			.containsExactly(230);
		// 묶음의 순서가 정해져 있다: 유형의 이름의 글자 코드 순.
		List<String> types = JsonPath.read(body, "$.typeGroups[*].entityType");
		assertThat(types).isSorted();
		assertThat(body("/api/v1/pack-relation-map?packId=" + packId + "&asOf=2026-03-01")).isEqualTo(body);
	}

	@Test
	void 틀린_입력은_어느_항목인지_알리며_거부한다() throws Exception {
		String packId = "pm-bad";
		load(packId);

		problem("/api/v1/pack-relation-map", 400, "MISSING_PACK_ID", "packId");
		problem("/api/v1/pack-relation-map?packId=pm-no-such-pack", 404, "PACK_NOT_FOUND", "packId");
		problem("/api/v1/pack-relation-map?packId=" + packId + "&asOf=2026-02-30", 400, "INVALID_AS_OF", "asOf");
		problem("/api/v1/pack-relation-map?packId=" + packId + "&asOf=어제", 400, "INVALID_AS_OF", "asOf");
		// 조회 시점을 비우면 오늘로 답하고 그 날짜를 알린다.
		this.mvc.perform(get("/api/v1/pack-relation-map").with(OFFICER).param("packId", packId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.asOf").isNotEmpty());
	}

	@Test
	void 로그인해야_보고_본_것은_감사_기록에_남는다() throws Exception {
		String packId = "pm-audit";
		load(packId);

		this.mvc.perform(get("/api/v1/pack-relation-map").param("packId", packId)).andExpect(status().isUnauthorized());
		long mark = this.audit.lastId();
		body("/api/v1/pack-relation-map?packId=" + packId + "&asOf=2026-03-01");

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).extracting(AuditRecord::action).containsExactly("getPackRelationMap", "getPackRelationMap");
		assertThat(rows.get(1).target()).contains(packId);
		// 거부된 요청(없는 Pack)의 줄에도 조회 시점이 남는다.
		long rejectedMark = this.audit.lastId();
		this.mvc.perform(get("/api/v1/pack-relation-map").with(OFFICER).param("packId", "pm-no-such-pack").param("asOf", "2026-04-01"))
			.andExpect(status().isNotFound());
		List<AuditRecord> rejected = this.audit.after(rejectedMark);
		assertThat(rejected).hasSize(2);
		assertThat(rejected.get(1).asOf()).isEqualTo(LocalDate.parse("2026-04-01"));
	}

	private String body(String url) throws Exception {
		return this.mvc.perform(get(url).with(OFFICER)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
	}

	private void problem(String url, int status, String code, String field) throws Exception {
		this.mvc.perform(get(url).with(OFFICER))
			.andExpect(status().is(status))
			.andExpect(jsonPath("$.code", is(code)))
			.andExpect(jsonPath("$.field", is(field)));
	}

	private void load(String packId) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] edition : EDITIONS) {
			Path file = pack.resolve("data/" + edition[1]);
			this.mvc.perform(multipart("/api/v1/editions").with(OFFICER).with(csrf()).param("packId", packId)
					.param("inputSourceId", edition[0]).param("effectiveDate", edition[2])
					.file(new MockMultipartFile("file", edition[1], "text/csv", Files.readAllBytes(file))))
				.andExpect(status().isOk());
		}
	}
}
