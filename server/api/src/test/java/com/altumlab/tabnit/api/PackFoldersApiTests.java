package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditRecord;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-22: 서버의 Pack 폴더에 놓인 Pack 의 폴더를 읽는 API (docs/api/openapi.yaml 의 listPackFolders, D7 10절, D2 AD-416).
 *
 * <p>서버의 Pack 폴더는 이 시험이 만든 임시 폴더다. 그 안에 시험용 Pack 의 사본을 둔다. 모든 이름은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PackFoldersApiTests {

	private static final Path PACKS = createPacksDirectory();

	private static final RequestPostProcessor ADMIN = user("pf-admin").roles("ADMIN");

	private static final RequestPostProcessor OFFICER = user("pf-officer").roles("OFFICER");

	@Autowired
	WebApplicationContext context;

	@Autowired
	AuditLog audit;

	MockMvc mvc;

	@DynamicPropertySource
	static void packsDirectory(DynamicPropertyRegistry registry) {
		registry.add("tabnit.packs-dir", PACKS::toString);
	}

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	@Test
	void 서버의_Pack_폴더에_놓인_폴더를_이름의_순서로_주고_적재됐는지를_함께_준다() throws Exception {
		// 넣는 차례를 섞는다.
		PackCopies.copyInto(PACKS, "pf-zeta", text -> text);
		PackCopies.copyInto(PACKS, "pf-alpha", text -> text);
		PackCopies.copyInto(PACKS, "pf-mid-10", text -> text);
		PackCopies.copyInto(PACKS, "pf-mid-2", text -> text);

		String before = folders();
		List<String> names = JsonPath.read(before, "$.folders[*].packId");
		// 글자 코드 순이다: pf-mid-10 이 pf-mid-2 보다 앞선다. 이 폴더는 다른 시험과 함께 쓴다: 이 시험이 둔 넷의 차례를 본다.
		List<String> mine = List.of("pf-alpha", "pf-mid-10", "pf-mid-2", "pf-zeta");
		assertThat(names.stream().filter(mine::contains).toList()).containsExactlyElementsOf(mine);
		assertThat(names).isSorted().doesNotHaveDuplicates();
		assertThat(JsonPath.<Boolean>read(before, "$.configured")).isTrue();
		assertThat(JsonPath.<List<Boolean>>read(before, "$.folders[?(@.packId=='pf-alpha')].loaded")).containsExactly(false);

		// 적재하면 적재됐다고 나온다. 다른 폴더는 그대로다.
		this.mvc.perform(post("/api/v1/pack-loads").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"packId\":\"pf-alpha\"}"))
			.andExpect(status().isOk());

		String after = folders();
		assertThat(JsonPath.<List<Boolean>>read(after, "$.folders[?(@.packId=='pf-alpha')].loaded")).containsExactly(true);
		assertThat(JsonPath.<List<Boolean>>read(after, "$.folders[?(@.packId=='pf-zeta')].loaded")).containsExactly(false);
		// 두 번 물어도 같다.
		assertThat(folders()).isEqualTo(after);
	}

	@Test
	void 적재할_수_없는_이름의_폴더와_파일과_숨은_폴더와_바로가기는_보이지_않는다() throws Exception {
		PackCopies.copyInto(PACKS, "pf-shown", text -> text);
		Files.createDirectories(PACKS.resolve("PF-Upper"));
		Files.createDirectories(PACKS.resolve("pf_underscore"));
		Files.createDirectories(PACKS.resolve(".pf-hidden"));
		Files.createDirectories(PACKS.resolve("pf 빈칸"));
		Files.writeString(PACKS.resolve("pf-file"), "폴더가 아니다", StandardCharsets.UTF_8);
		Path outside = Files.createTempDirectory("tabnit-pf-outside");
		PackCopies.copyInto(outside, "pf-linked-target", text -> text);
		Files.createSymbolicLink(PACKS.resolve("pf-linked"), outside.resolve("pf-linked-target"));

		List<String> names = JsonPath.read(folders(), "$.folders[*].packId");

		assertThat(names).contains("pf-shown")
			.doesNotContain("PF-Upper", "pf_underscore", ".pf-hidden", "pf 빈칸", "pf-file", "pf-linked");
		// 폴더 안을 읽어 검증하지 않는다: 빈 폴더도 이름이 맞으면 보인다. 틀렸는지는 적재할 때 안다.
		Files.createDirectories(PACKS.resolve("pf-empty"));
		assertThat(JsonPath.<List<String>>read(folders(), "$.folders[*].packId")).contains("pf-empty");
	}

	@Test
	void 응답에_서버의_폴더_경로를_담지_않는다() throws Exception {
		PackCopies.copyInto(PACKS, "pf-path", text -> text);

		assertThat(folders()).doesNotContain(PACKS.toString()).doesNotContain(PACKS.getFileName().toString());
	}

	@Test
	void 관리자만_보고_담당자는_403_이며_본_것은_감사_기록에_남는다() throws Exception {
		this.mvc.perform(get("/api/v1/pack-folders").with(OFFICER)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("FORBIDDEN")));
		this.mvc.perform(get("/api/v1/pack-folders")).andExpect(status().isUnauthorized());

		long mark = this.audit.lastId();
		this.mvc.perform(get("/api/v1/pack-folders").with(ADMIN)).andExpect(status().isOk());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).extracting(AuditRecord::action).containsExactly("listPackFolders", "listPackFolders");
		assertThat(rows).extracting(AuditRecord::actor).containsOnly("pf-admin");
	}

	private String folders() throws Exception {
		return this.mvc.perform(get("/api/v1/pack-folders").with(ADMIN)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
	}

	private static Path createPacksDirectory() {
		try {
			return Files.createTempDirectory("tabnit-pack-folders").toRealPath();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
