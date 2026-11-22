package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.Role;
import com.altumlab.tabnit.common.UserAccounts;
import com.altumlab.tabnit.pack.PackDefinitions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * E-06 수용 기준: 담당자는 확정만, 관리자는 Pack 적재까지 할 수 있다. 규격은 docs/api/openapi.yaml 의 loadPack,
 * 규칙은 D7 2절(역할)과 10절(Pack 적재)이다.
 *
 * <p>관리자는 담당자의 일을 모두 한다 (D2 AD-270). Pack 은 서버의 Pack 폴더에서 이름으로 골라 적재한다 (D2 AD-272).
 * 이 시험의 Pack 폴더는 임시 폴더이고, 그 안의 Pack 은 시험용 Pack 의 사본이다. 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RolesApiTests {

	/** 시험용으로 지어낸 비밀번호. 실제 계정의 것이 아니다. */
	private static final String PASSWORD = "지어낸-시험용-비밀번호-4";

	/** 서버의 Pack 폴더. 설치 설정 tabnit.packs-dir 로 넘긴다. */
	private static final Path PACKS = createPacksDirectory();

	private static final RequestPostProcessor OFFICER = user("role-officer").roles("OFFICER");

	private static final RequestPostProcessor ADMIN = user("role-admin").roles("ADMIN");

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@Autowired
	UserAccounts accounts;

	@Autowired
	PackDefinitions definitions;

	@Autowired
	Clock clock;

	MockMvc mvc;

	@DynamicPropertySource
	static void packsDirectory(DynamicPropertyRegistry registry) {
		registry.add("tabnit.packs-dir", PACKS::toString);
	}

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	// ── 수용 기준 ─────────────────────────────────────────────────

	@Test
	void 담당자는_보고_올리고_확정하는_API_를_부를_수_있지만_Pack_적재는_403_이다() throws Exception {
		PackCopies.copyInto(PACKS, "role-officer-pack", text -> text);

		// 보는 것.
		this.mvc.perform(get("/api/v1/session").with(OFFICER)).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/packs").with(OFFICER)).andExpect(status().isOk());
		// 확정과 판 올리기: 역할의 확인을 지나 항목의 확인까지 간다 (항목이 없어 400).
		this.mvc.perform(post("/api/v1/action-decisions").with(OFFICER).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_ACTION_ID"));
		this.mvc.perform(multipart("/api/v1/editions").with(OFFICER).with(csrf())).andExpect(status().isBadRequest());

		// Pack 적재는 관리자만 한다.
		this.mvc.perform(load("role-officer-pack").with(OFFICER))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("권한")));
		// 거부한 요청은 아무것도 적재하지 않는다. 항목이 틀려도 권한이 먼저다.
		assertThat(this.definitions.find("role-officer-pack")).isEmpty();
		this.mvc.perform(post("/api/v1/pack-loads").with(OFFICER).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void 적재는_되지만_뜻대로_걸리지_않을_수_있는_규칙은_경고로_알린다() throws Exception {
		// 규칙 하나를 더한다: 영향 대상에 걸면서 변동 대상의 속성을 보는데 변동 대상의 유형을 정하지 않았다.
		// 이 Pack 에는 한 행이 개체를 여럿 만드는 입력 자료가 있다 (D2 AD-323).
		String more = """

				ex:규칙-0009 a tabnit:Rule ; tabnit:ruleNo "0009" ; rdfs:label "경고가 나오는 규칙" ; tabnit:priority 9 ;
				    tabnit:appliesTo tabnit:ImpactTarget ; tabnit:targetType ex:의료사업 ;
				    tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:property ex:점검주기일 ;
				                       tabnit:operator tabnit:LessThanOrEqual ; tabnit:value 30 ] ;
				    tabnit:actionText "할 일" ; tabnit:dueInDays 1 .
				""";
		PackCopies.copyInto(PACKS, "role-warn-pack", text -> text.contains("a tabnit:Rule") ? text + more : text);

		this.mvc.perform(load("role-warn-pack").with(ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("LOADED"))
			.andExpect(jsonPath("$.warnings.length()").value(1))
			.andExpect(jsonPath("$.warnings[0].file").value("rules"))
			.andExpect(jsonPath("$.warnings[0].item").value("0009"))
			.andExpect(jsonPath("$.warnings[0].message", org.hamcrest.Matchers.containsString("점검주기일")));
		assertThat(this.definitions.find("role-warn-pack")).isPresent();
		// 다시 적재해도 같은 경고가 나온다.
		this.mvc.perform(load("role-warn-pack").with(ADMIN))
			.andExpect(jsonPath("$.outcome").value("UNCHANGED"))
			.andExpect(jsonPath("$.warnings[0].item").value("0009"));
	}

	@Test
	void 관리자는_Pack_을_적재하고_담당자의_일도_모두_한다() throws Exception {
		PackCopies.copyInto(PACKS, "role-admin-pack", text -> text);

		this.mvc.perform(load("role-admin-pack").with(ADMIN))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("application/json"))
			.andExpect(jsonPath("$.packId").value("role-admin-pack"))
			.andExpect(jsonPath("$.outcome").value("LOADED"))
			// 경고할 것이 없으면 빈 목록이다 (항목이 빠지지 않는다).
			.andExpect(jsonPath("$.warnings").isArray())
			.andExpect(jsonPath("$.warnings.length()").value(0));
		assertThat(this.definitions.find("role-admin-pack")).isPresent();
		// 적재한 Pack 이 목록에 나온다.
		this.mvc.perform(get("/api/v1/packs").with(ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.packs[*].packId", hasItem("role-admin-pack")));
		// 같은 것을 다시 넣으면 바꾸지 않는다.
		this.mvc.perform(load("role-admin-pack").with(ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("UNCHANGED"));
		// 담당자의 일.
		this.mvc.perform(post("/api/v1/action-decisions").with(ADMIN).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_ACTION_ID"));
		this.mvc.perform(multipart("/api/v1/editions").with(ADMIN).with(csrf())).andExpect(status().isBadRequest());
	}

	@Test
	void 실제로_로그인한_사람의_역할로_가른다() throws Exception {
		PackCopies.copyInto(PACKS, "role-real-pack", text -> text);
		this.accounts.create("role-real-officer", PASSWORD, Role.OFFICER, this.clock.instant());
		this.accounts.create("role-real-admin", PASSWORD, Role.ADMIN, this.clock.instant());

		MockHttpSession officer = login("role-real-officer");
		MockHttpSession admin = login("role-real-admin");

		this.mvc.perform(load("role-real-pack").session(officer)).andExpect(status().isForbidden());
		assertThat(this.definitions.find("role-real-pack")).isEmpty();
		// 막힌 뒤에도 로그인은 그대로다.
		this.mvc.perform(get("/api/v1/packs").session(officer)).andExpect(status().isOk());
		this.mvc.perform(load("role-real-pack").session(admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("LOADED"));
	}

	@Test
	void 로그인하지_않았거나_토큰이_없거나_아는_역할이_없으면_막는다() throws Exception {
		this.mvc.perform(load("role-none"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		this.mvc.perform(post("/api/v1/pack-loads").with(ADMIN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"packId\": \"role-none\" }")).andExpect(status().isForbidden());
		// 역할이 관리자도 담당자도 아닌 사람은 아무 API 도 부르지 못한다.
		RequestPostProcessor stranger = user("role-stranger").roles("GUEST");
		this.mvc.perform(get("/api/v1/packs").with(stranger))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		this.mvc.perform(get("/api/v1/actions").param("packId", "p").with(stranger)).andExpect(status().isForbidden());
		this.mvc.perform(load("role-none").with(stranger)).andExpect(status().isForbidden());
		// 로그아웃은 누구나 한다.
		this.mvc.perform(delete("/api/v1/session").with(stranger).with(csrf())).andExpect(status().isNoContent());
	}

	// ── Pack 적재의 입력과 거부 ───────────────────────────────────────

	@Test
	void Pack_의_이름이_없거나_폴더_밖을_가리키면_400_이고_없는_폴더는_404_다() throws Exception {
		expectProblem(this.mvc.perform(post("/api/v1/pack-loads").with(ADMIN).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")), 400, "MISSING_PACK_ID", "packId");
		expectProblem(loadAsAdmin("\"\""), 400, "MISSING_PACK_ID", "packId");
		for (String bad : new String[] { "\"../role-admin-pack\"", "\"..\"", "\".\"", "\"a/b\"", "\"a\\\\b\"", "\"/etc\"",
				"\"A-upper\"", "\"한글\"", "\"a b\"", "\"a\\u0000\"", "\"" + "a".repeat(101) + "\"", "7", "[\"a\"]" }) {
			expectProblem(loadAsAdmin(bad), 400, "INVALID_PACK_ID", "packId");
		}
		expectProblem(loadAsAdmin("\"role-no-such-folder\""), 404, "PACK_FOLDER_NOT_FOUND", "packId");
		// 폴더가 아니라 파일이면 없는 것이다.
		Files.writeString(PACKS.resolve("role-a-file"), "폴더가 아니다");
		expectProblem(loadAsAdmin("\"role-a-file\""), 404, "PACK_FOLDER_NOT_FOUND", "packId");
		// Pack 폴더 밖을 가리키는 바로가기는 따라가지 않는다.
		Path outside = Files.createTempDirectory("tabnit-roles-outside");
		PackCopies.copyInto(outside, "role-linked", text -> text);
		Files.createSymbolicLink(PACKS.resolve("role-linked"), outside.resolve("role-linked"));
		expectProblem(loadAsAdmin("\"role-linked\""), 404, "PACK_FOLDER_NOT_FOUND", "packId");
		assertThat(this.definitions.find("role-linked")).isEmpty();
		// 본문이 틀리면 400 이다.
		this.mvc.perform(post("/api/v1/pack-loads").with(ADMIN).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("JSON 이 아니다")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BODY"));
	}

	@Test
	void 틀린_Pack_은_422_로_문제를_모두_알리고_아무것도_적재하지_않는다() throws Exception {
		PackCopies.copyInto(PACKS, "role-broken", text -> text.replace("tabnit:defaultMaxDepth 3", "tabnit:noSuchThing 3"));

		loadAsAdmin("\"role-broken\"").andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value("PACK_INVALID"))
			.andExpect(jsonPath("$.detail").isString())
			.andExpect(jsonPath("$.problems", hasSize(greaterThanOrEqualTo(1))))
			.andExpect(jsonPath("$.problems[0].file").value("pack.ttl"))
			.andExpect(jsonPath("$.problems[0].item").isString())
			.andExpect(jsonPath("$.problems[0].message").isString())
			// 서버의 폴더 경로를 응답에 담지 않는다.
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PACKS.toString()))));
		assertThat(this.definitions.find("role-broken")).isEmpty();
	}

	@Test
	void Pack_의_문제를_알리는_문구에_서버의_폴더_경로가_섞여도_응답에는_담지_않는다() throws Exception {
		// 상대 주소로 적은 정의는 읽는 쪽이 파일의 자리를 붙여 푼다. 그 주소가 문제의 문구에 들어간다.
		PackCopies.copyInto(PACKS, "role-relative",
				text -> text.contains("owl:Class") ? text + "\n<relative-name> a owl:Class .\n" : text);

		String body = loadAsAdmin("\"role-relative\"").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PACK_INVALID"))
			.andExpect(jsonPath("$.problems", hasSize(greaterThanOrEqualTo(1))))
			.andReturn()
			.getResponse()
			.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

		// 무엇이 틀렸는지는 남는다.
		assertThat(body).contains("relative-name");
		assertThat(body).doesNotContain(PACKS.toString()).doesNotContain(PACKS.getFileName().toString());
		assertThat(this.definitions.find("role-relative")).isEmpty();
	}

	@Test
	void 본문이_null_이거나_객체가_아니면_400_이다() throws Exception {
		for (String body : new String[] { "null", "[]", "\"role-x\"", "" }) {
			this.mvc.perform(post("/api/v1/pack-loads").with(ADMIN).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BODY"));
		}
	}

	@Test
	void 적재된_Pack_을_받을_수_없게_고치면_409_로_까닭을_알리고_저장된_것은_그대로다() throws Exception {
		PackCopies.copyInto(PACKS, "role-changed", text -> text);
		loadAsAdmin("\"role-changed\"").andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("LOADED"));
		var before = this.definitions.find("role-changed").orElseThrow();

		// 이름만 고치면 받는다.
		PackCopies.copyInto(PACKS, "role-changed", text -> text.replace("시험용 Pack: 공공의료", "고친 시험용 Pack: 공공의료"));
		loadAsAdmin("\"role-changed\"").andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UPDATED"));
		var renamed = this.definitions.find("role-changed").orElseThrow();
		assertThat(renamed.label()).startsWith("고친 시험용 Pack");

		// 이미 적재한 매핑을 바꾸면 받지 않는다 (입력 자료의 열 이름을 고쳤다).
		PackCopies.copyInto(PACKS, "role-changed", text -> text.replace("\"점검주기일\"", "\"점검주기\""));
		loadAsAdmin("\"role-changed\"").andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value("PACK_CHANGE_REJECTED"))
			.andExpect(jsonPath("$.reasons", hasSize(greaterThanOrEqualTo(1))))
			.andExpect(jsonPath("$.reasons[0]").isString());
		assertThat(before.entityTypes()).isEqualTo(this.definitions.find("role-changed").orElseThrow().entityTypes());
	}

	@Test
	void Pack_폴더를_정하지_않은_설치에서는_적재할_수_없고_무엇을_하면_되는지_알린다() {
		PackLoading unconfigured = new PackLoading(this.dataSource, this.clock, "");

		assertThatThrownBy(() -> unconfigured.load("role-admin-pack")).isInstanceOfSatisfying(ApiProblemException.class, e -> {
			assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(e.code()).isEqualTo("PACK_FOLDER_NOT_FOUND");
			assertThat(e.getMessage()).contains("tabnit.packs-dir");
		});
		// 없는 폴더를 Pack 폴더로 정해도 같다.
		PackLoading missing = new PackLoading(this.dataSource, this.clock, PACKS.resolve("no-such-directory").toString());
		assertThatThrownBy(() -> missing.load("role-admin-pack")).isInstanceOf(ApiProblemException.class);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder load(String packId) {
		return post("/api/v1/pack-loads").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"packId\": \"" + packId + "\" }");
	}

	/** @param packIdJson packId 항목에 넣을 JSON 값 그대로 */
	private ResultActions loadAsAdmin(String packIdJson) throws Exception {
		return this.mvc.perform(post("/api/v1/pack-loads").with(ADMIN).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"packId\": " + packIdJson + " }"));
	}

	private MockHttpSession login(String loginId) throws Exception {
		return (MockHttpSession) this.mvc
			.perform(post("/api/v1/session").with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"loginId\": \"" + loginId + "\", \"password\": \"" + PASSWORD + "\" }"))
			.andExpect(status().isOk())
			.andReturn()
			.getRequest()
			.getSession(false);
	}

	private static void expectProblem(ResultActions result, int status, String code, String field) throws Exception {
		result.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.field").value(field))
			.andExpect(jsonPath("$.detail").isString());
	}

	private static Path createPacksDirectory() {
		try {
			return Files.createTempDirectory("tabnit-roles-packs").toRealPath();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
