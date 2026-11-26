package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditOutcome;
import com.altumlab.tabnit.common.AuditRecord;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.PackData;
import com.altumlab.tabnit.core.PackDataQuery;
import com.altumlab.tabnit.core.Relation;
import com.altumlab.tabnit.pack.PackLoader;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A-18: 두 개체 사이의 가장 짧은 경로를 묻는 API (docs/api/openapi.yaml 의 getRelationPath, D7 4절 "두 개체 사이의 경로",
 * D2 AD-413).
 *
 * <p>가장 짧은 길이의 정답은 이 시험이 따로 센다: 그 날짜에 유효한 관계를 모두 읽어 (core 의 PackDataQuery) 방향을
 * 가리지 않고 너비 우선으로 넓힌다. 제품의 경로 계산을 쓰지 않는다. 저장소의 Pack 셋의 사본으로 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RelationPathApiTests {

	private static final Path PACKS = Path.of("../../packs");

	private static final Pattern STEP = Pattern
		.compile("inputSource:\\s*([^,\\s]+)\\s*,\\s*file:\\s*([^,\\s]+)\\s*,\\s*effectiveDate:\\s*(\\d{4}-\\d{2}-\\d{2})");

	private static final RequestPostProcessor OFFICER = user("rp-officer").roles("OFFICER");

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

	// ── 수용 기준: 가장 짧은 경로의 길이가 따로 센 것과 같다 (Pack 셋) ─────────────────

	@Test
	void 시험용_Pack_에서_경로의_길이가_따로_센_가장_짧은_길이와_같다() throws Exception {
		comparePaths("test-mini", "rp-mini", 1);
	}

	@Test
	void 제조_안전_Pack_에서_경로의_길이가_따로_센_가장_짧은_길이와_같다() throws Exception {
		comparePaths("manufacturing-safety", "rp-manufacturing", 7);
	}

	@Test
	void 퇴직연금_Pack_에서_경로의_길이가_따로_센_가장_짧은_길이와_같다() throws Exception {
		comparePaths("retirement-pension", "rp-pension", 11);
	}

	/**
	 * Pack 의 사본에 시나리오의 첫날의 판을 모두 올리고, 개체의 짝을 골라 API 의 답을 따로 센 길이와 견준다.
	 *
	 * @param stride 짝을 고르는 간격. 개체를 식별자 순으로 세워 이 간격으로 뽑은 것끼리 짝짓는다
	 */
	private void comparePaths(String source, String packId, int stride) throws Exception {
		LocalDate day = loadFirstDay(source, packId);
		PackData data = this.packData.packDataAsOf(packId, day, 1_000_000);
		assertThat(data.complete()).isTrue();
		assertThat(data.relations()).as("관계가 있어야 이 시험에 뜻이 있다").isNotEmpty();
		Map<String, List<String>> neighbors = new TreeMap<>();
		for (EntityRef entity : data.entities()) {
			neighbors.put(entity.id(), new ArrayList<>());
		}
		for (Relation relation : data.relations()) {
			neighbors.get(relation.sourceEntityId()).add(relation.targetEntityId());
			neighbors.get(relation.targetEntityId()).add(relation.sourceEntityId());
		}
		List<String> picked = new ArrayList<>();
		List<String> all = new ArrayList<>(neighbors.keySet());
		for (int i = 0; i < all.size(); i += stride) {
			picked.add(all.get(i));
		}
		int found = 0;
		int missing = 0;
		int compared = 0;
		for (int i = 0; i < picked.size(); i++) {
			String from = picked.get(i);
			Map<String, Integer> distance = distances(neighbors, from);
			// 짝마다 다 묻지 않고 이웃한 몇과만 묻는다: 수백 번이면 충분하다.
			for (int j = i; j < Math.min(picked.size(), i + 4); j++) {
				String to = picked.get(j);
				Integer expected = distance.get(to);
				String body = path(from, to, day.toString()).andExpect(status().isOk()).andReturn().getResponse()
					.getContentAsString(StandardCharsets.UTF_8);
				compared++;
				boolean limit = JsonPath.read(body, "$.limitReached");
				if (expected != null && expected <= RelationPathController.DEFAULT_MAX_LENGTH) {
					assertThat(JsonPath.<Boolean>read(body, "$.found")).as(from + " → " + to).isTrue();
					assertThat(JsonPath.<Integer>read(body, "$.length")).as(from + " → " + to).isEqualTo(expected);
					assertThat(JsonPath.<List<Object>>read(body, "$.steps")).hasSize(expected);
					// 경로는 출발 개체에서 시작해 도착 개체에서 끝나고, 걸음마다 그 관계의 두 끝을 잇는다.
					List<String> walked = JsonPath.read(body, "$.entities[*].entityId");
					assertThat(walked).hasSize(expected + 1).startsWith(from).endsWith(to).doesNotHaveDuplicates();
					for (int step = 0; step < expected; step++) {
						String source0 = JsonPath.read(body, "$.steps[" + step + "].relation.sourceEntityId");
						String target0 = JsonPath.read(body, "$.steps[" + step + "].relation.targetEntityId");
						assertThat(List.of(source0, target0)).containsExactlyInAnyOrder(walked.get(step), walked.get(step + 1));
					}
					found++;
				}
				else {
					assertThat(JsonPath.<Boolean>read(body, "$.found")).as(from + " → " + to).isFalse();
					// 이어져 있지만 한도보다 길면 한도에서 멈춘 것이다.
					if (expected != null) {
						assertThat(limit).as(from + " → " + to).isTrue();
					}
					missing++;
				}
			}
		}
		assertThat(found).as(packId + ": 찾은 짝이 있어야 이 시험에 뜻이 있다 (견준 짝 " + compared + ")").isGreaterThan(3);
		assertThat(found + missing).isEqualTo(compared);
	}

	/** 방향을 가리지 않고 너비 우선으로 센 가장 짧은 거리. 닿지 않는 개체는 없다. */
	private static Map<String, Integer> distances(Map<String, List<String>> neighbors, String from) {
		Map<String, Integer> distance = new TreeMap<>();
		distance.put(from, 0);
		ArrayDeque<String> queue = new ArrayDeque<>(List.of(from));
		while (!queue.isEmpty()) {
			String current = queue.poll();
			for (String next : neighbors.get(current)) {
				if (!distance.containsKey(next)) {
					distance.put(next, distance.get(current) + 1);
					queue.add(next);
				}
			}
		}
		return distance;
	}

	// ── 답의 모양, 경계, 틀린 입력 ───────────────────────────────────────

	@Test
	void 경로의_걸음마다_관계와_두_개체가_이름과_함께_오고_두_번_물어도_같다() throws Exception {
		String packId = "rp-shape";
		LocalDate day = loadFirstDay("test-mini", packId);
		String from = packId + "|운영지침|G-01";
		String to = packId + "|전담팀|T-01";

		String body = path(from, to, day.toString()).andExpect(status().isOk())
			.andExpect(jsonPath("$.asOf", is(day.toString())))
			.andExpect(jsonPath("$.fromEntityId", is(from)))
			.andExpect(jsonPath("$.toEntityId", is(to)))
			.andExpect(jsonPath("$.found", is(true)))
			.andExpect(jsonPath("$.limitReached", is(false)))
			.andExpect(jsonPath("$.length", is(3)))
			.andExpect(jsonPath("$.maxLength", is(RelationPathController.DEFAULT_MAX_LENGTH)))
			.andExpect(jsonPath("$.entities", hasSize(4)))
			.andExpect(jsonPath("$.entities[0].entityId", is(from)))
			.andExpect(jsonPath("$.entities[0].entityTypeLabel", is("운영지침")))
			.andExpect(jsonPath("$.entities[3].entityId", is(to)))
			.andExpect(jsonPath("$.steps[0].stepNo", is(1)))
			.andExpect(jsonPath("$.steps[0].from.entityId", is(from)))
			.andExpect(jsonPath("$.steps[2].to.entityId", is(to)))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<String>read(body, "$.steps[0].relation.relationType")).isNotBlank();
		assertThat(JsonPath.<String>read(body, "$.entities[3].displayName")).isNotBlank();
		assertThat(path(from, to, day.toString()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
			.isEqualTo(body);
	}

	@Test
	void 그_날짜에_유효한_관계가_없으면_경로가_없다고_답하고_같은_개체는_길이_0_이다() throws Exception {
		String packId = "rp-none";
		loadFirstDay("test-mini", packId);
		String from = packId + "|운영지침|G-01";
		String to = packId + "|전담팀|T-01";

		// 자료가 들어오기 전의 날짜: 개체는 있지만 유효한 관계가 없다.
		path(from, to, "2025-12-31").andExpect(status().isOk())
			.andExpect(jsonPath("$.found", is(false)))
			.andExpect(jsonPath("$.limitReached", is(false)))
			.andExpect(jsonPath("$.length", nullValue()))
			.andExpect(jsonPath("$.steps", hasSize(0)))
			.andExpect(jsonPath("$.entities", hasSize(0)));
		path(from, from, "2026-02-01").andExpect(status().isOk())
			.andExpect(jsonPath("$.found", is(true)))
			.andExpect(jsonPath("$.length", is(0)))
			.andExpect(jsonPath("$.steps", hasSize(0)))
			.andExpect(jsonPath("$.entities", hasSize(1)));
	}

	@Test
	void 틀린_입력은_어느_항목인지_알리며_거부한다() throws Exception {
		String packId = "rp-bad";
		loadFirstDay("test-mini", packId);
		String otherPack = "rp-bad-other";
		loadFirstDay("test-mini", otherPack);
		String from = packId + "|운영지침|G-01";
		String to = packId + "|전담팀|T-01";

		problem(this.mvc.perform(get("/api/v1/relation-path").with(OFFICER).param("toEntityId", to)), 400,
				"MISSING_FROM_ENTITY_ID", "fromEntityId");
		problem(this.mvc.perform(get("/api/v1/relation-path").with(OFFICER).param("fromEntityId", from)), 400,
				"MISSING_TO_ENTITY_ID", "toEntityId");
		problem(path(from + "\u0000", to, null), 400, "INVALID_ENTITY_ID", "fromEntityId");
		problem(path(from, to + "\u0000", null), 400, "INVALID_ENTITY_ID", "toEntityId");
		problem(path(from, to, "2026-13-01"), 400, "INVALID_AS_OF", "asOf");
		path(packId + "|운영지침|없는-키", to, "2026-02-01").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("ENTITY_NOT_FOUND")));
		path(from, packId + "|전담팀|없는-키", "2026-02-01").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("ENTITY_NOT_FOUND")));
		// 다른 Pack 의 두 개체는 이어질 수 없다: 없는 경로가 아니라 틀린 입력이다.
		problem(path(from, otherPack + "|전담팀|T-01", "2026-02-01"), 400, "DIFFERENT_PACKS", "toEntityId");
	}

	@Test
	void 조회_시점이_없으면_서버가_정한_날짜를_답에_적고_로그인해야_하며_감사_기록에_남는다() throws Exception {
		String packId = "rp-audit";
		loadFirstDay("test-mini", packId);
		String from = packId + "|운영지침|G-01";
		String to = packId + "|전담팀|T-01";

		this.mvc.perform(get("/api/v1/relation-path").param("fromEntityId", from).param("toEntityId", to))
			.andExpect(status().isUnauthorized());
		long mark = this.audit.lastId();
		String body = path(from, to, null).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<String>read(body, "$.asOf")).matches("\\d{4}-\\d{2}-\\d{2}");
		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).extracting(AuditRecord::action).containsExactly("getRelationPath", "getRelationPath");
		assertThat(rows.get(1).outcome()).isEqualTo(AuditOutcome.SUCCESS);
		assertThat(rows.get(1).target()).contains(from).contains(to);
		assertThat(rows.get(1).asOf()).hasToString(JsonPath.<String>read(body, "$.asOf"));
	}

	// ── 도우미 ────────────────────────────────────────────────

	private ResultActions path(String from, String to, String asOf) throws Exception {
		var request = get("/api/v1/relation-path").with(OFFICER).param("fromEntityId", from).param("toEntityId", to);
		if (asOf != null) {
			request.param("asOf", asOf);
		}
		return this.mvc.perform(request);
	}

	private static void problem(ResultActions result, int status, String code, String field) throws Exception {
		result.andExpect(status().is(status)).andExpect(jsonPath("$.code", is(code))).andExpect(jsonPath("$.field", is(field)));
	}

	/**
	 * 저장소의 Pack 을 다른 식별자의 사본으로 적재하고, 시나리오의 첫날의 판을 차례대로 모두 올린다.
	 * 다른 시험과 자료가 섞이지 않게 사본을 쓴다.
	 *
	 * @return 첫날. 그 날짜의 관계로 견준다
	 */
	private LocalDate loadFirstDay(String source, String packId) throws Exception {
		Path from = PACKS.resolve(source);
		Path copy = this.temp.resolve(String.valueOf(System.nanoTime())).resolve(packId);
		try (Stream<Path> files = Files.walk(from)) {
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
		new PackLoader(this.dataSource).load(copy, Instant.parse("2026-07-01T00:00:00Z"));
		LocalDate first = null;
		try (Stream<Path> scenarios = Files.list(copy.resolve("questions"))) {
			Path scenario = scenarios.filter(Files::isRegularFile).sorted().findFirst().orElseThrow();
			Matcher step = STEP.matcher(Files.readString(scenario, StandardCharsets.UTF_8));
			while (step.find()) {
				LocalDate date = LocalDate.parse(step.group(3));
				if (first == null) {
					first = date;
				}
				if (!date.equals(first)) {
					break;
				}
				Path file = copy.resolve(step.group(2).trim());
				this.mvc.perform(multipart("/api/v1/editions").with(OFFICER).with(csrf()).param("packId", packId)
						.param("inputSourceId", step.group(1).trim()).param("effectiveDate", date.toString())
						.file(new MockMultipartFile("file", file.getFileName().toString(), "text/csv",
								Files.readAllBytes(file))))
					.andExpect(status().isOk());
			}
		}
		assertThat(first).as("시나리오에 단계가 있어야 한다").isNotNull();
		return first;
	}
}
