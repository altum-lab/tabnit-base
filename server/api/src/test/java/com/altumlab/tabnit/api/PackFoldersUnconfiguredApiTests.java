package com.altumlab.tabnit.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-22: 서버에 Pack 폴더가 정해져 있지 않을 때 (설치 설정 tabnit.packs-dir 가 없다). 오류가 아니라 정해져 있지 않다고 답한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "tabnit.packs-dir=")
class PackFoldersUnconfiguredApiTests {

	@Autowired
	WebApplicationContext context;

	@Test
	void Pack_폴더가_정해져_있지_않으면_빈_목록과_함께_그렇다고_답한다() throws Exception {
		MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build()
			.perform(get("/api/v1/pack-folders").with(user("pfu-admin").roles("ADMIN")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.configured", is(false)))
			.andExpect(jsonPath("$.folders", hasSize(0)));
	}
}
