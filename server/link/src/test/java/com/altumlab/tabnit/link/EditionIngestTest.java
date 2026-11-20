package com.altumlab.tabnit.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altumlab.tabnit.core.AttributeValueQuery;
import com.altumlab.tabnit.core.EntityNotFoundException;
import com.altumlab.tabnit.core.RelationQuery;
import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.MappedAttribute;
import com.altumlab.tabnit.pack.MappedEntity;
import com.altumlab.tabnit.pack.MappedEntityRef;
import com.altumlab.tabnit.pack.MappedRelation;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.MappingFileFormat;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.pack.PackImpactDirection;
import com.altumlab.tabnit.pack.PackLoader;
import com.altumlab.tabnit.pack.RelationTypeDefinition;
import com.altumlab.tabnit.pack.ValueFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * C-04 수용 기준: 받은 파일 원본과 처리 결과가 남는다.
 *
 * <p>일반 시험을 먼저 둔다 (지어낸 이름의 매핑과 자료). 그 위에 저장소에 들어 있는 Pack 의 첫 판을 넣어 본다.
 * 시험마다 다른 Pack 식별자를 써서 서로 섞이지 않게 한다. 받은 때는 시험이 넘긴다.
 */
class EditionIngestTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-03-02T01:00:00Z");

    private static final LocalDate EFFECTIVE = LocalDate.parse("2026-03-01");

    private static final MappedEntityRef BASE_BY_CODE = new MappedEntityRef("Base", "code");

    private static final String CSV = "code,title,other\nB-2,둘째,O-1\nB-1,첫째,O-1\n";

    private final EditionIngest ingest = new EditionIngest(LinkTestDatabase.dataSource());

    private final EditionRecords records = new EditionRecords(LinkTestDatabase.dataSource());

    private final RelationQuery relations = new RelationQuery(LinkTestDatabase.dataSource());

    private final AttributeValueQuery values = new AttributeValueQuery(LinkTestDatabase.dataSource());

    // ── 받아들인 투입: 원본과 처리 결과가 남는다 ───────────────────────────

    @Test
    void 받아들인_투입은_판과_원본과_항목과_투입_기록을_남긴다() {
        PackDefinition pack = pack("i-basic", full("src"));
        byte[] content = bytes(CSV);

        IngestResult result = ingest.ingest(pack, "src", "첫 판.csv", content, EFFECTIVE, RECEIVED_AT);

        assertThat(result.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(result.editionId()).isEqualTo("i-basic|src|1");
        assertThat(result.reasonCode()).isNull();
        // 판.
        Edition edition = records.findEdition("i-basic|src|1").orElseThrow();
        assertThat(edition.packId()).isEqualTo("i-basic");
        assertThat(edition.inputSourceId()).isEqualTo("src");
        assertThat(edition.editionNo()).isEqualTo(1);
        assertThat(edition.effectiveDate()).isEqualTo(EFFECTIVE);
        assertThat(edition.contentHash()).isEqualTo(sha256(content)).hasSize(64).matches("[0-9a-f]+");
        assertThat(edition.fileName()).isEqualTo("첫 판.csv");
        assertThat(edition.receivedAt()).isEqualTo(RECEIVED_AT);
        // 원본: 받은 바이트 그대로.
        assertThat(records.originalContent("i-basic|src|1")).contains(content);
        // 항목과 만든 것: 돌려준 결과와 저장된 것이 같다. 항목의 키 순이다.
        List<IngestedItem> items = records.items("i-basic|src|1");
        assertThat(items).isEqualTo(result.mapping().items());
        assertThat(items).extracting(IngestedItem::itemKey).containsExactly("B-1", "B-2");
        assertThat(items).extracting(IngestedItem::rowNo).containsExactly(3, 2);
        assertThat(items.get(0).outputs()).containsExactly(new ItemOutput(ItemOutput.Kind.ENTITY, "i-basic|Base|B-1"),
                new ItemOutput(ItemOutput.Kind.ENTITY, "i-basic|Other|O-1"),
                new ItemOutput(ItemOutput.Kind.RELATION, "i-basic|Base|B-1|Other|O-1|links|src|1|2026-03-01"),
                new ItemOutput(ItemOutput.Kind.ATTRIBUTE_VALUE, "i-basic|Base|B-1|title|2026-03-01"));
        assertThat(records.notices("i-basic|src|1")).isEmpty();
        // 투입 기록.
        assertThat(records.ingestRecords("i-basic", "src")).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
            assertThat(r.editionId()).isEqualTo("i-basic|src|1");
            assertThat(r.effectiveDate()).isEqualTo(EFFECTIVE);
            assertThat(r.contentHash()).isEqualTo(edition.contentHash());
            assertThat(r.fileName()).isEqualTo("첫 판.csv");
            assertThat(r.receivedAt()).isEqualTo(RECEIVED_AT);
            assertThat(r.reasonCode()).isNull();
            assertThat(r.reasonDetail()).isNull();
        });
        // core 에도 들어갔고, 출처가 이 판이다.
        assertThat(relations.relationsAsOf("i-basic|Base|B-1", EFFECTIVE)).singleElement()
            .satisfies(r -> assertThat(r.provenance().editionNo()).isEqualTo(1));
    }

    @Test
    void 내용의_해시는_받은_바이트의_SHA_256_이다() {
        PackDefinition pack = pack("i-hash", full("src"));

        ingest.ingest(pack, "src", "a.csv", "abc".getBytes(StandardCharsets.UTF_8), EFFECTIVE, RECEIVED_AT);
        ingest.ingest(pack, "src", "b.csv", bytes("code,title,other\n"), EFFECTIVE, RECEIVED_AT);

        // 널리 알려진 값: SHA-256("abc"). 거부된 투입에도 해시는 남는다.
        assertThat(records.ingestRecords("i-hash", "src").get(0).contentHash())
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(records.findEdition("i-hash|src|1").orElseThrow().contentHash())
            .isEqualTo(sha256(bytes("code,title,other\n")));
    }

    @Test
    void 알림은_정해진_차례로_번호가_매겨져_남는다() {
        PackDefinition pack = pack("i-notice", full("src"));

        IngestResult result = ingest.ingest(pack, "src", "f.csv",
                bytes("code,title,other\nB-1,첫째,O-1\n,빈 키,O-1\nB-3\nB-4,넷째,O-1\nB-4,다시,O-2\n"), EFFECTIVE, RECEIVED_AT);

        List<IngestNotice> notices = records.notices("i-notice|src|1");
        assertThat(notices).isEqualTo(result.mapping().notices());
        assertThat(notices).extracting(IngestNotice::rowNo, IngestNotice::code)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(3, "BLANK_KEY"),
                    org.assertj.core.groups.Tuple.tuple(4, "CELL_COUNT_MISMATCH"),
                    org.assertj.core.groups.Tuple.tuple(5, "DUPLICATE_ITEM_KEY"),
                    org.assertj.core.groups.Tuple.tuple(6, "DUPLICATE_ITEM_KEY"));
        // 건너뛴 행은 항목이 되지 않는다.
        assertThat(records.items("i-notice|src|1")).extracting(IngestedItem::itemKey).containsExactly("B-1");
    }

    @Test
    void 열_이름만_있는_파일도_판이_된다() {
        PackDefinition pack = pack("i-empty", full("src"));

        IngestResult result = ingest.ingest(pack, "src", "f.csv", bytes("code,title,other\n"), EFFECTIVE, RECEIVED_AT);

        assertThat(result.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(records.items("i-empty|src|1")).isEmpty();
        assertThat(records.originalContent("i-empty|src|1")).contains(bytes("code,title,other\n"));
    }

    @Test
    void 파일_이름은_없어도_된다() {
        PackDefinition pack = pack("i-noname", full("src"));

        ingest.ingest(pack, "src", null, bytes(CSV), EFFECTIVE, RECEIVED_AT);

        assertThat(records.findEdition("i-noname|src|1").orElseThrow().fileName()).isNull();
    }

    // ── 항목의 내용 해시 ────────────────────────────────────────────

    @Test
    void 항목의_내용_해시는_매핑이_쓰는_열의_읽은_값으로_정해진다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("count", "count", null, null, null, null, null, null),
                                new MappedAttribute("title", "title", null, null, null, null, null, null)))),
                List.of(new MappedRelation("links", BASE_BY_CODE, new MappedEntityRef("Other", "other"), null, null)));
        String base = "code,count,title,other,memo\nB-1,7,이름,O-1,메모\nB-2,8,이름,O-1,메모\n";

        Map<String, String> first = itemHashes("i-item-1", mapping, base, EFFECTIVE);
        // 행의 차례, 매핑이 쓰지 않는 열, 같은 수의 다른 표기, 적용일은 해시를 바꾸지 않는다 (D2 AD-156).
        Map<String, String> same = itemHashes("i-item-2", mapping,
                "code,count,title,other,memo\nB-2,+8,이름,O-1,다른 메모\nB-1,007,이름,O-1,\n", EFFECTIVE.plusDays(30));
        // 속성 값, 관계의 끝, 빈 칸이 된 것은 해시를 바꾼다.
        Map<String, String> changed = itemHashes("i-item-3", mapping,
                "code,count,title,other,memo\nB-1,9,이름,O-1,메모\nB-2,8,이름,O-2,메모\n", EFFECTIVE);
        Map<String, String> blanked = itemHashes("i-item-4", mapping,
                "code,count,title,other,memo\nB-1,7,,O-1,메모\nB-2,8,이름,,메모\n", EFFECTIVE);

        assertThat(first.values()).allSatisfy(hash -> assertThat(hash).hasSize(64).matches("[0-9a-f]+"));
        assertThat(first.get("B-1")).isNotEqualTo(first.get("B-2"));
        assertThat(same).isEqualTo(first);
        assertThat(changed.get("B-1")).isNotEqualTo(first.get("B-1"));
        assertThat(changed.get("B-2")).isNotEqualTo(first.get("B-2"));
        assertThat(blanked.get("B-1")).isNotEqualTo(first.get("B-1"));
        assertThat(blanked.get("B-2")).isNotEqualTo(first.get("B-2"));
    }

    @Test
    void 항목의_내용_해시는_유효기간의_칸에_적힌_날짜에도_달라진다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", "from", "to", null, null, null, null)))),
                List.of());

        Map<String, String> first = itemHashes("i-period-1", mapping, "code,title,from,to\nB-1,이름,2026-01-01,\n",
                EFFECTIVE);
        Map<String, String> ended = itemHashes("i-period-2", mapping,
                "code,title,from,to\nB-1,이름,2026-01-01,2026-06-01\n", EFFECTIVE);
        Map<String, String> moved = itemHashes("i-period-3", mapping, "code,title,from,to\nB-1,이름,2026-02-01,\n",
                EFFECTIVE);

        assertThat(ended.get("B-1")).isNotEqualTo(first.get("B-1"));
        assertThat(moved.get("B-1")).isNotEqualTo(first.get("B-1"));
    }

    @Test
    void 값_안의_줄바꿈과_줄이_나뉜_것은_다른_내용이다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", null, null, null, null, null, null)))),
                List.of());

        Map<String, String> oneLine = itemHashes("i-newline-1", mapping, "code,title\nB-1,가 나\n", EFFECTIVE);
        Map<String, String> twoLines = itemHashes("i-newline-2", mapping, "code,title\nB-1,\"가\n나\"\n", EFFECTIVE);
        Map<String, String> escaped = itemHashes("i-newline-3", mapping, "code,title\nB-1,가\\n나\n", EFFECTIVE);

        assertThat(twoLines.get("B-1")).isNotEqualTo(oneLine.get("B-1")).isNotEqualTo(escaped.get("B-1"));
    }

    // ── 긴 키 ───────────────────────────────────────────────────

    @Test
    void 이은_항목의_키가_200자를_넘는_행은_건너뛰고_가장_긴_키의_행은_저장된다() {
        MappedEntityRef other = new MappedEntityRef("Other", "k2");
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("k1", "k2"), new MappedEntityRef("Base", "k1"),
                List.of(new MappedEntity("Base", "k1",
                        List.of(new MappedAttribute("title", "title", null, null, null, null, null, null)))),
                List.of(new MappedRelation("links", new MappedEntityRef("Base", "k1"), other, null, null)));
        PackDefinition pack = pack("i-long", mapping);
        // 4바이트 글자. 되풀이가 없어 압축되지 않는다. 이은 키는 두 값과 세로 막대 하나다.
        String left = distinct(100, 11);
        String right = distinct(99, 29);
        String tooLong = distinct(100, 53);

        IngestResult result = ingest.ingest(pack, "src", "f.csv",
                bytes("k1,k2,title\n" + left + "," + right + ",이름\n" + left + "," + tooLong + ",이름\nB-3,O-3,이름\n"),
                EFFECTIVE, RECEIVED_AT);

        assertThat(result.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        List<IngestedItem> items = records.items("i-long|src|1");
        assertThat(items).extracting(IngestedItem::itemKey).containsExactly("B-3|O-3", left + "|" + right);
        assertThat(items.get(1).itemKey().codePointCount(0, items.get(1).itemKey().length())).isEqualTo(200);
        assertThat(items.get(1).outputs()).hasSize(4);
        assertThat(records.notices("i-long|src|1"))
            .containsExactly(new IngestNotice("SKIPPED_ROW", 3, "KEY_TOO_LONG", null, "201"));
    }

    @Test
    void 가장_긴_키_둘을_잇는_관계를_만든_항목도_저장된다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE, List.of(new MappedEntity("Base", "code", List.of())),
                List.of(new MappedRelation("links", BASE_BY_CODE, new MappedEntityRef("Other", "other"), null, null)));
        PackDefinition pack = pack("i-longest", mapping);
        String left = distinct(200, 11);
        String right = distinct(200, 29);

        IngestResult result = ingest.ingest(pack, "src", "f.csv", bytes("code,other\n" + left + "," + right + "\n"),
                EFFECTIVE, RECEIVED_AT);

        // 항목의 키 800바이트에 관계의 식별자 1,600바이트 남짓이 한 행에 든다. 색인의 한도에 걸리지 않는다.
        assertThat(result.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(records.items("i-longest|src|1")).singleElement()
            .satisfies(item -> assertThat(item.outputs()).extracting(ItemOutput::kind)
                .containsExactly(ItemOutput.Kind.ENTITY, ItemOutput.Kind.ENTITY, ItemOutput.Kind.RELATION));
    }

    // ── 거부: 아무것도 만들지 않고 투입 기록만 남는다 ───────────────────────

    @Test
    void 파일이_틀려_거부한_투입은_판을_만들지_않고_이유와_함께_기록된다() {
        PackDefinition pack = pack("i-reject", full("src"));

        IngestResult missing = ingest.ingest(pack, "src", "a.csv", bytes("code,title\nB-1,첫째\n"), EFFECTIVE, RECEIVED_AT);
        IngestResult notUtf8 = ingest.ingest(pack, "src", "b.csv", new byte[] { (byte) 0xC0, (byte) 0xAF }, EFFECTIVE,
                RECEIVED_AT.plusSeconds(1));

        assertThat(missing.outcome()).isEqualTo(IngestOutcome.REJECTED);
        assertThat(missing.editionId()).isNull();
        assertThat(missing.mapping()).isNull();
        assertThat(missing.reasonCode()).isEqualTo("MISSING_COLUMN");
        assertThat(missing.reasonDetail()).isEqualTo("other");
        assertThat(notUtf8.reasonCode()).isEqualTo("NOT_UTF8");
        // 판도, 원본도, core 의 개체도 없다.
        assertThat(records.findEdition("i-reject|src|1")).isEmpty();
        assertThat(records.originalContent("i-reject|src|1")).isEmpty();
        assertThatThrownBy(() -> values.valuesAsOf("i-reject|Base|B-1", EFFECTIVE))
            .isInstanceOf(EntityNotFoundException.class);
        // 투입 기록은 받은 때 순으로 남는다.
        assertThat(records.ingestRecords("i-reject", "src")).extracting(IngestRecord::outcome, IngestRecord::reasonCode,
                IngestRecord::editionId, IngestRecord::fileName)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(IngestOutcome.REJECTED, "MISSING_COLUMN", null, "a.csv"),
                    org.assertj.core.groups.Tuple.tuple(IngestOutcome.REJECTED, "NOT_UTF8", null, "b.csv"));
        // 거부된 뒤에 바른 파일을 넣으면 첫 판이 된다. 판 번호에 빈틈이 없다.
        assertThat(ingest.ingest(pack, "src", "c.csv", bytes(CSV), EFFECTIVE, RECEIVED_AT.plusSeconds(2)).editionId())
            .isEqualTo("i-reject|src|1");
    }

    @Test
    void 크기_한도를_넘는_파일은_거부하고_한도와_같은_크기는_받는다() {
        PackDefinition pack = pack("i-size", full("src"));
        byte[] content = bytes(CSV);
        EditionIngest tight = new EditionIngest(LinkTestDatabase.dataSource(), content.length - 1);
        EditionIngest exact = new EditionIngest(LinkTestDatabase.dataSource(), content.length);

        IngestResult rejected = tight.ingest(pack, "src", "f.csv", content, EFFECTIVE, RECEIVED_AT);
        IngestResult accepted = exact.ingest(pack, "src", "f.csv", content, EFFECTIVE, RECEIVED_AT);

        assertThat(rejected.outcome()).isEqualTo(IngestOutcome.REJECTED);
        assertThat(rejected.reasonCode()).isEqualTo("FILE_TOO_LARGE");
        assertThat(rejected.reasonDetail()).isEqualTo(Integer.toString(content.length));
        assertThat(accepted.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(EditionIngest.DEFAULT_MAX_FILE_BYTES).isEqualTo(20L * 1024 * 1024);
    }

    @Test
    void 매핑이_없는_입력_자료는_받지_않고_기록도_남기지_않는다() {
        PackDefinition pack = pack("i-unknown", full("src"));

        assertThatThrownBy(() -> ingest.ingest(pack, "nowhere", "f.csv", bytes(CSV), EFFECTIVE, RECEIVED_AT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nowhere");

        assertThat(records.ingestRecords("i-unknown", "nowhere")).isEmpty();
    }

    @Test
    void 저장할_수_없는_적용일과_받은_때와_파일_이름은_받지_않는다() {
        PackDefinition pack = pack("i-args", full("src"));

        assertThatThrownBy(() -> ingest.ingest(pack, "src", "f.csv", bytes(CSV), LocalDate.of(10000, 1, 1), RECEIVED_AT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("적용일");
        assertThatThrownBy(() -> ingest.ingest(pack, "src", "f.csv", bytes(CSV), LocalDate.MAX, RECEIVED_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingest.ingest(pack, "src", "f.csv", bytes(CSV), LocalDate.of(0, 12, 31), RECEIVED_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingest.ingest(pack, "src", "f.csv", bytes(CSV), EFFECTIVE, Instant.MIN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("받은 때");
        assertThatThrownBy(() -> ingest.ingest(pack, "src", "f.csv", bytes(CSV), EFFECTIVE, Instant.MAX))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingest.ingest(pack, "src", "f\u0000.csv", bytes(CSV), EFFECTIVE, RECEIVED_AT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("파일의 이름");

        // 아무것도 남지 않는다. 경계의 날짜는 받는다.
        assertThat(records.ingestRecords("i-args", "src")).isEmpty();
        assertThat(ingest.ingest(pack, "src", "f.csv", bytes(CSV), LocalDate.of(9999, 12, 31), RECEIVED_AT).outcome())
            .isEqualTo(IngestOutcome.ACCEPTED);
    }

    // ── 같은 입력 자료의 다음 투입 ─────────────────────────────────────

    @Test
    void 내용이_앞_판과_같으면_적용일이_달라도_판을_만들지_않는다() {
        PackDefinition pack = pack("i-same", full("src"));
        ingest.ingest(pack, "src", "f.csv", bytes(CSV), EFFECTIVE, RECEIVED_AT);

        IngestResult again = ingest.ingest(pack, "src", "g.csv", bytes(CSV), EFFECTIVE.plusDays(10),
                RECEIVED_AT.plusSeconds(60));

        assertThat(again.outcome()).isEqualTo(IngestOutcome.UNCHANGED);
        // 내용이 같았던 앞 판을 가리킨다.
        assertThat(again.editionId()).isEqualTo("i-same|src|1");
        assertThat(records.findEdition("i-same|src|2")).isEmpty();
        assertThat(records.ingestRecords("i-same", "src")).extracting(IngestRecord::outcome, IngestRecord::editionId)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(IngestOutcome.ACCEPTED, "i-same|src|1"),
                    org.assertj.core.groups.Tuple.tuple(IngestOutcome.UNCHANGED, "i-same|src|1"));
        // 한 번 넣은 것과 저장된 것이 같다.
        assertThat(relations.relationsAsOf("i-same|Other|O-1", EFFECTIVE.plusDays(20))).hasSize(2);
    }

    @Test
    void 적용일이_앞_판보다_뒤가_아니면_거부한다() {
        PackDefinition pack = pack("i-date", full("src"));
        ingest.ingest(pack, "src", "f.csv", bytes(CSV), EFFECTIVE, RECEIVED_AT);
        byte[] next = bytes("code,title,other\nB-1,바뀐 이름,O-1\n");

        IngestResult sameDay = ingest.ingest(pack, "src", "g.csv", next, EFFECTIVE, RECEIVED_AT.plusSeconds(1));
        IngestResult earlier = ingest.ingest(pack, "src", "g.csv", next, EFFECTIVE.minusDays(1),
                RECEIVED_AT.plusSeconds(2));

        assertThat(List.of(sameDay, earlier)).allSatisfy(result -> {
            assertThat(result.outcome()).isEqualTo(IngestOutcome.REJECTED);
            assertThat(result.reasonCode()).isEqualTo("EFFECTIVE_DATE_NOT_AFTER_PREVIOUS");
            assertThat(result.reasonDetail()).isEqualTo("2026-03-01");
        });
        assertThat(records.findEdition("i-date|src|2")).isEmpty();
    }

    @Test
    void 내용이_다른_다음_파일은_둘째_판이_된다() {
        PackDefinition pack = pack("i-second", full("src"));
        ingest.ingest(pack, "src", "f.csv", bytes(CSV), EFFECTIVE, RECEIVED_AT);
        byte[] next = bytes("code,title,other\nB-9,새 개체,O-1\n");

        IngestResult second = ingest.ingest(pack, "src", "g.csv", next, EFFECTIVE.plusDays(1),
                RECEIVED_AT.plusSeconds(1));

        // 판 비교는 EditionDiffTest 가 본다. 여기서는 판과 원본이 남는 것만 본다.
        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(second.editionId()).isEqualTo("i-second|src|2");
        assertThat(records.findEdition("i-second|src|2").orElseThrow().editionNo()).isEqualTo(2);
        assertThat(records.originalContent("i-second|src|2")).contains(next);
        assertThat(records.originalContent("i-second|src|1")).contains(bytes(CSV));
    }

    @Test
    void 판_번호는_입력_자료마다_따로_매기고_판이_있는_입력_자료를_답한다() {
        PackDefinition pack = pack("i-sources", full("zeta"), shared("alpha"), full("unused"));

        ingest.ingest(pack, "zeta", "z.csv", bytes(CSV), EFFECTIVE, RECEIVED_AT);
        ingest.ingest(pack, "alpha", "a.csv", bytes("line,other,otherName\n1,O-1,상대\n"), EFFECTIVE, RECEIVED_AT);
        // 거부된 투입만 있는 입력 자료는 판이 있는 것이 아니다.
        ingest.ingest(pack, "unused", "u.csv", bytes("x\n"), EFFECTIVE, RECEIVED_AT);

        assertThat(records.findEdition("i-sources|zeta|1")).isPresent();
        assertThat(records.findEdition("i-sources|alpha|1")).isPresent();
        assertThat(records.inputSourcesWithEditions("i-sources")).containsExactly("alpha", "zeta");
        assertThat(records.inputSourcesWithEditions("i-sources-none")).isEmpty();
    }

    // ── 저장 실패: 전부 되돌리고 기록은 남긴다 ─────────────────────────────

    @Test
    void 저장소가_받지_않으면_판도_원본도_남지_않고_투입_기록에는_남는다() {
        // 두 입력 자료가 같은 개체의 같은 속성을 겹치는 기간으로 넣는다. 둘째의 저장을 core 가 거부한다.
        PackDefinition pack = pack("i-fail", shared("one"), shared("two"));
        ingest.ingest(pack, "one", "1.csv", bytes("line,other,otherName\n1,O-1,하나\n"), EFFECTIVE, RECEIVED_AT);

        assertThatThrownBy(() -> ingest.ingest(pack, "two", "2.csv",
                bytes("line,other,otherName\n1,O-0,새 개체\n2,O-1,둘\n"), EFFECTIVE.plusDays(5), RECEIVED_AT.plusSeconds(1)))
            .isInstanceOf(IngestFailedException.class)
            .hasMessageContaining("two");

        assertThat(records.findEdition("i-fail|two|1")).isEmpty();
        assertThat(records.originalContent("i-fail|two|1")).isEmpty();
        assertThat(records.inputSourcesWithEditions("i-fail")).containsExactly("one");
        // 먼저 저장된 O-0 도 남지 않는다.
        assertThatThrownBy(() -> values.valuesAsOf("i-fail|Other|O-0", EFFECTIVE.plusDays(5)))
            .isInstanceOf(EntityNotFoundException.class);
        assertThat(records.ingestRecords("i-fail", "two")).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isEqualTo(IngestOutcome.REJECTED);
            assertThat(r.reasonCode()).isEqualTo("STORAGE_FAILURE");
            assertThat(r.editionId()).isNull();
        });
    }

    // ── 같은 입력에는 같은 결과 ──────────────────────────────────────

    @Test
    void 같은_자료를_다른_Pack_에_넣으면_판_식별자를_뺀_저장_내용이_같다() {
        String csv = "code,title,other\nB-3,셋째,O-2\nbad\nB-1,첫째,O-1\n,빈 키,O-1\n";
        ingest.ingest(pack("i-twice-a", full("src")), "src", "f.csv", bytes(csv), EFFECTIVE, RECEIVED_AT);
        ingest.ingest(pack("i-twice-b", full("src")), "src", "f.csv", bytes(csv), EFFECTIVE, RECEIVED_AT);

        assertThat(records.notices("i-twice-a|src|1")).isEqualTo(records.notices("i-twice-b|src|1"));
        assertThat(records.items("i-twice-a|src|1")).extracting(IngestedItem::itemKey, IngestedItem::rowNo)
            .isEqualTo(records.items("i-twice-b|src|1")
                .stream()
                .map(i -> org.assertj.core.groups.Tuple.tuple(i.itemKey(), i.rowNo()))
                .toList());
        assertThat(records.findEdition("i-twice-a|src|1").orElseThrow().contentHash())
            .isEqualTo(records.findEdition("i-twice-b|src|1").orElseThrow().contentHash());
    }

    @Test
    void 행이_많아도_모두_남는다() {
        PackDefinition pack = pack("i-many", full("src"));
        StringBuilder csv = new StringBuilder("code,title,other\n");
        int count = 1500;
        for (int i = 0; i < count; i++) {
            int n = (i * 7) % count;
            csv.append("B-").append(10000 + n).append(",이름 ").append(n).append(",O-").append(n % 10).append('\n');
        }

        ingest.ingest(pack, "src", "f.csv", bytes(csv.toString()), EFFECTIVE, RECEIVED_AT);

        List<IngestedItem> items = records.items("i-many|src|1");
        assertThat(items).hasSize(count);
        assertThat(items.get(0).itemKey()).isEqualTo("B-10000");
        assertThat(items.get(count - 1).itemKey()).isEqualTo("B-11499");
        assertThat(items).allSatisfy(item -> assertThat(item.outputs()).hasSize(4));
    }

    // ── 저장소에 들어 있는 Pack ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("repositoryPacks")
    void 저장소의_Pack의_첫_판이_원본과_함께_남는다(Path dir) throws IOException {
        String packId = dir.getFileName().toString();
        // Pack 식별자를 바꿀 수 없으므로 다른 시험과 섞이지 않게 빈 데이터베이스를 따로 쓴다.
        javax.sql.DataSource fresh = LinkTestDatabase.fresh();
        EditionIngest ingest = new EditionIngest(fresh);
        EditionRecords records = new EditionRecords(fresh);
        new PackLoader(fresh).load(dir, RECEIVED_AT);
        PackDefinition pack = new PackDefinitions(fresh).find(packId).orElseThrow();

        // 시나리오에서 입력 자료마다 처음 나오는 판을 차례대로 넣는다. 시험에 Pack 의 이름과 수를 적지 않는다.
        Map<String, String[]> firstEditions = firstEditions(dir);
        assertThat(firstEditions).isNotEmpty();
        for (Map.Entry<String, String[]> step : firstEditions.entrySet()) {
            byte[] content = Files.readAllBytes(dir.resolve(step.getValue()[0]));
            IngestResult result = ingest.ingest(pack, step.getKey(), step.getValue()[0], content,
                    LocalDate.parse(step.getValue()[1]), RECEIVED_AT);

            assertThat(result.outcome()).as(step.getKey()).isEqualTo(IngestOutcome.ACCEPTED);
            assertThat(records.originalContent(result.editionId())).contains(content);
            assertThat(records.items(result.editionId())).hasSize(CsvTableReader.read(content).rows().size());
            assertThat(records.notices(result.editionId())).isEmpty();
        }
        assertThat(records.inputSourcesWithEditions(packId)).containsExactlyElementsOf(
                firstEditions.keySet().stream().sorted(LinkOrder.STRINGS).toList());
    }

    static Stream<Path> repositoryPacks() throws IOException {
        // 작업 폴더는 server/link 다. 시나리오가 있는 Pack 만 고른다.
        try (Stream<Path> dirs = Files.list(Path.of("../../packs"))) {
            return dirs.filter(d -> Files.isDirectory(d.resolve("questions")) && Files.exists(d.resolve("pack.ttl")))
                .sorted()
                .toList()
                .stream();
        }
    }

    /** 시나리오의 단계에서 입력 자료마다 처음 나오는 파일과 적용일. 단계의 차례 그대로다. */
    private static Map<String, String[]> firstEditions(Path dir) throws IOException {
        Pattern step = Pattern.compile("inputSource:\\s*([^,\\s}]+)\\s*,\\s*file:\\s*([^,\\s}]+)\\s*,\\s*effectiveDate:\\s*([0-9-]+)");
        Map<String, String[]> first = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir.resolve("questions"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                Matcher m = step.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    first.putIfAbsent(m.group(1), new String[] { m.group(2), m.group(3) });
                }
            }
        }
        return first;
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private Map<String, String> itemHashes(String packId, MappingDefinition mapping, String csv, LocalDate effectiveDate) {
        IngestResult result = ingest.ingest(pack(packId, mapping), "src", "f.csv", bytes(csv), effectiveDate, RECEIVED_AT);
        assertThat(result.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (IngestedItem item : records.items(result.editionId())) {
            hashes.put(item.itemKey(), item.contentHash());
        }
        return hashes;
    }

    /** 서로 다른 4바이트 글자를 이은 글자. 되풀이가 없어 저장소가 압축하지 못한다. */
    private static String distinct(int length, int offset) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            text.appendCodePoint(0x20000 + (i * 7919 + offset) % 40000);
        }
        return text.toString();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 시험이 따로 계산하는 SHA-256. 구현과 같은 코드를 쓰지 않으려고 JDK 의 것을 바로 부른다. */
    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 유형 Base 의 개체를 만들고 제목을 넣고 Other 로 잇는 매핑. 열: code, title, other. */
    private static MappingDefinition full(String source) {
        return new MappingDefinition(source, MappingFileFormat.CSV, null, null, List.of(), List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", null, null, null, null, null, null)))),
                List.of(new MappedRelation("links", BASE_BY_CODE, new MappedEntityRef("Other", "other"), null, null)));
    }

    /** 유형 Other 의 개체에 이름을 넣는 매핑. 열: line, other, otherName. */
    private static MappingDefinition shared(String source) {
        MappedEntityRef other = new MappedEntityRef("Other", "other");
        return new MappingDefinition(source, MappingFileFormat.CSV, null, null, List.of(), List.of("line"), other,
                List.of(new MappedEntity("Other", "other",
                        List.of(new MappedAttribute("otherName", "otherName", null, null, null, null, null, null)))),
                List.of());
    }

    /** 지어낸 작은 Pack 의 정의. 저장소에 적재하지 않고 손으로 만든다. */
    private static PackDefinition pack(String packId, MappingDefinition... mappings) {
        return new PackDefinition(packId, "이름표", 3, RECEIVED_AT,
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null)),
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET)),
                List.of(new AttributeDefinition("count", "수", "Base", ValueFormat.INTEGER),
                        new AttributeDefinition("otherName", "다른 이름", "Other", ValueFormat.STRING),
                        new AttributeDefinition("title", "제목", "Base", ValueFormat.STRING)),
                List.of(), List.of(mappings));
    }
}
