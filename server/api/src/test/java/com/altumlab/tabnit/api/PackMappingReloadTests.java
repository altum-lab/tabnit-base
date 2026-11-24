package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.altumlab.tabnit.link.EditionIngest;
import com.altumlab.tabnit.link.IngestOutcome;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.pack.PackLoadResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * C-09: Pack 을 다시 적재할 때, 판이 들어오지 않은 입력 자료의 매핑은 고칠 수 있고 판이 들어온 입력 자료의 매핑은 고칠 수
 * 없다 (D6 7절, D2 AD-142, AD-159). 판이 있는지는 api 가 link 에 물어 pack 에 넘긴다.
 *
 * <p>시험용 Pack 의 사본을 쓴다. 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PackMappingReloadTests {

	private static final String GUIDELINE_COLUMNS = "tabnit:requiredColumns ( \"지침번호\" \"제목\" \"점검주기일\" ) ;";

	private static final String PROGRAM_SOURCE = "tabnit:inputSourceId \"program\" ;";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private Clock clock;

	@Autowired
	private EditionProcessing processing;

	@TempDir
	private Path packs;

	@Test
	void 판이_없는_동안에는_매핑을_고칠_수_있고_판이_들어온_뒤에는_그_입력_자료의_매핑만_고칠_수_없다() throws Exception {
		String packId = "re-map";
		PackLoading loading = new PackLoading(this.dataSource, this.clock, this.packs.toString(), this.processing);
		PackCopies.copyInto(this.packs, packId, text -> text);
		assertThat(loading.load(packId).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
		// 시험용 Pack 이 바뀌어 고칠 곳이 없어졌으면 여기서 알린다.
		String guideline = Files.readString(PackCopies.SOURCE.resolve("mappings/guideline.ttl"));
		assertThat(guideline).contains(GUIDELINE_COLUMNS);
		UnaryOperator<String> guidelineChanged = text -> text.replace(GUIDELINE_COLUMNS,
				"tabnit:requiredColumns ( \"지침번호\" \"제목\" ) ;");

		// 판이 하나도 없다. 매핑을 바꿔 다시 적재할 수 있다.
		PackCopies.copyInto(this.packs, packId, guidelineChanged);
		assertThat(loading.load(packId).outcome()).isEqualTo(PackLoadResult.Outcome.UPDATED);
		assertThat(mapping(packId, "guideline").requiredColumns()).containsExactly("지침번호", "제목");

		// guideline 에 판이 들어온다.
		PackDefinition pack = new PackDefinitions(this.dataSource).find(packId).orElseThrow();
		assertThat(new EditionIngest(this.dataSource)
			.ingest(pack, "guideline", "guideline-v1.csv",
					Files.readAllBytes(PackCopies.SOURCE.resolve("data/guideline-v1.csv")), LocalDate.parse("2026-01-01"),
					Instant.parse("2026-01-01T00:00:00Z"))
			.outcome()).isEqualTo(IngestOutcome.ACCEPTED);

		// 이제 guideline 의 매핑을 되돌리는 것(바꾸는 것)은 거부한다. 저장된 매핑은 그대로다.
		PackCopies.copyInto(this.packs, packId, text -> text);
		PackLoadRejectedException rejected = catchThrowableOfType(PackLoadRejectedException.class,
				() -> loading.load(packId));
		assertThat(rejected).isNotNull();
		assertThat(rejected.reasons()).singleElement()
			.satisfies(reason -> assertThat(reason).contains("guideline").contains("판").contains("새 식별자"));
		assertThat(mapping(packId, "guideline").requiredColumns()).containsExactly("지침번호", "제목");

		// 판이 없는 다른 입력 자료(program)의 매핑은 여전히 고칠 수 있다.
		PackCopies.copyInto(this.packs, packId,
				text -> text.contains(PROGRAM_SOURCE)
						? text.replace("tabnit:requiredColumns (", "tabnit:requiredColumns ( \"지어낸열\"")
						: guidelineChanged.apply(text));
		assertThat(loading.load(packId).outcome()).isEqualTo(PackLoadResult.Outcome.UPDATED);
		assertThat(mapping(packId, "program").requiredColumns()).contains("지어낸열");
	}

	private MappingDefinition mapping(String packId, String inputSourceId) {
		return new PackDefinitions(this.dataSource).find(packId)
			.orElseThrow()
			.mappings()
			.stream()
			.filter(m -> m.inputSourceId().equals(inputSourceId))
			.findFirst()
			.orElseThrow();
	}
}
