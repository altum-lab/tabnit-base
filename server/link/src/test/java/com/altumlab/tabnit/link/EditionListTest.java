package com.altumlab.tabnit.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

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

/**
 * D-16: Pack 에 받아들인 판의 목록 (D7 5절 "지난 판", D2 AD-383).
 *
 * <p>받은 때가 늦은 것부터, 같으면 입력 자료의 식별자 순, 그 안에서 판 번호가 큰 것부터다. 받아들인 판만 나온다.
 * 지어낸 이름의 매핑과 자료만 쓴다. 시험마다 다른 Pack 식별자를 쓴다.
 */
class EditionListTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private final EditionIngest ingest = new EditionIngest(LinkTestDatabase.dataSource());

    private final EditionRecords records = new EditionRecords(LinkTestDatabase.dataSource());

    @Test
    void 받아들인_판이_받은_때가_늦은_것부터_항목과_변동과_알림의_수와_함께_나온다() {
        PackDefinition pack = pack("el-order");
        // 같은 때에 두 입력 자료의 첫 판, 그 뒤에 a 의 둘째 판 (한 행이 바뀌고 한 행은 키가 비었다), 더 뒤에 b 의 둘째 판.
        put(pack, "a", "2026-03-01", AT, "code,title\nA-1,하나\nA-2,둘\n");
        put(pack, "b", "2026-03-01", AT, "code,title\nB-1,하나\n");
        put(pack, "a", "2026-04-01", AT.plusSeconds(10), "code,title\nA-1,하나 고침\nA-2,둘\n,빈 키\n");
        put(pack, "b", "2026-04-01", AT.plusSeconds(20), "code,title\nB-1,하나\nB-2,둘\n");
        // 거부된 투입(적용일이 앞 판보다 늦지 않다)과 내용이 같은 투입은 판이 아니다.
        assertThat(put(pack, "a", "2026-03-15", AT.plusSeconds(30), "code,title\nA-9,늦지 않은 판\n").outcome())
            .isEqualTo(IngestOutcome.REJECTED);
        assertThat(put(pack, "b", "2026-05-01", AT.plusSeconds(40), "code,title\nB-1,하나\nB-2,둘\n").outcome())
            .isEqualTo(IngestOutcome.UNCHANGED);

        EditionPage page = records.packEditions("el-order", 0, 50);

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.editions())
            .extracting(e -> e.edition().id(), EditionListEntry::itemCount, EditionListEntry::changeCount,
                    EditionListEntry::noticeCount)
            .containsExactly(tuple("el-order|b|2", 2, 1, 0), tuple("el-order|a|2", 2, 1, 1), tuple("el-order|a|1", 2, 0, 0),
                    tuple("el-order|b|1", 1, 0, 0));
        assertThat(page.editions().get(1).edition().effectiveDate()).isEqualTo(LocalDate.parse("2026-04-01"));
        assertThat(page.editions().get(1).edition().receivedAt()).isEqualTo(AT.plusSeconds(10));
    }

    @Test
    void 나눠_받은_것을_이으면_전체와_같고_전체_수는_나누기_전의_수다() {
        PackDefinition pack = pack("el-pages");
        for (int i = 1; i <= 7; i++) {
            put(pack, "a", LocalDate.parse("2026-03-01").plusDays(i).toString(), AT.plusSeconds(i),
                    "code,title\nA-1,제목 " + i + "\n");
        }

        List<String> all = records.packEditions("el-pages", 0, 50).editions().stream().map(e -> e.edition().id()).toList();
        EditionPage first = records.packEditions("el-pages", 0, 3);
        EditionPage second = records.packEditions("el-pages", 3, 3);
        EditionPage third = records.packEditions("el-pages", 6, 3);
        EditionPage beyond = records.packEditions("el-pages", 9, 3);

        assertThat(all).hasSize(7).startsWith("el-pages|a|7").endsWith("el-pages|a|1");
        assertThat(List.of(first, second, third, beyond)).allSatisfy(page -> assertThat(page.total()).isEqualTo(7));
        assertThat(first.editions()).hasSize(3);
        assertThat(third.editions()).hasSize(1);
        assertThat(beyond.editions()).isEmpty();
        assertThat(java.util.stream.Stream.of(first, second, third)
            .flatMap(page -> page.editions().stream())
            .map(e -> e.edition().id())
            .toList()).isEqualTo(all);
        // 두 번 읽어도 같다.
        assertThat(records.packEditions("el-pages", 3, 3)).isEqualTo(second);
    }

    @Test
    void 판이_없는_Pack_과_없는_Pack_은_빈_목록이고_다른_Pack_의_판은_섞이지_않는다() {
        put(pack("el-one"), "a", "2026-03-01", AT, "code,title\nA-1,하나\n");
        put(pack("el-two"), "a", "2026-03-01", AT, "code,title\nA-1,하나\n");

        assertThat(records.packEditions("el-none", 0, 10)).isEqualTo(new EditionPage(List.of(), 0));
        assertThat(records.packEditions("el-one", 0, 10).editions()).extracting(e -> e.edition().id())
            .containsExactly("el-one|a|1");
    }

    @Test
    void 건너뛸_수가_음수이거나_받을_수가_1보다_작으면_받지_않는다() {
        assertThatThrownBy(() -> records.packEditions("el-args", -1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> records.packEditions("el-args", 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> records.packEditions(null, 0, 10)).isInstanceOf(NullPointerException.class);
    }

    private IngestResult put(PackDefinition pack, String source, String effectiveDate, Instant receivedAt, String csv) {
        return ingest.ingest(pack, source, source + ".csv", csv.getBytes(StandardCharsets.UTF_8),
                LocalDate.parse(effectiveDate), receivedAt);
    }

    /** 입력 자료 둘(a, b). 행 하나가 Base 하나이고 제목을 적는다. */
    private static PackDefinition pack(String packId) {
        MappedEntityRef base = new MappedEntityRef("Base", "code");
        List<MappedEntity> entities = List.of(new MappedEntity("Base", "code",
                List.of(new MappedAttribute("title", "title", null, null, null, null, null, null))));
        return new PackDefinition(packId, "이름표", 1, AT, List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null)),
                List.of(), List.of(new AttributeDefinition("title", "제목", "Base", ValueFormat.STRING)), List.of(),
                List.of(new MappingDefinition("a", MappingFileFormat.CSV, null, null, List.of(), List.of("code"), base,
                        entities, List.of()),
                        new MappingDefinition("b", MappingFileFormat.CSV, null, null, List.of(), List.of("code"), base,
                                entities, List.of())));
    }
}
