package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditEntry;
import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditLogException;
import com.altumlab.tabnit.common.AuditOutcome;
import com.altumlab.tabnit.common.Role;
import com.altumlab.tabnit.common.UserAccounts;
import com.altumlab.tabnit.pack.PackDefinitions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 감사 기록을 남기지 못했을 때 (D2 AD-05, AD-273, AD-275).
 *
 * <p>하기 전의 줄을 남기지 못하면 변경을 하지 않고 조회도 답하지 않는다. 결과의 줄을 남기지 못한 것은 되돌릴 수 없다:
 * 일은 이미 끝났고 하기 전의 줄이 남아 있다. 저장이 고장 난 것은 감사 기록을 바꿔 끼워 흉내 낸다.
 */
@Import({ TestcontainersConfiguration.class, AuditFailureApiTests.FailingAudit.class })
@SpringBootTest
class AuditFailureApiTests {

	private static final String PASSWORD = "지어낸-시험용-비밀번호-6";

	private static final Path PACKS = createPacksDirectory();

	/** 이 결과의 줄은 남기지 못한다. */
	private static final Set<AuditOutcome> BROKEN = EnumSet.noneOf(AuditOutcome.class);

	@Autowired
	WebApplicationContext context;

	@Autowired
	AuditLog audit;

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

	@AfterEach
	void repair() {
		BROKEN.clear();
	}

	@Test
	void 하기_전의_줄을_남기지_못하면_조회는_답하지_않고_변경은_하지_않는다() throws Exception {
		RequestPostProcessor admin = user("fail-admin").roles("ADMIN");
		PackCopies.copyInto(PACKS, "fail-pack", text -> text);
		this.accounts.create("fail-login", PASSWORD, Role.OFFICER, this.clock.instant());
		MockHttpSession session = (MockHttpSession) login("fail-login").andExpect(status().isOk())
			.andReturn()
			.getRequest()
			.getSession(false);
		BROKEN.add(AuditOutcome.STARTED);

		// 조회: 내용을 주지 않는다.
		this.mvc.perform(get("/api/v1/packs").with(admin))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value("INTERNAL"))
			.andExpect(jsonPath("$.packs").doesNotExist());
		// 변경: 하지 않는다.
		this.mvc.perform(post("/api/v1/pack-loads").with(admin).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"packId\": \"fail-pack\" }")).andExpect(status().isInternalServerError());
		assertThat(this.definitions.find("fail-pack")).isEmpty();
		// 로그인: 되지 않는다.
		MockHttpSession fresh = new MockHttpSession();
		this.mvc.perform(post("/api/v1/session").session(fresh).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"loginId\": \"fail-login\", \"password\": \"" + PASSWORD + "\" }"))
			.andExpect(status().isInternalServerError());
		this.mvc.perform(get("/api/v1/session").session(fresh)).andExpect(status().isUnauthorized());

		// 고쳐지면 다시 된다.
		BROKEN.clear();
		this.mvc.perform(get("/api/v1/packs").session(session)).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/pack-loads").with(admin).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"packId\": \"fail-pack\" }")).andExpect(status().isOk());
	}

	@Test
	void 로그아웃을_남기지_못하면_로그아웃하지_않는다() throws Exception {
		this.accounts.create("fail-logout", PASSWORD, Role.OFFICER, this.clock.instant());
		MockHttpSession session = (MockHttpSession) login("fail-logout").andExpect(status().isOk())
			.andReturn()
			.getRequest()
			.getSession(false);
		BROKEN.add(AuditOutcome.SUCCESS);

		this.mvc.perform(delete("/api/v1/session").session(session).with(csrf()))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL"));

		assertThat(session.isInvalid()).isFalse();
		BROKEN.clear();
		this.mvc.perform(get("/api/v1/session").session(session)).andExpect(status().isOk());
	}

	@Test
	void 결과의_줄을_남기지_못해도_끝난_일은_그대로이고_하기_전의_줄이_남아_있다() throws Exception {
		RequestPostProcessor admin = user("fail-finish").roles("ADMIN");
		PackCopies.copyInto(PACKS, "fail-finish-pack", text -> text);
		long mark = this.audit.lastId();
		BROKEN.addAll(EnumSet.of(AuditOutcome.SUCCESS, AuditOutcome.FAILED, AuditOutcome.DENIED));

		this.mvc.perform(get("/api/v1/packs").with(admin)).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/pack-loads").with(admin).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"packId\": \"fail-finish-pack\" }")).andExpect(status().isOk());
		// 거부를 남기지 못해도 거부는 한다.
		this.mvc.perform(post("/api/v1/pack-loads").with(user("fail-officer").roles("OFFICER")).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"packId\": \"fail-finish-pack\" }")).andExpect(status().isForbidden());

		assertThat(this.definitions.find("fail-finish-pack")).isPresent();
		assertThat(this.audit.after(mark)).extracting(row -> row.action() + ":" + row.outcome())
			.containsExactly("listPacks:STARTED", "loadPack:STARTED");
	}

	private org.springframework.test.web.servlet.ResultActions login(String loginId) throws Exception {
		return this.mvc.perform(post("/api/v1/session").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"loginId\": \"" + loginId + "\", \"password\": \"" + PASSWORD + "\" }"));
	}

	private static Path createPacksDirectory() {
		try {
			return Files.createTempDirectory("tabnit-audit-fail-packs").toRealPath();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 정한 결과의 줄을 남기지 못하는 감사 기록. 그 밖에는 진짜와 같다. */
	@TestConfiguration(proxyBeanMethods = false)
	static class FailingAudit {

		@Bean
		@Primary
		AuditLog failingAuditLog(DataSource dataSource) {
			return new AuditLog(dataSource) {
				@Override
				public long record(AuditEntry entry) {
					if (BROKEN.contains(entry.outcome())) {
						throw new AuditLogException("시험: 감사 기록의 저장이 고장 났다", null);
					}
					return super.record(entry);
				}
			};
		}

	}

}
