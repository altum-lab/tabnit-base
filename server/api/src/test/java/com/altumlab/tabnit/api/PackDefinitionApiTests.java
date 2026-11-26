package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditRecord;
import com.altumlab.tabnit.pack.PackLoader;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * B-19: 적재된 Pack 의 정의를 읽는 API (docs/api/openapi.yaml 의 getPackDefinition, D7 8절 "Pack 의 정의", D2 AD-415).
 *
 * <p>수용 기준의 "유형, 관계 유형, 규칙의 수가 Pack 의 파일과 같다"는 이 시험이 Pack 의 파일(Turtle)에서 따로 센다:
 * 선언의 줄을 글자로 센다. pack 의 읽는 코드를 쓰지 않는다. 저장소의 Pack 셋의 사본으로 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PackDefinitionApiTests {

	private static final Path PACKS = Path.of("../../packs");

	private static final RequestPostProcessor OFFICER = user("pd-officer").roles("OFFICER");

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

	// ── 수용 기준: 수가 Pack 의 파일과 같다 (Pack 셋) ──────────────────────────

	@Test
	void 시험용_Pack_의_정의의_수가_파일에서_따로_센_것과_같다() throws Exception {
		compareCounts("test-mini", "pd-mini");
	}

	@Test
	void 제조_안전_Pack_의_정의의_수가_파일에서_따로_센_것과_같다() throws Exception {
		compareCounts("manufacturing-safety", "pd-manufacturing");
	}

	@Test
	void 퇴직연금_Pack_의_정의의_수가_파일에서_따로_센_것과_같다() throws Exception {
		compareCounts("retirement-pension", "pd-pension");
	}

	private void compareCounts(String source, String packId) throws Exception {
		Path pack = load(source, packId);
		String ontology = Files.readString(pack.resolve("ontology.ttl"), StandardCharsets.UTF_8);
		String rules = Files.readString(pack.resolve("rules/rules.ttl"), StandardCharsets.UTF_8);
		StringBuilder mappings = new StringBuilder();
		try (Stream<Path> files = Files.list(pack.resolve("mappings"))) {
			for (Path file : files.sorted().toList()) {
				mappings.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
			}
		}

		String body = definition(packId);

		assertThat(JsonPath.<List<Object>>read(body, "$.entityTypes")).as("개체 유형").hasSize(count(ontology, "a owl:Class"));
		assertThat(JsonPath.<List<Object>>read(body, "$.relationTypes")).as("관계 유형")
			.hasSize(count(ontology, "a owl:ObjectProperty"));
		assertThat(JsonPath.<List<Object>>read(body, "$.attributes")).as("속성")
			.hasSize(count(ontology, "a owl:DatatypeProperty"));
		assertThat(JsonPath.<List<Object>>read(body, "$.rules")).as("규칙").hasSize(count(rules, "a tabnit:Rule"));
		assertThat(JsonPath.<List<Object>>read(body, "$.inputSources")).as("입력 자료")
			.hasSize(count(mappings.toString(), "a tabnit:Mapping"));
		assertThat(count(ontology, "a owl:Class")).as("세는 법이 맞아야 이 시험에 뜻이 있다").isGreaterThan(3);
		// 제약은 파일의 글 그대로다.
		assertThat(JsonPath.<String>read(body, "$.constraints"))
			.isEqualTo(Files.readString(pack.resolve("constraints.ttl"), StandardCharsets.UTF_8));
		// 목록의 순서: 이름의 글자 코드 순, 규칙은 번호 순, 입력 자료는 식별자 순.
		assertThat(JsonPath.<List<String>>read(body, "$.entityTypes[*].name")).isSorted().doesNotHaveDuplicates();
		assertThat(JsonPath.<List<String>>read(body, "$.relationTypes[*].name")).isSorted().doesNotHaveDuplicates();
		assertThat(JsonPath.<List<String>>read(body, "$.attributes[*].name")).isSorted().doesNotHaveDuplicates();
		assertThat(JsonPath.<List<String>>read(body, "$.rules[*].ruleNo")).isSorted().doesNotHaveDuplicates();
		assertThat(JsonPath.<List<String>>read(body, "$.inputSources[*].inputSourceId")).isSorted().doesNotHaveDuplicates();
		// 두 번 물어도 같다.
		assertThat(definition(packId)).isEqualTo(body);
	}

	/** 선언의 줄을 센다: "이름 a owl:Class" 처럼 그 글자가 든 줄. 주석의 줄은 세지 않는다. */
	private static int count(String turtle, String declaration) {
		Pattern line = Pattern.compile("^\\s*[^#\\s]\\S*\\s+" + Pattern.quote(declaration) + "\\b", Pattern.MULTILINE);
		return (int) line.matcher(turtle).results().count();
	}

	// ── 수용 기준: 무엇이 보이는가 ─────────────────────────────────────

	@Test
	void 유형과_속성과_관계_유형과_매핑과_규칙의_내용이_Pack_에_적힌_대로_보인다() throws Exception {
		String packId = "pd-shape";
		load("test-mini", packId);

		String body = definition(packId);

		assertThat(JsonPath.<String>read(body, "$.packId")).isEqualTo(packId);
		assertThat(JsonPath.<String>read(body, "$.label")).isNotBlank();
		assertThat(JsonPath.<Integer>read(body, "$.defaultMaxDepth")).isPositive();
		assertThat(JsonPath.<String>read(body, "$.loadedAt")).isEqualTo("2026-07-01T00:00:00Z");
		// 개체 유형: 보건기관은 협력기관의 하위 유형이고, 표시 이름의 속성은 상위 유형에서 받는다 (스스로 적지 않았다).
		assertThat(JsonPath.<List<String>>read(body, "$.entityTypes[?(@.name=='보건기관')].parents[*]")).containsExactly("협력기관");
		assertThat(JsonPath.<List<String>>read(body, "$.entityTypes[?(@.name=='보건기관')].label")).containsExactly("보건기관");
		assertThat(JsonPath.<List<Object>>read(body, "$.entityTypes[?(@.name=='보건기관')].displayNameAttribute"))
			.containsExactly((Object) null);
		assertThat(JsonPath.<List<String>>read(body, "$.entityTypes[?(@.name=='협력기관')].displayNameAttribute"))
			.containsExactly("기관명");
		// 속성: 형식과 어느 유형의 것인지.
		assertThat(JsonPath.<List<String>>read(body, "$.attributes[?(@.name=='점검주기일')].valueFormat")).containsExactly("INTEGER");
		assertThat(JsonPath.<List<String>>read(body, "$.attributes[?(@.name=='점검주기일')].entityType")).containsExactly("운영지침");
		// 관계 유형: 두 끝의 유형과 영향 방향.
		List<String> directions = JsonPath.read(body, "$.relationTypes[*].impactDirection");
		assertThat(directions).isNotEmpty().allMatch(List.of("SOURCE_TO_TARGET", "TARGET_TO_SOURCE", "BOTH", "NONE")::contains);
		assertThat(JsonPath.<List<String>>read(body, "$.relationTypes[*].sourceType")).doesNotContainNull();
		assertThat(JsonPath.<List<String>>read(body, "$.relationTypes[*].label")).doesNotContainNull();
		// 입력 자료와 매핑: 열과, 행이 만드는 개체와 관계.
		assertThat(JsonPath.<List<String>>read(body, "$.inputSources[?(@.inputSourceId=='guideline')].fileFormat"))
			.containsExactly("CSV");
		assertThat(JsonPath.<List<String>>read(body, "$.inputSources[?(@.inputSourceId=='guideline')].requiredColumns[*]"))
			.contains("지침번호");
		assertThat(JsonPath.<List<String>>read(body, "$.inputSources[?(@.inputSourceId=='guideline')].primaryEntity.entityType"))
			.containsExactly("운영지침");
		assertThat(JsonPath.<List<String>>read(body,
				"$.inputSources[?(@.inputSourceId=='guideline')].entities[*].attributes[*].attributeName"))
			.contains("지침제목", "점검주기일");
		assertThat(JsonPath.<List<String>>read(body,
				"$.inputSources[?(@.inputSourceId=='guideline-program')].relations[*].relationType"))
			.isNotEmpty();
		// 규칙: 번호, 우선순위, 대상, 할 일, 기한.
		assertThat(JsonPath.<List<String>>read(body, "$.rules[*].actionText")).doesNotContainNull().allMatch(text -> !text.isBlank());
		assertThat(JsonPath.<List<Integer>>read(body, "$.rules[*].priority")).allMatch(priority -> priority >= 1);
		assertThat(JsonPath.<List<Integer>>read(body, "$.rules[*].dueInDays")).contains(14);
		assertThat(JsonPath.<List<String>>read(body, "$.rules[*].appliesTo"))
			.allMatch(List.of("CHANGE_TARGET", "IMPACT_TARGET")::contains);
		assertThat(JsonPath.<List<String>>read(body, "$.rules[*].changeKinds[*]"))
			.allMatch(List.of("ADDED", "CHANGED", "REMOVED")::contains);
	}

	// ── 수용 기준: 없는 Pack, 쓰는 API 없음, 로그인과 감사 기록 ─────────────────────

	@Test
	void 없는_Pack_은_없다고_답하고_식별자가_없으면_거부한다() throws Exception {
		this.mvc.perform(get("/api/v1/pack-definition").with(OFFICER).param("packId", "pd-없는-Pack"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("PACK_NOT_FOUND")))
			.andExpect(jsonPath("$.field", is("packId")));
		this.mvc.perform(get("/api/v1/pack-definition").with(OFFICER))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("MISSING_PACK_ID")));
		this.mvc.perform(get("/api/v1/pack-definition").with(OFFICER).param("packId", "pd\u0000"))
			.andExpect(status().isNotFound());
	}

	@Test
	void 정의를_고치는_API_는_없다() throws Exception {
		load("test-mini", "pd-readonly");
		RequestPostProcessor admin = user("pd-admin").roles("ADMIN");
		for (var request : List.of(post("/api/v1/pack-definition"), put("/api/v1/pack-definition"),
				delete("/api/v1/pack-definition"))) {
			this.mvc.perform(request.with(admin).with(csrf()).param("packId", "pd-readonly")
					.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isMethodNotAllowed());
		}
	}

	@Test
	void 로그인해야_보고_담당자도_보며_본_것은_감사_기록에_남는다() throws Exception {
		String packId = "pd-audit";
		load("test-mini", packId);

		this.mvc.perform(get("/api/v1/pack-definition").param("packId", packId)).andExpect(status().isUnauthorized());
		long mark = this.audit.lastId();
		this.mvc.perform(get("/api/v1/pack-definition").with(OFFICER).param("packId", packId)).andExpect(status().isOk());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).extracting(AuditRecord::action).containsExactly("getPackDefinition", "getPackDefinition");
		assertThat(rows.get(1).target()).contains(packId);
		assertThat(rows).extracting(AuditRecord::actor).containsOnly("pd-officer");
	}

	// ── 도우미 ────────────────────────────────────────────────

	private String definition(String packId) throws Exception {
		return this.mvc.perform(get("/api/v1/pack-definition").with(OFFICER).param("packId", packId))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	/** 저장소의 Pack 을 다른 식별자의 사본으로 적재한다. 사본의 폴더를 돌려준다. */
	private Path load(String source, String packId) throws Exception {
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
		return copy;
	}
}
