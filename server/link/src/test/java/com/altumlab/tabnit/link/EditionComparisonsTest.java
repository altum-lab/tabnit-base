package com.altumlab.tabnit.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.MappedAttribute;
import com.altumlab.tabnit.pack.MappedEntity;
import com.altumlab.tabnit.pack.MappedEntityRef;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.MappingFileFormat;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.ValueFormat;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * C-16 수용 기준 (link 쪽): 같은 입력 자료의 두 판을 고르면 추가, 변경, 삭제와 값의 앞뒤가 나온다. 쓰지 않는다.
 * 바로 앞 판과의 비교는 올릴 때 저장한 것과 같다 (D2 AD-418).
 *
 * <p>지어낸 이름의 매핑과 자료로 판을 넣은 뒤 본다. 시험마다 다른 Pack 식별자를 쓴다.
 */
class EditionComparisonsTest {

    private static final Instant AT = Instant.parse("2026-03-02T01:00:00Z");

    private static final LocalDate D1 = LocalDate.parse("2026-03-01");

    private final EditionIngest ingest = new EditionIngest(LinkTestDatabase.dataSource());

    private final EditionRecords records = new EditionRecords(LinkTestDatabase.dataSource());

    private final EditionComparisons comparisons = new EditionComparisons(LinkTestDatabase.dataSource());

    private final JdbcTemplate jdbc = new JdbcTemplate(LinkTestDatabase.dataSource());

    @Test
    void 바로_앞_판과의_비교는_올릴_때_저장한_변동과_같다() {
        String p = "cmp-adjacent";
        PackDefinition pack = pack(p, "src");
        put(pack, "src", 0, "code,title\nB-1,하나\nB-2,둘\nB-3,셋\n");
        put(pack, "src", 1, "code,title\nB-1,하나\nB-2,고침\nB-4,넷\n");
        put(pack, "src", 2, "code,title\nB-1,또 고침\nB-2,고침\nB-4,넷\nB-5,다섯\n");

        for (int no = 2; no <= 3; no++) {
            EditionComparison compared = comparisons.compare(p + "|src|" + (no - 1), p + "|src|" + no);
            List<ChangeEvent> stored = records.changeEvents(p + "|src|" + no);

            // 항목마다 종류, 주 개체, 변동 대상, 바뀐 속성과 값의 앞뒤가 저장된 변동과 같다. 차례도 같다.
            assertThat(compared.changes())
                .extracting(ItemChange::itemKey, ItemChange::kind, ItemChange::primaryEntityId,
                        ItemChange::targetEntityIds, ItemChange::changedAttributes, ItemChange::changedRelationTypes,
                        ItemChange::changedValues)
                .as(no + "번째 판")
                .containsExactlyElementsOf(stored.stream()
                    .map(event -> tuple(event.itemKey(), event.kind(), event.primaryEntityId(), event.targetEntityIds(),
                            event.changedAttributes(), event.changedRelationTypes(), event.changedValues()))
                    .toList());
            assertThat(compared.changes()).isNotEmpty();
            assertThat(compared.from().editionNo()).isEqualTo(no - 1);
            assertThat(compared.to().editionNo()).isEqualTo(no);
        }
    }

    @Test
    void 이웃하지_않은_두_판은_사이의_판을_건너_추가와_변경과_삭제와_값의_앞뒤를_낸다() {
        String p = "cmp-far";
        PackDefinition pack = pack(p, "src");
        put(pack, "src", 0, "code,title\nB-1,하나\nB-2,둘\nB-3,셋\nB-9,그대로\n");
        put(pack, "src", 1, "code,title\nB-1,하나\nB-2,둘째 고침\nB-3,셋\nB-7,잠깐\nB-9,그대로\n");
        put(pack, "src", 2, "code,title\nB-1,하나 고침\nB-2,둘째 고침\nB-4,넷\nB-9,그대로\n");

        EditionComparison compared = comparisons.compare(p + "|src|1", p + "|src|3");

        // 항목의 키 순이다. 그대로인 B-9 와, 둘째 판에만 있던 B-7 은 없다.
        assertThat(compared.changes()).extracting(ItemChange::itemKey, ItemChange::kind)
            .containsExactly(tuple("B-1", ChangeKind.CHANGED), tuple("B-2", ChangeKind.CHANGED),
                    tuple("B-3", ChangeKind.REMOVED), tuple("B-4", ChangeKind.ADDED));
        assertThat(compared.changes().get(0).changedValues())
            .containsExactly(new ValueChange(p + "|Base|B-1", "title", "하나", "하나 고침"));
        assertThat(compared.changes().get(1).changedValues())
            .containsExactly(new ValueChange(p + "|Base|B-2", "title", "둘", "둘째 고침"));
        assertThat(compared.changes().get(2).changedValues())
            .containsExactly(new ValueChange(p + "|Base|B-3", "title", "셋", null));
        assertThat(compared.changes().get(3).changedValues())
            .containsExactly(new ValueChange(p + "|Base|B-4", "title", null, "넷"));
        assertThat(compared.changes().get(0).changedAttributes()).containsExactly("title");
        assertThat(compared.changes().get(2).targetEntityIds()).containsExactly(p + "|Base|B-3");
        // 두 번 견줘도 같다.
        assertThat(comparisons.compare(p + "|src|1", p + "|src|3")).isEqualTo(compared);
    }

    @Test
    void 뒤의_판을_앞에_적으면_적힌_방향_그대로_견준다() {
        String p = "cmp-reverse";
        PackDefinition pack = pack(p, "src");
        put(pack, "src", 0, "code,title\nB-1,하나\nB-2,둘\n");
        put(pack, "src", 1, "code,title\nB-1,고침\nB-3,셋\n");

        EditionComparison backward = comparisons.compare(p + "|src|2", p + "|src|1");

        assertThat(backward.from().editionNo()).isEqualTo(2);
        assertThat(backward.to().editionNo()).isEqualTo(1);
        // 앞으로 견준 것의 거꾸로다: 추가는 삭제로, 삭제는 추가로, 값의 앞뒤가 바뀐다.
        assertThat(backward.changes()).extracting(ItemChange::itemKey, ItemChange::kind)
            .containsExactly(tuple("B-1", ChangeKind.CHANGED), tuple("B-2", ChangeKind.ADDED),
                    tuple("B-3", ChangeKind.REMOVED));
        assertThat(backward.changes().get(0).changedValues())
            .containsExactly(new ValueChange(p + "|Base|B-1", "title", "고침", "하나"));
        assertThat(backward.changes().get(1).changedValues())
            .containsExactly(new ValueChange(p + "|Base|B-2", "title", null, "둘"));
    }

    @Test
    void 같은_판_둘은_바뀐_것이_없다() {
        String p = "cmp-same";
        PackDefinition pack = pack(p, "src");
        put(pack, "src", 0, "code,title\nB-1,하나\n");
        put(pack, "src", 1, "code,title\nB-1,고침\n");

        EditionComparison same = comparisons.compare(p + "|src|2", p + "|src|2");

        assertThat(same.changes()).isEmpty();
        assertThat(same.from()).isEqualTo(same.to());
    }

    @Test
    void 되돌아온_값은_변경으로_나오고_앞뒤의_값이_같다() {
        String p = "cmp-back";
        PackDefinition pack = pack(p, "src");
        put(pack, "src", 0, "code,title\nB-1,가\nB-2,그대로\n");
        put(pack, "src", 1, "code,title\nB-1,나\nB-2,그대로\n");
        put(pack, "src", 2, "code,title\nB-1,가\nB-2,그대로\n");

        EditionComparison compared = comparisons.compare(p + "|src|1", p + "|src|3");

        // 셋째 판의 "가"는 둘째 판에서 끊긴 뒤 새로 만든 값이다: 첫 판의 것과 다른 것이다 (유효기간이 다르다, D2 AD-418).
        assertThat(compared.changes()).extracting(ItemChange::itemKey, ItemChange::kind)
            .containsExactly(tuple("B-1", ChangeKind.CHANGED));
        assertThat(compared.changes().get(0).changedValues())
            .containsExactly(new ValueChange(p + "|Base|B-1", "title", "가", "가"));
    }

    @Test
    void 견주는_일은_아무것도_쓰지_않는다() {
        String p = "cmp-readonly";
        PackDefinition pack = pack(p, "src");
        put(pack, "src", 0, "code,title\nB-1,하나\n");
        put(pack, "src", 1, "code,title\nB-1,고침\nB-2,둘\n");
        put(pack, "src", 2, "code,title\nB-2,둘\n");
        List<Integer> before = counts(p);

        comparisons.compare(p + "|src|1", p + "|src|3");
        comparisons.compare(p + "|src|3", p + "|src|1");

        assertThat(counts(p)).isEqualTo(before);
    }

    @Test
    void 없는_판과_다른_입력_자료의_판과_다른_Pack_의_판은_거부한다() {
        String p = "cmp-bad";
        PackDefinition pack = pack(p, "alpha", "zeta");
        put(pack, "alpha", 0, "code,title\nB-1,하나\n");
        put(pack, "zeta", 0, "code,title\nZ-1,하나\n");
        PackDefinition other = pack("cmp-bad-other", "alpha");
        put(other, "alpha", 0, "code,title\nB-1,하나\n");

        assertThatThrownBy(() -> comparisons.compare(p + "|alpha|1", p + "|alpha|9"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(p + "|alpha|9");
        assertThatThrownBy(() -> comparisons.compare(p + "|alpha|9", p + "|alpha|1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(p + "|alpha|9");
        assertThatThrownBy(() -> comparisons.compare(p + "|alpha|1", p + "|zeta|1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("입력 자료");
        assertThatThrownBy(() -> comparisons.compare(p + "|alpha|1", "cmp-bad-other|alpha|1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("입력 자료");
        assertThatThrownBy(() -> comparisons.compare(null, p + "|alpha|1")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 판이_많고_항목이_많아도_건너_견준_것이_맞다() {
        String p = "cmp-many";
        PackDefinition pack = pack(p, "src");
        // 판 여섯, 항목 300. 판마다 항목 하나씩의 값이 바뀐다 (판 번호와 같은 번호의 항목).
        for (int edition = 0; edition < 6; edition++) {
            StringBuilder csv = new StringBuilder("code,title\n");
            for (int i = 0; i < 300; i++) {
                csv.append(String.format("K-%03d", i)).append(",값 ").append(i <= edition && i > 0 ? "고침" : "처음").append('\n');
            }
            put(pack, "src", edition, csv.toString());
        }

        // 첫 판과 여섯째 판: 둘째 판부터 여섯째 판까지 하나씩 바뀐 다섯 항목.
        assertThat(comparisons.compare(p + "|src|1", p + "|src|6").changes()).extracting(ItemChange::itemKey)
            .containsExactly("K-001", "K-002", "K-003", "K-004", "K-005");
        assertThat(comparisons.compare(p + "|src|3", p + "|src|5").changes()).extracting(ItemChange::itemKey)
            .containsExactly("K-003", "K-004");
    }

    private List<Integer> counts(String packId) {
        return List.of(
                jdbc.queryForObject("select count(*) from edition where pack_id = ?", Integer.class, packId),
                jdbc.queryForObject("select count(*) from edition_item i join edition e on e.id = i.edition_id where e.pack_id = ?",
                        Integer.class, packId),
                jdbc.queryForObject(
                        "select count(*) from change_event c join edition e on e.id = c.edition_id where e.pack_id = ?",
                        Integer.class, packId));
    }

    /** 판을 하나 넣는다. 적용일과 받은 때는 차례대로 뒤로 간다. */
    private void put(PackDefinition pack, String source, int order, String csv) {
        IngestResult result = ingest.ingest(pack, source, "f.csv", csv.getBytes(StandardCharsets.UTF_8),
                D1.plusMonths(order), AT.plusSeconds(order * 10L));
        assertThat(result.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
    }

    /** 유형 Base 의 개체에 제목을 넣는 매핑을 입력 자료마다 하나씩 가진 Pack. 열: code, title. */
    private static PackDefinition pack(String packId, String... sources) {
        MappedEntityRef base = new MappedEntityRef("Base", "code");
        List<MappingDefinition> mappings = java.util.Arrays.stream(sources)
            .map(source -> new MappingDefinition(source, MappingFileFormat.CSV, null, null, List.of(), List.of("code"), base,
                    List.of(new MappedEntity("Base", "code",
                            List.of(new MappedAttribute("title", "title", null, null, null, null, null, null)))),
                    List.of()))
            .toList();
        return new PackDefinition(packId, "이름표", 3, AT, List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null)),
                List.of(), List.of(new AttributeDefinition("title", "제목", "Base", ValueFormat.STRING)), List.of(), mappings);
    }
}
