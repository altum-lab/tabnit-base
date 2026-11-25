package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * C-14: 지난 판의 목록과 변동마다의 조치의 수를 읽는 질의의 실행 계획을 잰다 (D2 AD-386). 시험이 아니라 재는 것이다.
 * 보통의 빌드에서는 돌지 않는다. 돌리는 법 (저장소의 server 폴더에서):
 *
 * <pre>TABNIT_MEASURE=editions ./gradlew :api:test --rerun --tests '*EditionQueryPlansMeasure*'</pre>
 *
 * <p>--rerun 을 빼면 Gradle 이 앞의 결과를 그대로 두고 돌리지 않는다 (환경 변수는 입력으로 보지 않는다).
 *
 * <p>판의 수는 TABNIT_MEASURE_EDITIONS 로 정한다 (없으면 2000). 빈 PostgreSQL 에 시험용 Pack 의 사본 둘을 적재하고,
 * 한 입력 자료에 두 파일을 번갈아 올려 판을 쌓는다 (판마다 변동과 조치가 생긴다). 통계를 다시 모은 뒤 제품이 쓰는
 * 질의의 실행 계획(analyze, buffers)을 적고, 고치기 전의 목록의 질의와 견주고, 받은 때의 색인이 있다면 어떻게 달라지는지를 되돌리는 트랜잭션 안에서 본다.
 * 결과는 build/measure/edition-plans.txt 에 남는다. 질의의 글은 link 의 EditionRecords.packEditions 와 rules 의
 * ActionRecords.actionCounts 에서 옮겨 적은 것이다: 그쪽을 고치면 여기도 고친다.
 * 모든 이름과 값은 지어낸 것이다. 걸린 시간은 잰 곳마다 다르다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TABNIT_MEASURE", matches = "editions")
class EditionQueryPlansMeasure {

	private static final String[][] FIRST = { { "program", "program-v1.csv" }, { "guideline", "guideline-v1.csv" },
			{ "guideline-program", "guideline-program-v1.csv" }, { "partner-health", "partner-health-v1.csv" },
			{ "partner-private", "partner-private-v1.csv" }, { "team", "team-v1.csv" } };

	private static final String LIST = """
			select e.id, e.pack_id, e.input_source_id, e.edition_no, e.effective_date, e.content_hash, e.file_name,
			       e.received_at,
			       (select count(*) from edition_item i where i.edition_id = e.id) as item_count,
			       (select count(*) from change_event c where c.edition_id = e.id) as change_count,
			       (select count(*) from edition_notice n where n.edition_id = e.id) as notice_count
			  from (select p.id, p.pack_id, p.input_source_id, p.edition_no, p.effective_date, p.content_hash,
			               p.file_name, p.received_at
			          from edition p
			         where p.pack_id = ?
			         order by p.received_at desc, p.input_source_id, p.edition_no desc
			        offset ? limit ?) e
			 order by e.received_at desc, e.input_source_id, e.edition_no desc
			""";

	/** 고치기 전의 목록의 질의 (C-14 전). 수를 세는 것과 쪽을 고르는 것이 한 문장이다. 견주려고 둔다. */
	private static final String LIST_BEFORE = """
			select e.id, e.pack_id, e.input_source_id, e.edition_no, e.effective_date, e.content_hash, e.file_name,
			       e.received_at,
			       (select count(*) from edition_item i where i.edition_id = e.id) as item_count,
			       (select count(*) from change_event c where c.edition_id = e.id) as change_count,
			       (select count(*) from edition_notice n where n.edition_id = e.id) as notice_count
			  from edition e
			 where e.pack_id = ?
			 order by e.received_at desc, e.input_source_id, e.edition_no desc
			offset ? limit ?
			""";

	private static final String COUNTS = """
			select a.change_event_hash, count(*) as action_count
			  from action a
			 where a.pack_id = ? and a.change_event_hash = any(?)
			 group by a.change_event_hash
			""";

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@TempDir
	Path temp;

	MockMvc mvc;

	private final StringBuilder report = new StringBuilder();

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
	}

	@Test
	void 판을_쌓아_지난_판의_목록과_조치의_수를_읽는_질의의_실행_계획을_적는다() throws Exception {
		int editions = Integer.parseInt(System.getenv().getOrDefault("TABNIT_MEASURE_EDITIONS", "2000"));
		String big = "plans-big";
		String small = "plans-small";
		fill(big, editions);
		fill(small, Math.max(10, editions / 10));

		try (Connection connection = this.dataSource.getConnection()) {
			connection.createStatement().execute("analyze");
			line("판의 수: " + big + " " + count(connection, "select count(*) from edition where pack_id = '" + big + "'")
					+ ", " + small + " " + count(connection, "select count(*) from edition where pack_id = '" + small + "'")
					+ ", 변동 이벤트 " + count(connection, "select count(*) from change_event") + ", 조치 "
					+ count(connection, "select count(*) from action") + ", 판의 항목 "
					+ count(connection, "select count(*) from edition_item"));

			// 지난 판의 목록: 전체 수, 첫 쪽, 깊은 쪽.
			int total = count(connection, "select count(*) from edition where pack_id = '" + big + "'");
			explain(connection, "판의 전체 수", "select count(*) from edition where pack_id = ?", big);
			explain(connection, "목록의 첫 쪽 (건너뛴 수 0, 한 번에 50)", LIST, big, 0, 50);
			explain(connection, "목록의 깊은 쪽 (건너뛴 수 " + (total - 50) + ", 한 번에 50)", LIST, big, total - 50, 50);
			explain(connection, "작은 Pack 의 첫 쪽", LIST, small, 0, 50);
			explain(connection, "[고치기 전의 질의] 목록의 첫 쪽", LIST_BEFORE, big, 0, 50);
			explain(connection, "[고치기 전의 질의] 목록의 깊은 쪽", LIST_BEFORE, big, total - 50, 50);

			// 받은 때의 색인이 있다면. 되돌린다.
			connection.setAutoCommit(false);
			connection.createStatement()
				.execute("create index edition_received_ix on edition (pack_id, received_at desc, input_source_id, edition_no desc)");
			connection.createStatement().execute("analyze edition");
			explain(connection, "[색인이 있다면] 목록의 첫 쪽", LIST, big, 0, 50);
			explain(connection, "[색인이 있다면] 목록의 깊은 쪽", LIST, big, total - 50, 50);
			connection.rollback();
			connection.setAutoCommit(true);

			// 조치의 수: 판 하나의 변동, Pack 의 변동 전부.
			List<String> one = hashes(connection,
					"select encode(sha256(convert_to(id, 'UTF8')), 'hex') from change_event where edition_id = ? order by id",
					big + "|guideline|" + (total - FIRST.length + 1));
			List<String> all = hashes(connection, """
					select encode(sha256(convert_to(c.id, 'UTF8')), 'hex') from change_event c
					  join edition e on e.id = c.edition_id where e.pack_id = ? order by c.id""", big);
			explain(connection, "조치의 수: 판 하나의 변동 " + one.size() + "개", COUNTS, big, array(connection, one));
			explain(connection, "조치의 수: Pack 의 변동 전부 " + all.size() + "개", COUNTS, big, array(connection, all));
			line("조치가 있는 변동 (판 하나에서): "
					+ count(connection, "select count(distinct change_event_hash) from action where change_event_hash in ('"
							+ String.join("','", one) + "')"));
		}

		// API 로 걸린 시간. 다섯 번 재어 가운데 값.
		line("");
		line("API 로 잰 시간 (다섯 번의 가운데 값, MockMvc):");
		String latest = big + "|guideline|" + (editions + 1);
		time("GET /api/v1/editions 첫 쪽", () -> this.mvc.perform(get("/api/v1/editions").param("packId", big))
			.andExpect(status().isOk()));
		time("GET /api/v1/editions 깊은 쪽", () -> this.mvc
			.perform(get("/api/v1/editions").param("packId", big).param("offset", String.valueOf(editions - 50)))
			.andExpect(status().isOk()));
		time("GET /api/v1/edition (판 하나)", () -> this.mvc.perform(get("/api/v1/edition").param("editionId", latest))
			.andExpect(status().isOk()));

		Path out = Path.of("build/measure/edition-plans.txt");
		Files.createDirectories(out.getParent());
		Files.writeString(out, this.report.toString(), StandardCharsets.UTF_8);
	}

	/** Pack 의 사본을 적재하고 처음 자료를 넣은 뒤, 한 입력 자료에 두 파일을 번갈아 올려 판을 쌓는다. */
	private void fill(String packId, int editions) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] first : FIRST) {
			upload(packId, first[0], LocalDate.of(2026, 1, 1), pack.resolve("data/" + first[1]));
		}
		long started = System.nanoTime();
		int accepted = 0;
		for (int i = 0; i < editions; i++) {
			String body = upload(packId, "guideline", LocalDate.of(2026, 1, 2).plusDays(i),
					pack.resolve(i % 2 == 0 ? "data/guideline-v2.csv" : "data/guideline-v1.csv"));
			if ("ACCEPTED".equals(JsonPath.read(body, "$.outcome"))) {
				accepted++;
			}
		}
		line(String.format(Locale.ROOT, "%s: 판 %d개를 올려 %d개를 받아들였다. 판 하나에 %.1f 밀리초", packId, editions, accepted,
				(System.nanoTime() - started) / 1e6 / editions));
		assertThat(accepted).isEqualTo(editions);
	}

	private String upload(String packId, String inputSourceId, LocalDate effectiveDate, Path file) throws Exception {
		return this.mvc.perform(multipart("/api/v1/editions").param("packId", packId)
			.param("inputSourceId", inputSourceId)
			.param("effectiveDate", effectiveDate.toString())
			.file(new MockMultipartFile("file", file.getFileName().toString(), "text/csv", Files.readAllBytes(file))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
	}

	private void explain(Connection connection, String title, String sql, Object... arguments) throws Exception {
		line("");
		line("── " + title + " ──");
		try (PreparedStatement statement = connection.prepareStatement("explain (analyze, buffers) " + sql)) {
			for (int i = 0; i < arguments.length; i++) {
				statement.setObject(i + 1, arguments[i]);
			}
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					line(rs.getString(1));
				}
			}
		}
	}

	private static java.sql.Array array(Connection connection, List<String> values) throws Exception {
		return connection.createArrayOf("text", values.toArray(String[]::new));
	}

	private static List<String> hashes(Connection connection, String sql, String argument) throws Exception {
		List<String> found = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, argument);
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					found.add(rs.getString(1));
				}
			}
		}
		return found;
	}

	private static int count(Connection connection, String sql) throws Exception {
		try (ResultSet rs = connection.createStatement().executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		}
	}

	private interface Call {

		void run() throws Exception;

	}

	private void time(String title, Call call) throws Exception {
		List<Long> taken = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			long started = System.nanoTime();
			call.run();
			taken.add(System.nanoTime() - started);
		}
		taken.sort(null);
		line(String.format(Locale.ROOT, "  %s: %.1f 밀리초 (가장 큰 값 %.1f)", title, taken.get(2) / 1e6, taken.get(4) / 1e6));
	}

	private void line(String text) {
		this.report.append(text).append('\n');
	}
}
