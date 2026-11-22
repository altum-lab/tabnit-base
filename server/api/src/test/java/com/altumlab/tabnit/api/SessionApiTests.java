package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.Role;
import com.altumlab.tabnit.common.UserAccountRejectedException;
import com.altumlab.tabnit.common.UserAccounts;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * E-02 수용 기준: 로그인하지 않으면 API 에 접근할 수 없다. 규격은 docs/api/openapi.yaml 의 getSession, login, logout,
 * 규칙은 D7 9절이다.
 *
 * <p>막는 횟수는 이 시험에서만 3번으로 둔다: 설치 설정에서 오는지 함께 본다. 아이디와 비밀번호는 모두 지어낸 시험용 값이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "tabnit.login.max-failures=3", "tabnit.login.lock-duration=10m" })
class SessionApiTests {

	/** 시험용으로 지어낸 비밀번호. 실제 계정의 것이 아니다. */
	private static final String PASSWORD = "지어낸-시험용-비밀번호-2";

	@Autowired
	WebApplicationContext context;

	@Autowired
	UserAccounts accounts;

	@Autowired
	Clock clock;

	/** 시험의 데이터베이스. 사용자를 만드는 실행을 같은 곳에 붙인다. */
	@Autowired
	org.testcontainers.postgresql.PostgreSQLContainer postgres;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	// ── 수용 기준: 로그인하지 않으면 접근할 수 없다 ─────────────────────

	@Test
	void 로그인하지_않으면_모든_API_가_401_이다() throws Exception {
		List<RequestBuilder> requests = List.of(get("/api/v1/session"),
				get("/api/v1/relations").param("entityId", "e|T|a").param("asOf", "2026-01-01"), get("/api/v1/packs"),
				get("/api/v1/actions").param("packId", "p"), get("/api/v1/action").param("actionId", "a"),
				get("/api/v1/impact-scopes").param("changeEventId", "c"),
				post("/api/v1/action-decisions").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"),
				multipart("/api/v1/editions").with(csrf()), get("/api/v1/없는-주소"), get("/actuator/info"),
				get("/actuator/env"));
		for (RequestBuilder request : requests) {
			this.mvc.perform(request)
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.detail").isString());
		}
		// 서버가 떠 있는지는 로그인 없이 볼 수 있다.
		this.mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void 로그인하면_API_를_쓸_수_있고_내_정보가_나온다() throws Exception {
		this.accounts.create("ses-officer", PASSWORD, Role.OFFICER, this.clock.instant());
		MockHttpSession before = new MockHttpSession();
		String idBefore = before.getId();

		MvcResult result = login("ses-officer", PASSWORD, before).andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("application/json"))
			.andExpect(jsonPath("$.loginId").value("ses-officer"))
			.andExpect(jsonPath("$.role").value("OFFICER"))
			// 화면이 시각을 바꿔 보여 줄 시간대: 설치 설정의 것이다 (기본 Asia/Seoul, D2 AD-348).
			.andExpect(jsonPath("$.timeZone").value("Asia/Seoul"))
			.andExpect(jsonPath("$.password").doesNotExist())
			.andReturn();
		MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);

		// 로그인하면 세션의 식별자가 바뀐다 (남이 심어 둔 세션을 이어 쓰지 않는다).
		assertThat(session).isNotNull();
		assertThat(session.getId()).isNotEqualTo(idBefore);
		this.mvc.perform(get("/api/v1/session").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.loginId").value("ses-officer"))
			.andExpect(jsonPath("$.role").value("OFFICER"));
		this.mvc.perform(get("/api/v1/packs").session(session)).andExpect(status().isOk());
		// 세션 없이는 여전히 401 이다.
		this.mvc.perform(get("/api/v1/packs")).andExpect(status().isUnauthorized());
	}

	@Test
	void 관리자로_로그인하면_역할이_ADMIN_이다() throws Exception {
		this.accounts.create("ses-admin", PASSWORD, Role.ADMIN, this.clock.instant());

		login("ses-admin", PASSWORD, new MockHttpSession()).andExpect(status().isOk())
			.andExpect(jsonPath("$.loginId").value("ses-admin"))
			.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	// ── 로그인의 실패 ──────────────────────────────────────────────

	@Test
	void 없는_아이디와_틀린_비밀번호와_막힌_계정은_응답이_똑같다() throws Exception {
		this.accounts.create("ses-fail", PASSWORD, Role.OFFICER, this.clock.instant());

		String wrong = failed(login("ses-fail", "틀린-시험용-비밀번호-0", new MockHttpSession()));
		String unknown = failed(login("ses-fail-없음", PASSWORD, new MockHttpSession()));
		failed(login("ses-fail", "틀린-시험용-비밀번호-0", new MockHttpSession()));
		failed(login("ses-fail", "틀린-시험용-비밀번호-0", new MockHttpSession()));
		// 세 번 틀려 막혔다. 맞는 비밀번호도 받지 않고, 응답은 앞의 것과 똑같다.
		MockHttpSession session = new MockHttpSession();
		String locked = failed(login("ses-fail", PASSWORD, session));

		assertThat(unknown).isEqualTo(wrong);
		assertThat(locked).isEqualTo(wrong);
		assertThat(wrong).doesNotContain(PASSWORD).doesNotContain("ses-fail");
		// 실패한 로그인으로는 로그인되지 않는다.
		this.mvc.perform(get("/api/v1/session").session(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void 아이디나_비밀번호가_없거나_본문이_틀리면_400_이다() throws Exception {
		expectProblem(loginBody("{ \"password\": \"x\" }"), 400, "MISSING_LOGIN_ID", "loginId");
		expectProblem(loginBody("{ \"loginId\": \"\", \"password\": \"x\" }"), 400, "MISSING_LOGIN_ID", "loginId");
		expectProblem(loginBody("{ \"loginId\": 7, \"password\": \"x\" }"), 400, "MISSING_LOGIN_ID", "loginId");
		expectProblem(loginBody("{ \"loginId\": \"ses-x\" }"), 400, "MISSING_PASSWORD", "password");
		expectProblem(loginBody("{ \"loginId\": \"ses-x\", \"password\": \"\" }"), 400, "MISSING_PASSWORD", "password");
		expectProblem(loginBody("{ \"loginId\": \"ses-x\", \"password\": [\"x\"] }"), 400, "MISSING_PASSWORD", "password");
		loginBody("이것은 JSON 이 아니다").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BODY"));
		loginBody("[]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BODY"));
		// 아주 긴 값과 이상한 글자는 서버의 오류가 아니라 실패한 로그인이다.
		failed(login("가".repeat(3000), "나".repeat(3000), new MockHttpSession()));
		failed(login("a\u0000b", "x\u0000y", new MockHttpSession()));
	}

	// ── 위조 요청 방어 ─────────────────────────────────────────────

	@Test
	void 바꾸는_요청에_토큰이_없거나_틀리면_403_이다() throws Exception {
		this.accounts.create("ses-csrf", PASSWORD, Role.OFFICER, this.clock.instant());
		String body = "{ \"loginId\": \"ses-csrf\", \"password\": \"" + PASSWORD + "\" }";

		// 로그인 요청도 토큰이 있어야 한다.
		forbidden(this.mvc.perform(post("/api/v1/session").contentType(MediaType.APPLICATION_JSON).content(body)));
		forbidden(this.mvc.perform(post("/api/v1/session").with(csrf().useInvalidToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)));
		MockHttpSession session = (MockHttpSession) login("ses-csrf", PASSWORD, new MockHttpSession())
			.andExpect(status().isOk())
			.andReturn()
			.getRequest()
			.getSession(false);
		// 로그인한 뒤에도 같다.
		forbidden(this.mvc.perform(post("/api/v1/action-decisions").session(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")));
		forbidden(this.mvc.perform(multipart("/api/v1/editions").session(session)));
		forbidden(this.mvc.perform(delete("/api/v1/session").session(session)));
		// 토큰이 있으면 요청이 처리된다 (여기서는 항목이 없어 400 이다).
		this.mvc.perform(post("/api/v1/action-decisions").session(session)
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_ACTION_ID"));
		// 막힌 요청으로 로그아웃되지 않았다.
		this.mvc.perform(get("/api/v1/session").session(session)).andExpect(status().isOk());
	}

	// ── 로그아웃 ──────────────────────────────────────────────────

	@Test
	void 로그아웃하면_세션이_끝나고_다시_401_이다() throws Exception {
		this.accounts.create("ses-out", PASSWORD, Role.OFFICER, this.clock.instant());
		MockHttpSession session = (MockHttpSession) login("ses-out", PASSWORD, new MockHttpSession())
			.andExpect(status().isOk())
			.andReturn()
			.getRequest()
			.getSession(false);

		this.mvc.perform(delete("/api/v1/session").session(session).with(csrf()))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		assertThat(session.isInvalid()).isTrue();
		// 로그인하지 않은 채로 로그아웃해도 204 다.
		this.mvc.perform(delete("/api/v1/session").with(csrf())).andExpect(status().isNoContent());
	}

	// ── 사용자를 만드는 명령 ─────────────────────────────────────────

	@Test
	void 명령으로_만든_사용자로_로그인할_수_있고_비밀번호는_환경_변수에서만_온다() throws Exception {
		new UserCreationCommand(this.accounts, this.audit, this.clock, "ses-cmd", "ADMIN", name -> PASSWORD).run(null);

		login("ses-cmd", PASSWORD, new MockHttpSession()).andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("ADMIN"));
		// 읽는 환경 변수의 이름.
		List<String> asked = new java.util.ArrayList<>();
		new UserCreationCommand(this.accounts, this.audit, this.clock, "ses-cmd-2", "OFFICER", name -> {
			asked.add(name);
			return PASSWORD;
		}).run(null);
		assertThat(asked).containsExactly("TABNIT_NEW_USER_PASSWORD");
		// 비밀번호가 없으면 만들지 않고, 무엇을 하면 되는지 알린다.
		for (String missing : new String[] { null, "" }) {
			assertThatThrownBy(
					() -> new UserCreationCommand(this.accounts, this.audit, this.clock, "ses-cmd-3", "OFFICER", name -> missing).run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("TABNIT_NEW_USER_PASSWORD");
		}
		assertThat(this.accounts.find("ses-cmd-3")).isEmpty();
		// 역할이 틀리면 만들지 않는다.
		assertThatThrownBy(
				() -> new UserCreationCommand(this.accounts, this.audit, this.clock, "ses-cmd-4", "MANAGER", name -> PASSWORD).run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ADMIN")
			.hasMessageContaining("OFFICER");
		assertThat(this.accounts.find("ses-cmd-4")).isEmpty();
		// 규칙에 맞지 않는 비밀번호나 이미 있는 아이디는 common 이 거부한 그대로 알린다. 문구에 비밀번호가 없다.
		assertThatThrownBy(
				() -> new UserCreationCommand(this.accounts, this.audit, this.clock, "ses-cmd-5", "OFFICER", name -> "짧은-비밀").run(null))
			.isInstanceOf(UserAccountRejectedException.class)
			.satisfies(e -> assertThat(e.getMessage()).doesNotContain("짧은-비밀"));
		assertThatThrownBy(
				() -> new UserCreationCommand(this.accounts, this.audit, this.clock, "ses-cmd", "OFFICER", name -> PASSWORD).run(null))
			.isInstanceOf(UserAccountRejectedException.class);
	}

	@Test
	void 사용자를_만드는_실행은_HTTP_서버_없이_뜨고_비밀번호가_없으면_만들지_않고_끝난다() {
		// 실제 명령과 같은 방식으로 띄운다. 이 시험의 환경에는 비밀번호의 환경 변수가 없다.
		org.springframework.boot.SpringApplication command = new org.springframework.boot.SpringApplication(
				TabnitApiApplication.class);
		command.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);

		assertThat(UserCreationCommand.requested(new String[] { "--tabnit.create-user.login-id=ses-mode" })).isTrue();
		assertThat(UserCreationCommand.requested(new String[] { "--server.port=0" })).isFalse();
		assertThatThrownBy(() -> command.run("--tabnit.create-user.login-id=ses-mode", "--tabnit.create-user.role=ADMIN",
				"--spring.datasource.url=" + this.postgres.getJdbcUrl(),
				"--spring.datasource.username=" + this.postgres.getUsername(),
				"--spring.datasource.password=" + this.postgres.getPassword()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("TABNIT_NEW_USER_PASSWORD");
		assertThat(this.accounts.find("ses-mode")).isEmpty();
	}

	private ResultActions login(String loginId, String password, MockHttpSession session) throws Exception {
		String body = "{ \"loginId\": " + json(loginId) + ", \"password\": " + json(password) + " }";
		return this.mvc
			.perform(post("/api/v1/session").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions loginBody(String body) throws Exception {
		return this.mvc.perform(post("/api/v1/session").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	/** 실패한 로그인의 응답을 확인하고 그 본문을 돌려준다. */
	private static String failed(ResultActions result) throws Exception {
		return result.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
			.andReturn()
			.getResponse()
			.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
	}

	private static void forbidden(ResultActions result) throws Exception {
		result.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.detail").isString());
	}

	private static void expectProblem(ResultActions result, int status, String code, String field) throws Exception {
		result.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.field").value(field));
	}

	private static String json(String text) {
		StringBuilder out = new StringBuilder("\"");
		for (char c : text.toCharArray()) {
			if (c == '"' || c == '\\') {
				out.append('\\').append(c);
			}
			else if (c < 0x20) {
				out.append(String.format("\\u%04x", (int) c));
			}
			else {
				out.append(c);
			}
		}
		return out.append('"').toString();
	}


	// ── 계정 관리 명령과 그 감사 기록 (E-18, D2 AD-320, AD-329, AD-346) ───────────────

	@Autowired
	com.altumlab.tabnit.common.AuditLog audit;

	@Test
	void 막힌_사람을_명령으로_풀면_바로_로그인되고_명령이_감사_기록에_남는다() throws Exception {
		this.accounts.create("ses-unlock", PASSWORD, com.altumlab.tabnit.common.Role.OFFICER, this.clock.instant());
		for (int i = 0; i < 5; i++) {
			login("ses-unlock", "틀린-시험용-비밀번호-9", new MockHttpSession()).andExpect(status().isUnauthorized());
		}
		login("ses-unlock", PASSWORD, new MockHttpSession()).andExpect(status().isUnauthorized());
		long before = this.audit.lastId();

		new UserUnlockCommand(this.accounts, this.audit, this.clock, "ses-unlock").run(null);

		login("ses-unlock", PASSWORD, new MockHttpSession()).andExpect(status().isOk());
		// 하기 전의 줄과 결과의 줄. 누가는 비어 있고 명령으로 했다고 적는다.
		List<com.altumlab.tabnit.common.AuditRecord> rows = this.audit.after(before)
			.stream()
			.filter(row -> "unlockUser".equals(row.action()))
			.toList();
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).outcome()).isEqualTo(com.altumlab.tabnit.common.AuditOutcome.STARTED);
		assertThat(rows.get(1).outcome()).isEqualTo(com.altumlab.tabnit.common.AuditOutcome.SUCCESS);
		assertThat(rows.get(1).startedId()).isEqualTo(rows.get(0).id());
		assertThat(rows).allSatisfy(row -> {
			assertThat(row.actor()).isNull();
			assertThat(row.target()).isEqualTo("loginId=ses-unlock");
			assertThat(row.detail()).contains("via=command");
			assertThat(row.httpStatus()).isNull();
		});
	}

	@Test
	void 명령으로_비밀번호를_바꾸면_새_비밀번호로만_로그인되고_감사_기록에_비밀번호는_남지_않는다() throws Exception {
		String next = "지어낸-화면-시험-새-비밀번호-7";
		this.accounts.create("ses-change", PASSWORD, com.altumlab.tabnit.common.Role.OFFICER, this.clock.instant());
		long before = this.audit.lastId();
		List<String> asked = new java.util.ArrayList<>();

		new PasswordChangeCommand(this.accounts, this.audit, this.clock, "ses-change", name -> {
			asked.add(name);
			return next;
		}).run(null);

		assertThat(asked).containsExactly("TABNIT_NEW_USER_PASSWORD");
		login("ses-change", PASSWORD, new MockHttpSession()).andExpect(status().isUnauthorized());
		login("ses-change", next, new MockHttpSession()).andExpect(status().isOk());
		List<com.altumlab.tabnit.common.AuditRecord> rows = this.audit.after(before)
			.stream()
			.filter(row -> "changeUserPassword".equals(row.action()))
			.toList();
		assertThat(rows).extracting(com.altumlab.tabnit.common.AuditRecord::outcome)
			.containsExactly(com.altumlab.tabnit.common.AuditOutcome.STARTED, com.altumlab.tabnit.common.AuditOutcome.SUCCESS);
		assertThat(rows).allSatisfy(row -> {
			assertThat(row.actor()).isNull();
			assertThat(row.target()).isEqualTo("loginId=ses-change");
			assertThat(String.valueOf(row.detail())).doesNotContain(next).doesNotContain(PASSWORD);
		});
	}

	@Test
	void 사용자를_만드는_명령도_감사_기록에_남고_실패한_명령은_실패로_남는다() throws Exception {
		long before = this.audit.lastId();

		new UserCreationCommand(this.accounts, this.audit, this.clock, "ses-audited", "OFFICER", name -> PASSWORD).run(null);
		// 실패하는 명령들: 이미 있는 아이디, 없는 아이디의 막힘 풀기, 비밀번호가 없는 바꾸기, 규칙에 맞지 않는 새 비밀번호.
		assertThatThrownBy(() -> new UserCreationCommand(this.accounts, this.audit, this.clock, "ses-audited", "OFFICER",
				name -> PASSWORD).run(null)).isInstanceOf(UserAccountRejectedException.class);
		assertThatThrownBy(() -> new UserUnlockCommand(this.accounts, this.audit, this.clock, "ses-no-such").run(null))
			.isInstanceOf(UserAccountRejectedException.class);
		assertThatThrownBy(
				() -> new PasswordChangeCommand(this.accounts, this.audit, this.clock, "ses-audited", name -> null).run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("TABNIT_NEW_USER_PASSWORD");
		assertThatThrownBy(() -> new PasswordChangeCommand(this.accounts, this.audit, this.clock, "ses-audited",
				name -> "짧은-비밀").run(null)).isInstanceOf(UserAccountRejectedException.class);

		List<com.altumlab.tabnit.common.AuditRecord> rows = this.audit.after(before)
			.stream()
			.filter(row -> row.target() != null && (row.target().equals("loginId=ses-audited")
					|| row.target().equals("loginId=ses-no-such")))
			.toList();
		assertThat(rows).extracting(com.altumlab.tabnit.common.AuditRecord::action,
				com.altumlab.tabnit.common.AuditRecord::outcome)
			.containsExactly(tuple("createUser", com.altumlab.tabnit.common.AuditOutcome.STARTED),
					tuple("createUser", com.altumlab.tabnit.common.AuditOutcome.SUCCESS),
					tuple("createUser", com.altumlab.tabnit.common.AuditOutcome.STARTED),
					tuple("createUser", com.altumlab.tabnit.common.AuditOutcome.FAILED),
					tuple("unlockUser", com.altumlab.tabnit.common.AuditOutcome.STARTED),
					tuple("unlockUser", com.altumlab.tabnit.common.AuditOutcome.FAILED),
					tuple("changeUserPassword", com.altumlab.tabnit.common.AuditOutcome.STARTED),
					tuple("changeUserPassword", com.altumlab.tabnit.common.AuditOutcome.FAILED),
					tuple("changeUserPassword", com.altumlab.tabnit.common.AuditOutcome.STARTED),
					tuple("changeUserPassword", com.altumlab.tabnit.common.AuditOutcome.FAILED));
		// 만든 사용자의 역할이 남는다. 비밀번호는 어디에도 없다.
		assertThat(rows.get(0).detail()).contains("role=OFFICER");
		assertThat(rows).allSatisfy(row -> assertThat(String.valueOf(row.detail())).doesNotContain(PASSWORD)
			.doesNotContain("짧은-비밀"));
		// 실패한 명령은 아무것도 바꾸지 않았다.
		login("ses-audited", PASSWORD, new MockHttpSession()).andExpect(status().isOk());
	}

	@Test
	void 어느_명령으로_띄웠는지를_실행_인자에서_가린다() {
		assertThat(AccountCommands.requested(new String[] { "--tabnit.create-user.login-id=a", "--x=y" })).isTrue();
		assertThat(AccountCommands.requested(new String[] { "--tabnit.unlock-user.login-id=a" })).isTrue();
		assertThat(AccountCommands.requested(new String[] { "--tabnit.change-password.login-id=a" })).isTrue();
		assertThat(AccountCommands.requested(new String[] { "--server.port=8080" })).isFalse();
		assertThat(AccountCommands.requested(new String[] {})).isFalse();
	}

	@Test
	void 명령으로_띄웠는데_일을_한_명령이_없으면_성공으로_끝내지_않는다() {
		// 아이디로 false 를 주면 설정이 그 명령을 끈 것으로 읽어 명령이 만들어지지 않는다.
		org.springframework.context.support.StaticApplicationContext none =
				new org.springframework.context.support.StaticApplicationContext();
		assertThatThrownBy(() -> AccountCommands.requireRan(none)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("false");

		org.springframework.context.support.StaticApplicationContext ran =
				new org.springframework.context.support.StaticApplicationContext();
		ran.getBeanFactory()
			.registerSingleton("unlock", new UserUnlockCommand(this.accounts, this.audit, this.clock, "ses-any"));
		AccountCommands.requireRan(ran);
	}
}
