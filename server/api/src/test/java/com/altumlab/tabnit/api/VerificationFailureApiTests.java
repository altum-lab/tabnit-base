package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.link.EditionPage;
import com.altumlab.tabnit.link.EditionRecords;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * E-23 의 후속 수정 (`/code-review 134`): 검증 질문을 화면에서 돌리다가 생기는 고장과 끼어드는 변경.
 *
 * <p>고장과 끼어듦은 실제로 만들기 어려워, 관계 조회와 판의 기록을 감싸 그때만 다르게 답하게 한다. 그 밖에는 실제 것이
 * 그대로 답한다. 서버의 Pack 폴더는 이 시험이 만든 임시 폴더다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class VerificationFailureApiTests {

	private static final Path PACKS = createPacksDirectory();

	private static final Pattern STEP = Pattern
		.compile("inputSource:\\s*([^,\\s]+)\\s*,\\s*file:\\s*([^,\\s]+)\\s*,\\s*effectiveDate:\\s*(\\d{4}-\\d{2}-\\d{2})");

	private static final RequestPostProcessor ADMIN = user("vf-admin").roles("ADMIN");

	@Autowired
	WebApplicationContext context;

	@MockitoSpyBean
	RelationsController relations;

	@MockitoSpyBean
	EditionRecords editions;

	MockMvc mvc;

	@DynamicPropertySource
	static void packsDirectory(DynamicPropertyRegistry registry) {
		registry.add("tabnit.packs-dir", PACKS::toString);
	}

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		Mockito.reset(this.relations, this.editions);
	}

	@Test
	void 묻다가_난_서버_안의_오류의_문구를_답에_담지_않는다() throws Exception {
		String packId = "vf-broken";
		prepareAndUpload(packId);
		// 관계 조회가 고장 난다: 예외의 문구에 밖으로 나가면 안 되는 글자가 있다.
		doThrow(new IllegalStateException("지어낸-비밀 SQL [select 숨길것 from 어딘가]")).when(this.relations)
			.relationsAsOf(any(), any(), any(), any());

		String body = results(packId);

		// 시점 조회의 질문은 실패다. 다른 종류의 질문은 그대로 묻는다.
		List<String> failed = JsonPath.read(body, "$.scenarios[0].results[?(@.passed == false)].kind");
		assertThat(failed).isNotEmpty().containsOnly("relations-as-of");
		assertThat(JsonPath.<List<String>>read(body, "$.scenarios[0].results[?(@.passed == true)].kind")).isNotEmpty();
		List<String> reasons = JsonPath.read(body, "$.scenarios[0].results[?(@.passed == false)].reason");
		assertThat(reasons).allMatch(reason -> reason.contains("500"));
		// 예외의 종류도 문구도 답에 없다.
		assertThat(body).doesNotContain("지어낸-비밀").doesNotContain("숨길것").doesNotContain("IllegalStateException");
	}

	@Test
	void 묻는_사이에_판이_달라지면_통과와_실패를_내지_않고_다시_돌리라고_알린다() throws Exception {
		String packId = "vf-moved";
		prepareAndUpload(packId);
		// 판의 목록을 두 번째로 읽을 때부터 (묻고 난 뒤의 확인) 판이 하나 더 있는 것처럼 답한다.
		AtomicInteger reads = new AtomicInteger();
		doAnswer(invocation -> {
			EditionPage real = (EditionPage) invocation.callRealMethod();
			if (reads.incrementAndGet() == 1 || real.editions().isEmpty()) {
				return real;
			}
			List<com.altumlab.tabnit.link.EditionListEntry> more = new java.util.ArrayList<>(real.editions());
			more.add(real.editions().get(0));
			return new EditionPage(more, real.total() + 1);
		}).when(this.editions).packEditions(eq(packId), anyInt(), anyInt());

		String body = results(packId);

		assertThat(JsonPath.<Boolean>read(body, "$.scenarios[0].matched")).isFalse();
		assertThat(JsonPath.<String>read(body, "$.scenarios[0].mismatch")).contains("묻는 사이").contains("다시");
		assertThat(JsonPath.<List<Object>>read(body, "$.scenarios[0].results")).isEmpty();
		assertThat(JsonPath.<Integer>read(body, "$.scenarios[0].passedCount")).isZero();
	}

	@Test
	void 두_번째로_물을_때는_저장된_판의_답도_다시_읽는다() throws Exception {
		String packId = "vf-twice";
		int steps = prepareAndUpload(packId);
		AtomicInteger storedReads = new AtomicInteger();
		doAnswer(invocation -> {
			storedReads.incrementAndGet();
			return invocation.callRealMethod();
		}).when(this.editions).changeEvents(any());

		String body = results(packId);

		assertThat(JsonPath.<Boolean>read(body, "$.scenarios[0].matched")).isTrue();
		assertThat(JsonPath.<Boolean>read(body, "$.scenarios[0].repeatable")).isTrue();
		// 단계마다의 저장된 판을 두 번 읽었다 (한 번만 읽어 두 번 견주면 변동의 질문은 제 자신과 견주는 것이다).
		assertThat(storedReads.get()).isGreaterThanOrEqualTo(steps * 2);
	}

	// ── 도우미 ────────────────────────────────────────────────

	private String results(String packId) throws Exception {
		return this.mvc.perform(get("/api/v1/verification-results").with(ADMIN).param("packId", packId))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	/** 서버의 Pack 폴더에 사본을 두고 적재한 뒤, 그 시나리오의 판을 차례대로 올린다. 단계의 수를 돌려준다. */
	private int prepareAndUpload(String packId) throws Exception {
		Path pack = PackCopies.copyInto(PACKS, packId, text -> text);
		this.mvc.perform(post(PackLoadsController.PATH).with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"packId\":\"" + packId + "\"}"))
			.andExpect(status().isOk());
		Matcher step = STEP.matcher(Files.readString(pack.resolve("questions/S-01.yaml"), StandardCharsets.UTF_8));
		int count = 0;
		while (step.find()) {
			Path file = pack.resolve(step.group(2).trim());
			this.mvc.perform(multipart("/api/v1/editions").with(ADMIN).with(csrf()).param("packId", packId)
					.param("inputSourceId", step.group(1).trim()).param("effectiveDate", step.group(3))
					.file(new MockMultipartFile("file", file.getFileName().toString(), "text/csv", Files.readAllBytes(file))))
				.andExpect(status().isOk());
			count++;
		}
		assertThat(count).isGreaterThan(3);
		return count;
	}

	private static Path createPacksDirectory() {
		try {
			return Files.createTempDirectory("tabnit-vf-packs-dir").toRealPath();
		}
		catch (IOException e) {
			throw new UncheckedIOException("시험의 Pack 폴더를 만들지 못했습니다", e);
		}
	}
}
