package com.altumlab.tabnit.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
 * C-01 의 엑셀을 API 까지 이은 시험: 엑셀로 받는 입력 자료에 엑셀 파일을 올리면 CSV 와 같이 판이 되고 변동이 나온다
 * (D6 7절 "엑셀 파일", D7 5절).
 *
 * <p>시험용 Pack 의 사본에서 입력 자료 하나의 매핑을 엑셀로 바꿔 쓴다. 엑셀 파일은 그 Pack 의 CSV 자료를 옮겨 이
 * 시험이 만든다 (엑셀을 읽는 라이브러리는 link 안에서만 쓰므로 여기서는 파일의 XML 을 손으로 적는다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class XlsxEditionsApiTests {

	private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

	private static final String SHEET = "지침";

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
	void 엑셀_파일을_올리면_판이_되고_다음_판에서_바뀐_항목과_바뀐_값이_나온다() throws Exception {
		String packId = "xl-up";
		Path pack = loadWithXlsxGuideline(packId);

		upload(packId, "2026-01-01", "guideline-v1.xlsx", fromCsv(pack.resolve("data/guideline-v1.csv")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("ACCEPTED"))
			.andExpect(jsonPath("$.itemCount").value(3))
			.andExpect(jsonPath("$.notices", hasSize(0)))
			.andExpect(jsonPath("$.changes", hasSize(0)));

		// 둘째 판: 한 행의 수가 바뀌고, 한 행이 없어지고, 한 행이 생긴다.
		upload(packId, "2026-02-01", "guideline-v2.xlsx", fromCsv(pack.resolve("data/guideline-v2.csv")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("ACCEPTED"))
			.andExpect(jsonPath("$.notices", hasSize(0)))
			.andExpect(jsonPath("$.changes", hasSize(3)))
			// 바뀐 항목은 항목의 키 순이다. 맨 앞이 수가 바뀐 행이다.
			.andExpect(jsonPath("$.changes[0].changedValues", hasSize(1)))
			.andExpect(jsonPath("$.changes[0].changedValues[0].before").value("30"))
			.andExpect(jsonPath("$.changes[0].changedValues[0].after").value("14"))
			.andExpect(jsonPath("$.changes[1].changedValues[0].after").doesNotExist());
	}

	@Test
	void 엑셀로_받는_입력_자료에_CSV_를_올리면_받아들이지_않고_무엇을_하면_되는지_알린다() throws Exception {
		String packId = "xl-wrong";
		Path pack = loadWithXlsxGuideline(packId);

		upload(packId, "2026-01-01", "guideline-v1.csv", Files.readAllBytes(pack.resolve("data/guideline-v1.csv")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("EDITION_REJECTED"))
			.andExpect(jsonPath("$.reasonCode").value("NOT_XLSX"))
			.andExpect(jsonPath("$.detail").value(containsString("xlsx")));
	}

	@Test
	void 시트의_이름이_다르면_받아들이지_않고_찾던_시트를_알린다() throws Exception {
		String packId = "xl-sheet";
		loadWithXlsxGuideline(packId);

		upload(packId, "2026-01-01", "guideline.xlsx", xlsx("다른 시트", List.of(List.of("지침번호", "제목", "점검주기일"))))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reasonCode").value("SHEET_NOT_FOUND"))
			.andExpect(jsonPath("$.reasonDetail").value(SHEET))
			.andExpect(jsonPath("$.detail").value(containsString(SHEET)));
	}

	@Test
	void 계산_결과가_없는_수식의_행은_건너뛰고_어느_열인지_알린다() throws Exception {
		String packId = "xl-formula";
		loadWithXlsxGuideline(packId);

		upload(packId, "2026-01-01", "guideline.xlsx",
				xlsx(SHEET, List.of(List.of("지침번호", "제목", "점검주기일"), List.of("G-91", "지어낸 기준 하나", "30"),
						List.of("G-92", "지어낸 기준 둘", "=10*3"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.itemCount").value(1))
			.andExpect(jsonPath("$.notices", hasSize(1)))
			.andExpect(jsonPath("$.notices[0].kind").value("SKIPPED_ROW"))
			.andExpect(jsonPath("$.notices[0].code").value("FORMULA_WITHOUT_RESULT"))
			.andExpect(jsonPath("$.notices[0].rowNo").value(3))
			.andExpect(jsonPath("$.notices[0].message").value(containsString("점검주기일 열")));
	}

	// ── 도우미 ───────────────────────────────────────────────────

	/** 시험용 Pack 의 사본을 적재한다. 입력 자료 guideline 의 매핑만 엑셀로 받게 고친다. */
	private Path loadWithXlsxGuideline(String packId) throws IOException {
		String csv = "tabnit:fileFormat tabnit:Csv ;";
		return PackCopies.load(this.dataSource, this.temp, packId, text -> {
			if (!text.contains("tabnit:inputSourceId \"guideline\" ;")) {
				return text;
			}
			if (!text.contains(csv)) {
				throw new IllegalStateException("시험용 Pack 의 guideline 매핑에 \"" + csv + "\" 가 없습니다. 이 시험이 고칠 곳이 없어졌습니다.");
			}
			return text.replace(csv, "tabnit:fileFormat tabnit:Xlsx ;\n    tabnit:sheetName \"" + SHEET + "\" ;");
		});
	}

	private ResultActions upload(String packId, String effectiveDate, String fileName, byte[] content) throws Exception {
		return this.mvc.perform(multipart("/api/v1/editions").file(new MockMultipartFile("file", fileName,
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content))
			.param("packId", packId)
			.param("inputSourceId", "guideline")
			.param("effectiveDate", effectiveDate));
	}

	/** 쉼표와 따옴표가 없는 단순한 CSV 를 엑셀로 옮긴다. */
	private static byte[] fromCsv(Path csv) throws IOException {
		return xlsx(SHEET, Files.readAllLines(csv, StandardCharsets.UTF_8)
			.stream()
			.filter(line -> !line.isBlank())
			.map(line -> List.of(line.split(",", -1)))
			.toList());
	}

	/**
	 * 시트 하나인 엑셀 파일을 만든다. 숫자만 든 값은 수가 든 칸으로, = 로 시작하는 값은 계산 결과가 없는 수식으로,
	 * 나머지는 글자가 든 칸으로 적는다.
	 */
	private static byte[] xlsx(String sheetName, List<List<String>> rows) throws IOException {
		StringBuilder data = new StringBuilder();
		for (int r = 0; r < rows.size(); r++) {
			data.append("<row r=\"").append(r + 1).append("\">");
			for (String value : rows.get(r)) {
				if (value.startsWith("=")) {
					data.append("<c><f>").append(value.substring(1)).append("</f></c>");
				}
				else if (!value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
					data.append("<c><v>").append(value).append("</v></c>");
				}
				else {
					data.append("<c t=\"inlineStr\"><is><t>").append(value).append("</t></is></c>");
				}
			}
			data.append("</row>");
		}
		String relationships = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
		String packageRelationships = "http://schemas.openxmlformats.org/package/2006/relationships";
		String contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.";
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
				entry(zip, "[Content_Types].xml",
						"<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
								+ "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
								+ "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
								+ "<Override PartName=\"/xl/workbook.xml\" ContentType=\"" + contentType + "sheet.main+xml\"/>"
								+ "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"" + contentType
								+ "worksheet+xml\"/></Types>");
				entry(zip, "_rels/.rels", "<Relationships xmlns=\"" + packageRelationships + "\"><Relationship Id=\"rId1\" Type=\""
						+ relationships + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
				entry(zip, "xl/workbook.xml", "<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + relationships
						+ "\"><sheets><sheet name=\"" + sheetName + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
				entry(zip, "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"" + packageRelationships
						+ "\"><Relationship Id=\"rId1\" Type=\"" + relationships
						+ "/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
				entry(zip, "xl/worksheets/sheet1.xml",
						"<worksheet xmlns=\"" + MAIN + "\"><sheetData>" + data + "</sheetData></worksheet>");
			}
			return out.toByteArray();
		}
	}

	private static void entry(ZipOutputStream zip, String name, String xml) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + xml).getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
