package com.altumlab.tabnit.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-15: 판을 올린 답의 개체가 유형, 키, 표시 이름과 함께 오고, 바뀐 값에 화면에 보일 글자가 함께 온다
 * (docs/api/openapi.yaml 의 EditionChange, ChangedValue. D7 5절, D2 AD-381).
 *
 * <p>시험용 Pack 의 사본을 쓴다. 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChangeNamesApiTests {

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@TempDir
	Path temp;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
	}

	@Test
	void 바뀐_항목의_개체는_유형과_키와_그_판의_적용일의_표시_이름과_함께_온다() throws Exception {
		String packId = "cn-names";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		upload(packId, "guideline", "2026-01-01", pack.resolve("data/guideline-v1.csv")).andExpect(status().isOk());

		// 둘째 판: G-01 은 수가 바뀌고, G-03 은 없어지고, G-04 가 생긴다. 항목의 키 순이다.
		ResultActions result = upload(packId, "guideline", "2026-03-01", pack.resolve("data/guideline-v2.csv"));

		result.andExpect(status().isOk()).andExpect(jsonPath("$.changes", hasSize(3)));
		// 변경: 식별자 항목은 그대로 있고, 같은 개체가 이름과 함께 온다.
		result.andExpect(jsonPath("$.changes[0].primaryEntityId").value(packId + "|운영지침|G-01"))
			.andExpect(jsonPath("$.changes[0].primaryEntity.entityId").value(packId + "|운영지침|G-01"))
			.andExpect(jsonPath("$.changes[0].primaryEntity.entityType").value("운영지침"))
			.andExpect(jsonPath("$.changes[0].primaryEntity.entityTypeLabel").isString())
			.andExpect(jsonPath("$.changes[0].primaryEntity.entityKey").value("G-01"))
			.andExpect(jsonPath("$.changes[0].primaryEntity.displayName").value("방문 건강 관리 운영 기준"))
			.andExpect(jsonPath("$.changes[0].targetEntityIds", hasSize(1)))
			.andExpect(jsonPath("$.changes[0].targetEntities", hasSize(1)))
			.andExpect(jsonPath("$.changes[0].targetEntities[0].entityId").value(packId + "|운영지침|G-01"))
			.andExpect(jsonPath("$.changes[0].targetEntities[0].displayName").value("방문 건강 관리 운영 기준"))
			.andExpect(jsonPath("$.changes[0].changedValues[0].entityId").value(packId + "|운영지침|G-01"))
			.andExpect(jsonPath("$.changes[0].changedValues[0].entity.entityKey").value("G-01"))
			.andExpect(jsonPath("$.changes[0].changedValues[0].entity.displayName").value("방문 건강 관리 운영 기준"))
			// 참·거짓이 아닌 속성은 보일 글자가 저장한 글자와 같다.
			.andExpect(jsonPath("$.changes[0].changedValues[0].before").value("30"))
			.andExpect(jsonPath("$.changes[0].changedValues[0].beforeDisplay").value("30"))
			.andExpect(jsonPath("$.changes[0].changedValues[0].afterDisplay").value("14"));
		// 삭제: 그 판의 적용일에는 제목이 끝났다. 앞의 값을 끌어다 쓰지 않고 표시 이름이 없다 (화면은 키를 보인다).
		result.andExpect(jsonPath("$.changes[1].kind").value("REMOVED"))
			.andExpect(jsonPath("$.changes[1].primaryEntity.entityKey").value("G-03"))
			.andExpect(jsonPath("$.changes[1].primaryEntity.displayName").value(nullValue()))
			.andExpect(jsonPath("$.changes[1].changedValues[0].afterDisplay").value(nullValue()));
		// 추가: 새로 들어온 제목이 표시 이름이다.
		result.andExpect(jsonPath("$.changes[2].kind").value("ADDED"))
			.andExpect(jsonPath("$.changes[2].primaryEntity.displayName").value("이동 검진 운영 기준"))
			.andExpect(jsonPath("$.changes[2].changedValues[0].beforeDisplay").value(nullValue()));
	}

	@Test
	void 참_거짓_속성의_바뀐_값은_저장한_글자는_그대로이고_보일_글자가_예와_아니오로_온다() throws Exception {
		String packId = "cn-boolean";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		upload(packId, "program", "2026-01-01", pack.resolve("data/program-v1.csv")).andExpect(status().isOk());

		// 둘째 판: P-01 의 목표 인원이 바뀌고, P-03 의 운영 여부가 아니오에서 예로 바뀐다.
		ResultActions result = upload(packId, "program", "2026-03-01", pack.resolve("data/program-v2.csv"));

		String flag = "$.changes[?(@.itemKey == 'P-03')].changedValues[?(@.attributeName == '운영여부')]";
		result.andExpect(status().isOk())
			.andExpect(jsonPath(flag + ".before").value("false"))
			.andExpect(jsonPath(flag + ".after").value("true"))
			.andExpect(jsonPath(flag + ".beforeDisplay").value("아니오"))
			.andExpect(jsonPath(flag + ".afterDisplay").value("예"));
		// 수의 속성은 그대로다.
		String count = "$.changes[?(@.itemKey == 'P-01')].changedValues[?(@.attributeName == '연간목표인원')]";
		result.andExpect(jsonPath(count + ".beforeDisplay").value("1200"))
			.andExpect(jsonPath(count + ".afterDisplay").value("1500"));
	}

	private ResultActions upload(String packId, String inputSourceId, String effectiveDate, Path file) throws Exception {
		return this.mvc.perform(multipart("/api/v1/editions").param("packId", packId)
			.param("inputSourceId", inputSourceId)
			.param("effectiveDate", effectiveDate)
			.file(new MockMultipartFile("file", file.getFileName().toString(), "text/csv", Files.readAllBytes(file))));
	}
}
