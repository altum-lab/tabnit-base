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
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A-21: 개체 하나에서 출발해 영향 범위를 계산해 답하는 API (docs/api/openapi.yaml 의 getImpactFromEntity,
 * D7 6절 "출발 개체로 영향을 묻는다", D2 AD-429).
 *
 * <p>수용 기준: 개체 하나와 조회 시점으로 영향 범위를 답한다. 저장하지 않는다. 답에 조회 시점과 대상마다의 근거 경로가
 * 있다. 같은 변동 대상과 날짜로 물으면 저장한 영향 범위와 같다 (두 예시 Pack 의 시나리오의 변동으로 견준다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ImpactFromEntityApiTests {

	private static final Pattern STEP = Pattern
		.compile("inputSource:\\s*([^,\\s]+)\\s*,\\s*file:\\s*([^,\\s]+)\\s*,\\s*effectiveDate:\\s*(\\d{4}-\\d{2}-\\d{2})");

	private static final RequestPostProcessor OFFICER = user("im-officer").roles("OFFICER");

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@Autowired
	AuditLog audit;

	@TempDir
	Path temp;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	// ── 수용 기준: 저장한 영향 범위와 같다 ───────────────────────────────

	@Test
	void 같은_변동_대상과_날짜로_물으면_저장한_영향_범위와_같다_두_예시_Pack_의_모든_변동으로() throws Exception {
		// 저장한 영향 범위는 판을 올린 그때의 자료로 계산한 것이다 (뒤에 자료가 바뀌어도 달라지지 않는다).
		// 그래서 단계를 올릴 때마다, 그 단계에서 생긴 변동을 다음 단계를 올리기 전에 견준다: 같은 자료로 견주는 것이다.
		for (String source : List.of("manufacturing-safety", "retirement-pension")) {
			String packId = "im-" + source;
			TreeSet<String> seen = new TreeSet<>();
			int[] compared = { 0 };
			int[] withTargets = { 0 };
			loadExample(source, packId, () -> {
				for (Map<String, Object> change : changes(packId)) {
					String changeEventId = (String) change.get("changeEventId");
					if (!seen.add(changeEventId) || !"PROCESSED".equals(change.get("status"))) {
						continue;
					}
					// 변동 대상이 하나인 변동만 견준다: 이 API 는 개체 하나에서 출발한다.
					List<String> starts = targetsOf(changeEventId);
					if (starts.size() != 1) {
						continue;
					}
					String stored = read(get("/api/v1/impact-scopes").param("changeEventId", changeEventId).param("limit", "200"));
					int total = JsonPath.read(stored, "$.total");
					assertThat(total).as("한 쪽에 다 들어오는 영향 범위여야 견준다: " + changeEventId).isLessThanOrEqualTo(200);
					String asked = read(get("/api/v1/impact").param("entityId", starts.get(0))
						.param("asOf", JsonPath.<String>read(stored, "$.asOfEffective")).param("includePreviousDay", "true")
						.param("limit", "200"));

					String what = source + " " + changeEventId;
					assertThat(JsonPath.<String>read(asked, "$.asOfPrevious")).as(what)
						.isEqualTo(JsonPath.<String>read(stored, "$.asOfPrevious"));
					assertThat(JsonPath.<Integer>read(asked, "$.maxDepth")).as(what)
						.isEqualTo(JsonPath.<Integer>read(stored, "$.maxDepth"));
					assertThat(JsonPath.<Boolean>read(asked, "$.truncated")).as(what)
						.isEqualTo(JsonPath.<Boolean>read(stored, "$.truncated"));
					assertThat(JsonPath.<Integer>read(asked, "$.total")).as(what).isEqualTo(total);
					// 대상과 깊이와 근거 경로(시작, 날짜, 단계마다의 관계)가 순서까지 같다. 표시 이름까지 통째로 견준다.
					assertThat(JsonPath.<Object>read(asked, "$.targets")).as(what).isEqualTo(JsonPath.<Object>read(stored, "$.targets"));
					assertThat(JsonPath.<Object>read(asked, "$.countsByType")).as(what)
						.isEqualTo(JsonPath.<Object>read(stored, "$.countsByType"));
					assertThat(JsonPath.<Object>read(asked, "$.countsByDepth")).as(what)
						.isEqualTo(JsonPath.<Object>read(stored, "$.countsByDepth"));
					compared[0]++;
					withTargets[0] += total > 0 ? 1 : 0;
				}
			});
			assertThat(compared[0]).as(source + ": 견준 변동의 수").isGreaterThan(3);
			assertThat(withTargets[0]).as(source + ": 영향 대상이 있는 변동의 수").isGreaterThan(1);
		}
	}

	// ── 수용 기준: 조회 시점과 근거 경로, 저장하지 않는다 ─────────────────────

	@Test
	void 조회_시점_하루의_관계만_따라가고_전날을_함께_보라고_하면_전날_끝난_관계로_닿는_대상도_나온다() throws Exception {
		String packId = "im-day";
		loadExample("manufacturing-safety", packId);
		// 2026-10-01 에 작업인원 W-1003 의 배치가 옮겨진다 (Pack 의 시나리오). 공정 가-세정-01 에서 그 인원으로 가는 관계는
		// 2026-10-01 에 끝난다: 그 날에는 유효하지 않고 전날에는 유효하다.
		String process = packId + "|제조공정|가-세정-01";
		String worker = packId + "|작업인원|W-1003";

		String day = read(get("/api/v1/impact").param("entityId", process).param("asOf", "2026-10-01").param("limit", "200"));
		String both = read(get("/api/v1/impact").param("entityId", process).param("asOf", "2026-10-01")
			.param("includePreviousDay", "true").param("limit", "200"));

		assertThat(JsonPath.<String>read(day, "$.asOf")).isEqualTo("2026-10-01");
		assertThat(JsonPath.<Object>read(day, "$.asOfPrevious")).isNull();
		assertThat(JsonPath.<String>read(both, "$.asOfPrevious")).isEqualTo("2026-09-30");
		assertThat(JsonPath.<String>read(day, "$.entityId")).isEqualTo(process);
		assertThat(JsonPath.<String>read(day, "$.packId")).isEqualTo(packId);
		List<String> onDay = JsonPath.read(day, "$.targets[*].entityId");
		List<String> withPrevious = JsonPath.read(both, "$.targets[*].entityId");
		assertThat(onDay).doesNotContain(worker).doesNotContain(process);
		assertThat(withPrevious).contains(worker).containsAll(onDay);
		// 그 대상의 근거 경로는 전날의 관계다.
		assertThat(JsonPath.<List<String>>read(both, "$.targets[?(@.entityId == '" + worker + "')].path.asOf"))
			.containsExactly("2026-09-30");
		// 대상마다 근거 경로가 있고 출발 개체에서 시작한다.
		List<String> starts = JsonPath.read(both, "$.targets[*].path.startEntityId");
		assertThat(starts).hasSize(withPrevious.size()).containsOnly(process);
		List<Integer> stepCounts = JsonPath.read(both, "$.targets[*].path.steps.length()");
		List<Integer> depths = JsonPath.read(both, "$.targets[*].depth");
		assertThat(stepCounts).isEqualTo(depths);
		// 두 번 물어도 같다.
		assertThat(read(get("/api/v1/impact").param("entityId", process).param("asOf", "2026-10-01")
			.param("includePreviousDay", "true").param("limit", "200"))).isEqualTo(both);
	}

	@Test
	void 저장하지_않는다_부르기_전과_뒤에_저장된_영향_범위와_조치와_변동_이벤트의_수가_같다() throws Exception {
		String packId = "im-stores-nothing";
		loadExample("retirement-pension", packId);
		String entityId = targetsOf((String) changes(packId).get(0).get("changeEventId")).get(0);
		JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
		List<String> tables = List.of("change_event", "change_target", "impact_scope", "impact_target", "impact_path_step", "action",
				"edition", "entity", "relation", "attribute_value");
		List<Long> before = tables.stream().map(table -> jdbc.queryForObject("select count(*) from " + table, Long.class)).toList();
		long mark = this.audit.lastId();

		for (int i = 0; i < 3; i++) {
			read(get("/api/v1/impact").param("entityId", entityId).param("asOf", "2026-06-01").param("includePreviousDay", "true"));
		}

		List<Long> after = tables.stream().map(table -> jdbc.queryForObject("select count(*) from " + table, Long.class)).toList();
		assertThat(after).as(tables.toString()).isEqualTo(before);
		// 부른 것은 감사 기록에 남는다: 요청마다 두 줄, 조회 시점과 함께.
		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).extracting(AuditRecord::action).containsOnly("getImpactFromEntity").hasSize(6);
		// 조회 시점은 결과의 줄에 적힌다 (시작의 줄은 날짜를 정하기 전에 남는다).
		assertThat(rows.stream().filter(row -> row.startedId() != null).map(row -> String.valueOf(row.asOf())).toList())
			.containsOnly("2026-06-01").hasSize(3);
		assertThat(rows).extracting(AuditRecord::target).allMatch(target -> target.contains(entityId));
	}

	// ── 일반: 최대 깊이, 나눠 주기, 틀린 입력 ───────────────────────────────

	@Test
	void 최대_깊이는_Pack_의_기본값_안에서_줄일_수_있고_나눠_받은_것을_이으면_한_번에_받은_것과_같다() throws Exception {
		String packId = "im-depth";
		loadExample("manufacturing-safety", packId);
		String entityId = packId + "|제조공정|가-세정-01";
		String whole = read(get("/api/v1/impact").param("entityId", entityId).param("asOf", "2026-06-01").param("limit", "200"));
		int packDepth = JsonPath.read(whole, "$.maxDepth");
		int total = JsonPath.read(whole, "$.total");
		assertThat(packDepth).isGreaterThan(1);
		assertThat(total).isGreaterThan(3);

		// 깊이 1 로 물으면 깊이 1 의 대상만이고, 그 너머에 대상이 있으므로 잘렸다고 알린다.
		String shallow = read(get("/api/v1/impact").param("entityId", entityId).param("asOf", "2026-06-01").param("maxDepth", "1")
			.param("limit", "200"));
		List<Integer> depths = JsonPath.read(shallow, "$.targets[*].depth");
		assertThat(depths).isNotEmpty().containsOnly(1);
		assertThat(JsonPath.<Integer>read(shallow, "$.maxDepth")).isEqualTo(1);
		assertThat(JsonPath.<Boolean>read(shallow, "$.truncated")).isTrue();
		List<String> depthOne = JsonPath.read(whole, "$.targets[?(@.depth == 1)].entityId");
		assertThat(JsonPath.<List<String>>read(shallow, "$.targets[*].entityId")).isEqualTo(depthOne);

		// 나눠 받은 것을 이으면 한 번에 받은 것과 순서까지 같다. 전체 수와 유형별·깊이별 수는 쪽마다 같다.
		List<String> joined = new ArrayList<>();
		for (int offset = 0; offset < total; offset += 3) {
			String page = read(get("/api/v1/impact").param("entityId", entityId).param("asOf", "2026-06-01")
				.param("offset", String.valueOf(offset)).param("limit", "3"));
			assertThat(JsonPath.<Integer>read(page, "$.total")).isEqualTo(total);
			assertThat(JsonPath.<Object>read(page, "$.countsByType")).isEqualTo(JsonPath.<Object>read(whole, "$.countsByType"));
			joined.addAll(JsonPath.<List<String>>read(page, "$.targets[*].entityId"));
		}
		assertThat(joined).isEqualTo(JsonPath.<List<String>>read(whole, "$.targets[*].entityId"));
		// 순서: 깊이, 개체 유형, 개체의 id.
		List<Integer> order = JsonPath.read(whole, "$.targets[*].depth");
		assertThat(order).isSorted();
		// 수를 더하면 전체다.
		List<Integer> byType = JsonPath.read(whole, "$.countsByType[*].count");
		assertThat(byType.stream().mapToInt(Integer::intValue).sum()).isEqualTo(total);
	}

	@Test
	void 관계가_없는_개체와_자료가_들어오기_전의_날짜는_빈_영향_범위다() throws Exception {
		String packId = "im-empty";
		loadExample("manufacturing-safety", packId);
		String entityId = packId + "|제조공정|가-세정-01";

		String before = read(get("/api/v1/impact").param("entityId", entityId).param("asOf", "2025-12-31"));

		assertThat(JsonPath.<Integer>read(before, "$.total")).isZero();
		assertThat(JsonPath.<List<Object>>read(before, "$.targets")).isEmpty();
		assertThat(JsonPath.<List<Object>>read(before, "$.countsByType")).isEmpty();
		assertThat(JsonPath.<Boolean>read(before, "$.truncated")).isFalse();
	}

	@Test
	void 틀린_입력은_어느_항목인지_알리고_없는_개체는_404_다() throws Exception {
		String packId = "im-bad";
		loadExample("manufacturing-safety", packId);
		String entityId = packId + "|제조공정|가-세정-01";
		int packDepth = JsonPath.read(read(get("/api/v1/impact").param("entityId", entityId).param("asOf", "2026-06-01")), "$.maxDepth");

		problem(get("/api/v1/impact"), 400, "MISSING_ENTITY_ID", "entityId");
		problem(get("/api/v1/impact").param("entityId", ""), 400, "MISSING_ENTITY_ID", "entityId");
		problem(get("/api/v1/impact").param("entityId", entityId).param("asOf", "어제"), 400, "INVALID_AS_OF", "asOf");
		problem(get("/api/v1/impact").param("entityId", entityId).param("includePreviousDay", "네"), 400,
				"INVALID_INCLUDE_PREVIOUS_DAY", "includePreviousDay");
		problem(get("/api/v1/impact").param("entityId", entityId).param("maxDepth", "0"), 400, "INVALID_MAX_DEPTH", "maxDepth");
		problem(get("/api/v1/impact").param("entityId", entityId).param("maxDepth", "깊게"), 400, "INVALID_MAX_DEPTH", "maxDepth");
		// Pack 이 정한 한도보다 깊게는 묻지 못한다.
		problem(get("/api/v1/impact").param("entityId", entityId).param("maxDepth", String.valueOf(packDepth + 1)), 400,
				"INVALID_MAX_DEPTH", "maxDepth");
		problem(get("/api/v1/impact").param("entityId", entityId).param("limit", "201"), 400, "INVALID_LIMIT", "limit");
		// 전날을 함께 보라고 했는데 전날이 받는 날짜의 범위 밖이다.
		problem(get("/api/v1/impact").param("entityId", entityId).param("asOf", "0001-01-01").param("includePreviousDay", "true"),
				400, "INVALID_AS_OF", "asOf");
		this.mvc.perform(get("/api/v1/impact").with(OFFICER).param("entityId", packId + "|제조공정|없는-공정"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("ENTITY_NOT_FOUND")));
		// 로그인하지 않으면 401.
		this.mvc.perform(get("/api/v1/impact").param("entityId", entityId)).andExpect(status().isUnauthorized());
		// 기본값은 Pack 의 최대 깊이의 기본값이고, 같은 값을 적어도 된다.
		assertThat(JsonPath.<Integer>read(read(get("/api/v1/impact").param("entityId", entityId).param("asOf", "2026-06-01")
			.param("maxDepth", String.valueOf(packDepth))), "$.maxDepth")).isEqualTo(packDepth);
	}

	// ── 도우미 ────────────────────────────────────────────────

	private String read(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
		return this.mvc.perform(request.with(OFFICER)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
	}

	private void problem(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int status,
			String code, String field) throws Exception {
		this.mvc.perform(request.with(OFFICER)).andExpect(status().is(status)).andExpect(jsonPath("$.code", is(code)))
			.andExpect(jsonPath("$.field", is(field)));
	}

	/** 그 Pack 의 변동 이벤트 전부. 서버가 준 차례다. */
	private List<Map<String, Object>> changes(String packId) throws Exception {
		List<Map<String, Object>> all = new ArrayList<>();
		for (int offset = 0;; offset += 200) {
			String page = read(get("/api/v1/change-events").param("packId", packId).param("offset", String.valueOf(offset))
				.param("limit", "200"));
			List<Map<String, Object>> changes = JsonPath.read(page, "$.changes");
			all.addAll(changes);
			if (all.size() >= JsonPath.<Integer>read(page, "$.total")) {
				return all;
			}
		}
	}

	/** 그 변동 이벤트의 변동 대상. 저장소에서 바로 읽는다 (API 의 답에는 주 개체 하나만 있다). */
	private List<String> targetsOf(String changeEventId) {
		return new ArrayList<>(new TreeSet<>(new JdbcTemplate(this.dataSource)
			.queryForList("select entity_id from change_target where change_event_id = ?", String.class, changeEventId)));
	}

	/** 저장소의 예시 Pack 을 다른 식별자의 사본으로 적재하고, 그 시나리오의 단계를 차례로 올린다. */
	private void loadExample(String source, String packId) throws Exception {
		loadExample(source, packId, () -> {
		});
	}

	/** 단계 하나를 올릴 때마다 할 일. */
	private interface AfterStep {

		void run() throws Exception;
	}

	private void loadExample(String source, String packId, AfterStep afterStep) throws Exception {
		Path from = Path.of("../../packs", source);
		Path copy = this.temp.resolve(packId);
		try (java.util.stream.Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path target = copy.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(target);
				}
				else if (file.toString().endsWith(".ttl")) {
					Files.writeString(target, Files.readString(file, StandardCharsets.UTF_8).replace(source, packId),
							StandardCharsets.UTF_8);
				}
				else {
					Files.copy(file, target);
				}
			}
		}
		// 폴더의 이름이 Pack 의 식별자와 같아야 한다.
		new com.altumlab.tabnit.pack.PackLoader(this.dataSource).load(copy, java.time.Instant.parse("2026-07-01T00:00:00Z"));
		Matcher step = STEP.matcher(Files.readString(copy.resolve("questions/S-01.yaml"), StandardCharsets.UTF_8));
		int uploaded = 0;
		while (step.find()) {
			Path file = copy.resolve(step.group(2).trim());
			this.mvc.perform(multipart("/api/v1/editions").with(OFFICER).with(csrf()).param("packId", packId)
					.param("inputSourceId", step.group(1).trim()).param("effectiveDate", step.group(3))
					.file(new MockMultipartFile("file", file.getFileName().toString(), "text/csv", Files.readAllBytes(file))))
				.andExpect(status().isOk());
			uploaded++;
			afterStep.run();
		}
		assertThat(uploaded).as(source + " 의 시나리오의 단계").isGreaterThan(3);
	}

}
