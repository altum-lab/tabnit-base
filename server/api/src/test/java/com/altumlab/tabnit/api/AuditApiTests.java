package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditOutcome;
import com.altumlab.tabnit.common.AuditRecord;
import com.altumlab.tabnit.common.Role;
import com.altumlab.tabnit.common.UserAccounts;
import com.altumlab.tabnit.rules.Action;
import com.altumlab.tabnit.rules.ActionRecords;
import com.altumlab.tabnit.rules.ActionStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * E-05 수용 기준: 조회와 변경이 감사 기록에 남는다. 규칙은 D7 2절(감사 기록)과 D5 7절이다.
 *
 * <p>요청 하나는 두 줄이다: 하기 전에 남기는 시작의 줄과 끝난 뒤의 결과의 줄 (D2 AD-273). 로그인의 성공과 실패,
 * 로그아웃, 권한이 없어 거부한 요청도 남는다. 로그인하지 않은 요청과 내 정보를 묻는 요청은 남기지 않는다 (D2 AD-274).
 * 아이디, 비밀번호, 자료는 모두 지어낸 시험용 값이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuditApiTests {

	/** 시험용으로 지어낸 비밀번호. 실제 계정의 것이 아니다. */
	private static final String PASSWORD = "지어낸-시험용-비밀번호-5";

	private static final Path PACKS = createPacksDirectory();

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@Autowired
	AuditLog audit;

	@Autowired
	UserAccounts accounts;

	@Autowired
	EditionProcessing processing;

	@Autowired
	Clock clock;

	@Autowired
	RequestMappingHandlerMapping requestMappingHandlerMapping;

	@TempDir
	Path temp;

	MockMvc mvc;

	@DynamicPropertySource
	static void packsDirectory(DynamicPropertyRegistry registry) {
		registry.add("tabnit.packs-dir", PACKS::toString);
	}

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	// ── 수용 기준: 조회가 남는다 ──────────────────────────────────────

	@Test
	void 조회는_하기_전의_줄과_결과의_줄_두_줄로_남는다() throws Exception {
		RequestPostProcessor reader = user("aud-reader").roles("OFFICER");
		long mark = this.audit.lastId();
		Instant before = this.clock.instant().minusSeconds(1);

		this.mvc.perform(get("/api/v1/packs").with(reader)).andExpect(status().isOk());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).hasSize(2);
		AuditRecord started = rows.get(0);
		AuditRecord finished = rows.get(1);
		assertThat(started.actor()).isEqualTo("aud-reader");
		assertThat(started.action()).isEqualTo("listPacks");
		assertThat(started.outcome()).isEqualTo(AuditOutcome.STARTED);
		assertThat(started.httpStatus()).isNull();
		assertThat(started.startedId()).isNull();
		assertThat(started.occurredAt()).isBetween(before, this.clock.instant().plusSeconds(1));
		assertThat(finished.actor()).isEqualTo("aud-reader");
		assertThat(finished.action()).isEqualTo("listPacks");
		assertThat(finished.outcome()).isEqualTo(AuditOutcome.SUCCESS);
		assertThat(finished.httpStatus()).isEqualTo(200);
		assertThat(finished.startedId()).isEqualTo(started.id());
		assertThat(finished.occurredAt()).isAfterOrEqualTo(started.occurredAt());
	}

	@Test
	void 조회의_대상과_기준_날짜가_남고_실패한_조회도_남는다() throws Exception {
		RequestPostProcessor reader = user("aud-target").roles("ADMIN");
		long mark = this.audit.lastId();

		// 없는 개체: 404.
		this.mvc.perform(get("/api/v1/relations").param("entityId", "aud|T|없는 개체").param("asOf", "2026-03-01").with(reader))
			.andExpect(status().isNotFound());
		// 틀린 요청: 400.
		this.mvc.perform(get("/api/v1/relations").with(reader)).andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/v1/actions").param("packId", "aud-no-pack").param("status", "PENDING").with(reader))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/impact-scopes").param("changeEventId", "aud-no-change").with(reader))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/action").param("actionId", "aud-no-action").with(reader)).andExpect(status().isNotFound());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).hasSize(10);
		assertThat(rows).extracting(AuditRecord::actor).containsOnly("aud-target");
		// 시작의 줄에 대상이 이미 있다: 주소의 항목은 하기 전에 안다.
		assertThat(rows.get(0).action()).isEqualTo("getRelationsAsOf");
		assertThat(rows.get(0).target()).isEqualTo("entityId=aud|T|없는 개체");
		assertThat(rows.get(0).asOf()).isNull();
		assertThat(rows.get(1).outcome()).isEqualTo(AuditOutcome.FAILED);
		assertThat(rows.get(1).httpStatus()).isEqualTo(404);
		assertThat(rows.get(1).target()).isEqualTo("entityId=aud|T|없는 개체");
		// 결과의 줄에 답에 쓴 기준 날짜가 남는다.
		assertThat(rows.get(1).asOf()).isEqualTo(LocalDate.parse("2026-03-01"));
		assertThat(rows.get(2).target()).isNull();
		assertThat(rows.get(3).httpStatus()).isEqualTo(400);
		assertThat(rows.get(3).outcome()).isEqualTo(AuditOutcome.FAILED);
		assertThat(rows.get(3).asOf()).isNull();
		assertThat(rows.get(4).action()).isEqualTo("listActions");
		assertThat(rows.get(4).target()).isEqualTo("packId=aud-no-pack; status=PENDING");
		assertThat(rows.get(6).action()).isEqualTo("getChangeImpactScope");
		assertThat(rows.get(6).target()).isEqualTo("changeEventId=aud-no-change");
		assertThat(rows.get(8).action()).isEqualTo("getAction");
		assertThat(rows.get(8).target()).isEqualTo("actionId=aud-no-action");
	}

	@Test
	void 같은_항목을_여러_번_보내면_처리한_값_그대로_남는다() throws Exception {
		RequestPostProcessor reader = user("aud-twice").roles("OFFICER");
		long mark = this.audit.lastId();

		// 서버는 여러 번 온 값을 쉼표로 이어 하나로 받는다. 감사 기록에도 첫 값만이 아니라 그 값이 남아야 한다.
		this.mvc.perform(get("/api/v1/relations").param("entityId", "aud|T|a", "aud|T|b").param("asOf", "2026-03-01")
			.with(reader)).andExpect(status().isNotFound());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).target()).isEqualTo("entityId=aud|T|a,aud|T|b");
		assertThat(rows.get(1).target()).isEqualTo("entityId=aud|T|a,aud|T|b");
	}

	@Test
	void 저장할_수_없는_기준_날짜가_와도_결과의_줄이_빠지지_않는다() throws Exception {
		RequestPostProcessor reader = user("aud-far").roles("OFFICER");
		long mark = this.audit.lastId();

		// 날짜의 형식은 맞지만 저장소가 담지 못하는 먼 해.
		this.mvc.perform(get("/api/v1/relations").param("entityId", "aud|T|far").param("asOf", "+6000000-01-01").with(reader));

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(1).startedId()).isEqualTo(rows.get(0).id());
		assertThat(rows.get(1).outcome()).isNotEqualTo(AuditOutcome.STARTED);
		assertThat(rows.get(1).asOf()).isNull();
	}

	// ── 수용 기준: 변경이 남는다 ──────────────────────────────────────

	@Test
	void 확정과_보류는_누가_어느_조치에_무엇을_했는지_남고_사유는_남기지_않는다() throws Exception {
		RequestPostProcessor officer = user("aud-officer").roles("OFFICER");
		Action action = pendingAction("aud-decide");
		long mark = this.audit.lastId();

		this.mvc.perform(post("/api/v1/action-decisions").with(officer).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"actionId\": " + json(action.id()) + ", \"decision\": \"HOLD\", \"note\": \"지어낸 사유: 홍길동 담당\" }"))
			.andExpect(status().isOk());
		// 그 사이에 다른 사람이 확정했다 (HTTP 를 거치지 않아 감사 기록에는 남지 않는다). 그 뒤의 보류는 거부된다 (409).
		// 같은 사람이 같은 보류를 다시 보내는 것은 이제 성공이라 (D2 AD-325) 거부의 경우를 이렇게 만든다.
		new com.altumlab.tabnit.rules.ActionDecisions(this.dataSource).confirm(action.id(), "aud-other", null,
				java.time.Instant.parse("2026-07-01T00:00:00Z"));
		this.mvc.perform(post("/api/v1/action-decisions").with(officer).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"actionId\": " + json(action.id()) + ", \"decision\": \"HOLD\", \"note\": \"다시\" }"))
			.andExpect(status().isConflict());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).hasSize(4);
		assertThat(rows).extracting(AuditRecord::action).containsOnly("decideAction");
		assertThat(rows).extracting(AuditRecord::actor).containsOnly("aud-officer");
		// 본문의 항목은 하기 전에는 모른다. 결과의 줄에 남는다.
		assertThat(rows.get(0).outcome()).isEqualTo(AuditOutcome.STARTED);
		assertThat(rows.get(0).target()).isNull();
		assertThat(rows.get(1).outcome()).isEqualTo(AuditOutcome.SUCCESS);
		assertThat(rows.get(1).target()).isEqualTo("actionId=" + action.id());
		assertThat(rows.get(1).detail()).isEqualTo("decision=HOLD");
		assertThat(rows.get(1).startedId()).isEqualTo(rows.get(0).id());
		assertThat(rows.get(3).outcome()).isEqualTo(AuditOutcome.FAILED);
		assertThat(rows.get(3).httpStatus()).isEqualTo(409);
		assertThat(rows.get(3).target()).isEqualTo("actionId=" + action.id());
		// 사유에는 사람의 이름 같은 것이 들 수 있다. 감사 기록에 옮기지 않는다.
		assertThat(allText(rows)).doesNotContain("홍길동").doesNotContain("지어낸 사유");
	}

	@Test
	void 같은_사람이_다시_보낸_보류는_성공으로_답하고_감사_기록에도_성공으로_남는다() throws Exception {
		RequestPostProcessor officer = user("aud-officer").roles("OFFICER");
		Action action = pendingAction("aud-resend");
		long mark = this.audit.lastId();

		for (String note : List.of("기다린다", "다시 누름")) {
			this.mvc.perform(post("/api/v1/action-decisions").with(officer).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"actionId\": " + json(action.id()) + ", \"decision\": \"HOLD\", \"note\": \"" + note + "\" }"))
				.andExpect(status().isOk());
		}

		// 요청마다 두 줄이다. 다시 보낸 것도 성공으로 남는다 (D2 AD-325). 이력은 한 줄이다.
		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).extracting(AuditRecord::outcome)
			.containsExactly(AuditOutcome.STARTED, AuditOutcome.SUCCESS, AuditOutcome.STARTED, AuditOutcome.SUCCESS);
		assertThat(rows.get(3).target()).isEqualTo("actionId=" + action.id());
		assertThat(rows.get(3).detail()).isEqualTo("decision=HOLD");
		assertThat(new ActionRecords(this.dataSource).history(action.id())).hasSize(1);
	}

	@Test
	void 판_올리기와_Pack_적재가_대상과_함께_남는다() throws Exception {
		RequestPostProcessor admin = user("aud-admin").roles("ADMIN");
		PackCopies.copyInto(PACKS, "aud-load", text -> text);
		long mark = this.audit.lastId();

		this.mvc.perform(post("/api/v1/pack-loads").with(admin).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"packId\": \"aud-load\" }")).andExpect(status().isOk());
		this.mvc.perform(multipart("/api/v1/editions")
			.file(new org.springframework.mock.web.MockMultipartFile("file", "지어낸 사람 이름.csv", "text/csv",
					Files.readAllBytes(PACKS.resolve("aud-load/data/guideline-v1.csv"))))
			.param("packId", "aud-load")
			.param("inputSourceId", "guideline")
			.param("effectiveDate", "2026-01-01")
			.with(admin)
			.with(csrf())).andExpect(status().isOk());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).hasSize(4);
		assertThat(rows.get(0).action()).isEqualTo("loadPack");
		assertThat(rows.get(1).outcome()).isEqualTo(AuditOutcome.SUCCESS);
		assertThat(rows.get(1).target()).isEqualTo("packId=aud-load");
		assertThat(rows.get(1).detail()).isEqualTo("outcome=LOADED");
		// 폼의 항목은 하기 전에 안다.
		assertThat(rows.get(2).action()).isEqualTo("submitEdition");
		assertThat(rows.get(2).outcome()).isEqualTo(AuditOutcome.STARTED);
		assertThat(rows.get(2).target()).isEqualTo("packId=aud-load; inputSourceId=guideline; effectiveDate=2026-01-01");
		assertThat(rows.get(3).outcome()).isEqualTo(AuditOutcome.SUCCESS);
		assertThat(rows.get(3).detail()).isEqualTo("outcome=ACCEPTED; editionId=aud-load|guideline|1");
		// 파일의 이름과 내용은 남기지 않는다.
		assertThat(allText(rows)).doesNotContain("지어낸 사람 이름").doesNotContain("지침번호");
	}

	// ── 로그인, 로그아웃, 거부 ────────────────────────────────────────

	@Test
	void 로그인의_성공과_실패와_로그아웃이_남고_비밀번호는_어디에도_남지_않는다() throws Exception {
		this.accounts.create("aud-login", PASSWORD, Role.OFFICER, this.clock.instant());
		long mark = this.audit.lastId();

		// 있는 아이디에 틀린 비밀번호, 없는 아이디, 성공.
		login("aud-login", "틀린-시험용-비밀번호-5").andExpect(status().isUnauthorized());
		login("aud-login-none", PASSWORD).andExpect(status().isUnauthorized());
		MockHttpSession session = (MockHttpSession) login("aud-login", PASSWORD).andExpect(status().isOk())
			.andReturn()
			.getRequest()
			.getSession(false);
		this.mvc.perform(delete("/api/v1/session").session(session).with(csrf())).andExpect(status().isNoContent());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).hasSize(7);
		assertThat(rows.subList(0, 6)).extracting(AuditRecord::action).containsOnly("login");
		// 하기 전에는 누구인지 모른다.
		assertThat(rows.get(0).actor()).isNull();
		assertThat(rows.get(0).outcome()).isEqualTo(AuditOutcome.STARTED);
		// 실패: 누가는 비우고, 있는 아이디면 어느 아이디로 하려 했는지를 남긴다.
		assertThat(rows.get(1).outcome()).isEqualTo(AuditOutcome.DENIED);
		assertThat(rows.get(1).httpStatus()).isEqualTo(401);
		assertThat(rows.get(1).actor()).isNull();
		assertThat(rows.get(1).target()).isEqualTo("loginId=aud-login");
		// 없는 아이디는 남기지 않는다: 아이디 칸에 비밀번호를 잘못 넣은 것일 수 있다.
		assertThat(rows.get(3).outcome()).isEqualTo(AuditOutcome.DENIED);
		assertThat(rows.get(3).target()).isNull();
		// 성공: 누가가 채워진다.
		assertThat(rows.get(5).outcome()).isEqualTo(AuditOutcome.SUCCESS);
		assertThat(rows.get(5).actor()).isEqualTo("aud-login");
		assertThat(rows.get(5).startedId()).isEqualTo(rows.get(4).id());
		assertThat(rows.get(6).action()).isEqualTo("logout");
		assertThat(rows.get(6).actor()).isEqualTo("aud-login");
		assertThat(rows.get(6).outcome()).isEqualTo(AuditOutcome.SUCCESS);
		assertThat(allText(rows)).doesNotContain(PASSWORD).doesNotContain("틀린-시험용").doesNotContain("aud-login-none");
	}

	@Test
	void 권한이_없어_거부한_요청은_누가_무엇을_부르려_했는지_남는다() throws Exception {
		RequestPostProcessor officer = user("aud-denied").roles("OFFICER");
		long mark = this.audit.lastId();

		this.mvc.perform(post("/api/v1/pack-loads").with(officer).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"packId\": \"aud-secret\" }")).andExpect(status().isForbidden());
		// 위조 요청 방어에 걸린 것도 로그인한 사람의 것이면 남는다.
		this.mvc.perform(post("/api/v1/action-decisions").with(officer).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(AuditRecord::actor).containsOnly("aud-denied");
		assertThat(rows).extracting(AuditRecord::outcome).containsOnly(AuditOutcome.DENIED);
		assertThat(rows).extracting(AuditRecord::httpStatus).containsOnly(403);
		assertThat(rows.get(0).action()).isEqualTo("accessDenied");
		assertThat(rows.get(0).target()).isEqualTo("POST /api/v1/pack-loads");
		assertThat(rows.get(0).detail()).isEqualTo("reason=ROLE");
		assertThat(rows.get(1).target()).isEqualTo("POST /api/v1/action-decisions");
		assertThat(rows.get(1).detail()).isEqualTo("reason=CSRF");
		// 본문은 읽지 않았다.
		assertThat(allText(rows)).doesNotContain("aud-secret");
	}

	@Test
	void 로그인하지_않은_요청과_내_정보를_묻는_요청은_남기지_않는다() throws Exception {
		long mark = this.audit.lastId();

		this.mvc.perform(get("/api/v1/packs")).andExpect(status().isUnauthorized());
		this.mvc.perform(post("/api/v1/pack-loads").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
		// 로그인하지 않은 채 토큰 없이 보낸 로그인과 로그아웃.
		this.mvc.perform(post("/api/v1/session").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden());
		this.mvc.perform(delete("/api/v1/session").with(csrf())).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/v1/session")).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/v1/session").with(user("aud-me").roles("OFFICER"))).andExpect(status().isOk());
		this.mvc.perform(get("/actuator/health")).andExpect(status().isOk());

		assertThat(this.audit.after(mark)).isEmpty();
	}

	// ── 빠뜨리지 않는다 ──────────────────────────────────────────────

	@Test
	void 모든_API_는_감사_기록에_남기는지_남기지_않는지를_밝혀_적었다() {
		List<String> undeclared = new ArrayList<>();
		List<String> actions = new ArrayList<>();
		for (HandlerMethod handler : this.requestMappingHandlerMapping.getHandlerMethods().values()) {
			if (!handler.getBeanType().getPackageName().equals("com.altumlab.tabnit.api")) {
				continue;
			}
			Audited audited = handler.getMethodAnnotation(Audited.class);
			if (audited != null) {
				actions.add(audited.action());
			}
			else if (handler.getMethodAnnotation(NotAudited.class) == null) {
				undeclared.add(handler.toString());
			}
		}
		assertThat(undeclared).as("@Audited 나 @NotAudited 가 없는 API").isEmpty();
		// 기능의 이름은 규격의 operationId 와 같고 겹치지 않는다.
		assertThat(actions).doesNotHaveDuplicates()
			.containsExactlyInAnyOrder("getRelationsAsOf", "listPacks", "submitEdition", "listActions", "getAction",
					"decideAction", "getChangeImpactScope", "loadPack", "login", "listUnprocessedChanges",
					"reprocessChanges", "getRelationMap", "listEditions", "getEdition", "searchEntities",
					"exportRelations", "exportActions", "exportImpactTargets", "getRelationPath", "listAuditRecords", "getPackDefinition", "listPackFolders", "listChangeEvents", "compareEditions", "getPackRelationMap", "getAttributeValuesAsOf", "getVerificationResults", "getImpactFromEntity",
					"listConstraintViolations");
	}

	private org.springframework.test.web.servlet.ResultActions login(String loginId, String password) throws Exception {
		return this.mvc.perform(post("/api/v1/session").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"loginId\": \"" + loginId + "\", \"password\": \"" + password + "\" }"));
	}

	/** 시험용 Pack 의 사본에 판을 올려 대기인 조치 하나를 만든다. */
	private Action pendingAction(String packId) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		String[][] editions = { { "program", "program-v1.csv", "2026-01-01" }, { "guideline", "guideline-v1.csv", "2026-01-01" },
				{ "guideline-program", "guideline-program-v1.csv", "2026-01-01" },
				{ "partner-health", "partner-health-v1.csv", "2026-01-01" },
				{ "partner-private", "partner-private-v1.csv", "2026-01-01" }, { "team", "team-v1.csv", "2026-01-01" },
				{ "guideline", "guideline-v2.csv", "2026-03-01" } };
		for (String[] edition : editions) {
			this.processing.submit(packId, edition[0], edition[1], Files.readAllBytes(pack.resolve("data/" + edition[1])),
					LocalDate.parse(edition[2]));
		}
		List<Action> actions = new ActionRecords(this.dataSource).packActions(packId, ActionStatus.PENDING, null);
		assertThat(actions).isNotEmpty();
		return actions.get(0);
	}

	private static String allText(List<AuditRecord> rows) {
		StringBuilder text = new StringBuilder();
		for (AuditRecord row : rows) {
			text.append(row.actor()).append('\n').append(row.action()).append('\n').append(row.target()).append('\n')
				.append(row.detail()).append('\n');
		}
		return text.toString();
	}

	private static String json(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static Path createPacksDirectory() {
		try {
			return Files.createTempDirectory("tabnit-audit-packs").toRealPath();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
