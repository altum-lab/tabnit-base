package com.altumlab.tabnit.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.MappedAttribute;
import com.altumlab.tabnit.pack.MappedEntity;
import com.altumlab.tabnit.pack.MappedEntityRef;
import com.altumlab.tabnit.pack.MappedRelation;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.MappingFileFormat;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackImpactDirection;
import com.altumlab.tabnit.pack.RelationTypeDefinition;
import com.altumlab.tabnit.pack.ValueFormat;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * C-09: 참조로만 만들어진 개체의 알림 (D6 7절, D5 5절, D2 AD-138, AD-378).
 *
 * <p>관계의 상대편으로만 나오고, 그 판의 적용일에 어느 입력 자료도 속성 값을 넣어 두지 않은 개체를 판마다 알린다.
 * 투입은 막지 않는다. 지어낸 이름의 매핑과 자료만 쓴다. 시험마다 다른 Pack 식별자를 쓴다.
 */
class ReferenceOnlyEntityTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final LocalDate D1 = LocalDate.parse("2026-03-01");

    private static final LocalDate D2 = LocalDate.parse("2026-04-01");

    private static final LocalDate D3 = LocalDate.parse("2026-05-01");

    private static final String KIND = "REFERENCE_ONLY_ENTITY";

    private final EditionIngest ingest = new EditionIngest(LinkTestDatabase.dataSource());

    private final EditionRecords records = new EditionRecords(LinkTestDatabase.dataSource());

    @Test
    void 관계의_상대편으로만_나오고_속성이_없는_개체를_가리킨_첫_행과_함께_알린다() {
        PackDefinition pack = pack("ro-first");

        IngestResult result = put(pack, "src", 1, D1, "code,title,other,bare\nB-1,첫째,O-1,X-1\nB-2,둘째,O-2,\nB-3,셋째,O-1,\n");

        assertThat(result.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        // O-1 은 두 행이 가리킨다. 앞의 행으로 한 건만 알린다. 속성이 정의되지 않은 유형(Bare)의 개체는 알리지 않는다.
        assertThat(result.mapping().notices()).containsExactly(
                new IngestNotice(KIND, 2, "NO_ATTRIBUTE_VALUES", "ro-first|Other|O-1", null),
                new IngestNotice(KIND, 3, "NO_ATTRIBUTE_VALUES", "ro-first|Other|O-2", null));
        // 투입은 막지 않는다. 알림은 판과 함께 저장된다.
        assertThat(result.mapping().items()).hasSize(3);
        assertThat(records.notices("ro-first|src|1")).isEqualTo(result.mapping().notices());
    }

    @Test
    void 가리키는_동안_판마다_알리고_다른_입력_자료가_속성을_넣으면_그친다() {
        PackDefinition pack = pack("ro-again");
        put(pack, "src", 1, D1, "code,title,other,bare\nB-1,첫째,O-1,\n");

        // 바뀐 것이 없는 행이 여전히 가리킨다.
        IngestResult second = put(pack, "src", 2, D2, "code,title,other,bare\nB-1,첫째,O-1,\nB-2,둘째,O-1,\n");
        assertThat(second.mapping().notices())
            .containsExactly(new IngestNotice(KIND, 2, "NO_ATTRIBUTE_VALUES", "ro-again|Other|O-1", null));

        // 다른 입력 자료가 그 개체의 이름을 넣는다. 그 자료의 행이 스스로 적은 개체는 알리지 않는다.
        IngestResult others = put(pack, "others", 1, D2, "other,otherName\nO-1,하나\nO-9,\n");
        assertThat(others.mapping().notices()).isEmpty();

        IngestResult third = put(pack, "src", 3, D3, "code,title,other,bare\nB-1,첫째,O-1,\n");
        assertThat(third.mapping().notices()).isEmpty();
    }

    @Test
    void 속성이_이_판의_적용일에_이미_끝났으면_다시_알리고_나중에_시작하는_값이_있으면_알리지_않는다() {
        PackDefinition pack = pack("ro-period");
        // O-1 의 이름은 4월 1일에 끝난다. O-2 의 이름은 6월 1일에 시작한다.
        put(pack, "dated", 1, D1, "other,otherName,from,to\nO-1,하나,,2026-04-01\nO-2,둘,2026-06-01,\n");

        IngestResult before = put(pack, "src", 1, D1.plusDays(1), "code,title,other,bare\nB-1,첫째,O-1,\nB-2,둘째,O-2,\n");
        assertThat(before.mapping().notices()).isEmpty();

        IngestResult after = put(pack, "src", 2, D2, "code,title,other,bare\nB-1,첫째,O-1,\nB-2,둘째,O-2,\nB-3,셋째,O-1,\n");
        assertThat(after.mapping().notices())
            .containsExactly(new IngestNotice(KIND, 2, "NO_ATTRIBUTE_VALUES", "ro-period|Other|O-1", null));
    }

    @Test
    void 관계만_담는_입력_자료는_양쪽_끝을_모두_본다_건너뛴_행이_가리킨_것은_보지_않는다() {
        PackDefinition pack = pack("ro-links");
        put(pack, "src", 1, D1, "code,title,other,bare\nB-1,첫째,,\n");

        IngestResult result = put(pack, "links", 1, D2, "code,other\nB-1,O-1\nB-7,O-1\nB-8,B-8\n,O-5\n");

        // B-1 은 다른 자료가 제목을 넣었다. B-7 과 O-1 은 속성이 없다. 넷째 행은 키가 비어 건너뛰었다.
        assertThat(result.mapping().notices()).extracting(IngestNotice::kind, IngestNotice::rowNo, IngestNotice::subject)
            .contains(org.assertj.core.groups.Tuple.tuple(KIND, 2, "ro-links|Other|O-1"),
                    org.assertj.core.groups.Tuple.tuple(KIND, 3, "ro-links|Base|B-7"),
                    org.assertj.core.groups.Tuple.tuple(KIND, 4, "ro-links|Base|B-8"),
                    org.assertj.core.groups.Tuple.tuple(KIND, 4, "ro-links|Other|B-8"))
            .doesNotContain(org.assertj.core.groups.Tuple.tuple(KIND, 5, "ro-links|Other|O-5"));
        assertThat(result.mapping().notices()).filteredOn(n -> KIND.equals(n.kind())).hasSize(4);
    }

    @Test
    void 다른_끝이_비어_관계를_적지_않은_행의_주_개체는_가리킨_것이_아니라서_알리지_않는다() {
        // 관계만 담고 항목의 키가 code 하나인 입력 자료.
        MappedEntityRef base = new MappedEntityRef("Base", "code");
        PackDefinition pack = with(pack("ro-alone"),
                new MappingDefinition("alone", MappingFileFormat.CSV, null, null, List.of(), List.of("code"), base,
                        List.of(), List.of(new MappedRelation("links", base, new MappedEntityRef("Other", "other"), null, null))));

        // 첫 행은 관계의 다른 끝이 비었다. Base B-1 은 어느 자료도 제목을 넣지 않았지만 아무도 가리키지 않았다.
        IngestResult result = put(pack, "alone", 1, D1, "code,other\nB-1,\nB-2,O-1\n");

        assertThat(result.mapping().items()).hasSize(2);
        assertThat(result.mapping().notices()).extracting(IngestNotice::rowNo, IngestNotice::subject)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(3, "ro-alone|Base|B-2"),
                    org.assertj.core.groups.Tuple.tuple(3, "ro-alone|Other|O-1"));
    }

    @Test
    void 이_판이_끝내는_값만_가진_개체는_알린다() {
        PackDefinition pack = pack("ro-ended");
        // own 은 Other 의 이름을 스스로 적는 자료다. 첫 판에서 O-1 의 이름을 넣는다.
        put(pack, "others", 1, D1, "other,otherName\nO-1,하나\n");
        put(pack, "src", 1, D1, "code,title,other,bare\nB-1,첫째,O-1,\n");

        // others 의 둘째 판에서 O-1 의 행이 없어진다: 이름이 그 적용일에 끝난다.
        put(pack, "others", 2, D2, "other,otherName\nO-2,둘\n");
        IngestResult after = put(pack, "src", 2, D2, "code,title,other,bare\nB-1,첫째,O-1,\nB-2,둘째,O-1,\n");

        assertThat(after.mapping().notices())
            .containsExactly(new IngestNotice(KIND, 2, "NO_ATTRIBUTE_VALUES", "ro-ended|Other|O-1", null));
    }

    @Test
    void 같은_판이_스스로_적던_행을_빼면서_다른_행이_관계의_끝으로_가리키면_그_판에서_알린다() {
        // 한 입력 자료가 Other 의 이름을 적는 행과, Other 를 관계의 끝으로만 가리키는 행을 함께 담는다.
        MappedEntityRef base = new MappedEntityRef("Base", "code");
        MappedEntityRef other = new MappedEntityRef("Other", "other");
        MappedEntityRef named = new MappedEntityRef("Other", "named");
        MappingDefinition mixed = new MappingDefinition("mixed", MappingFileFormat.CSV, null, null, List.of(),
                List.of("line"), base,
                List.of(new MappedEntity("Other", "named",
                        List.of(new MappedAttribute("otherName", "otherName", null, null, null, null, null, null)))),
                List.of(new MappedRelation("links", base, other, null, null)));
        PackDefinition pack = with(pack("ro-own-ended"), mixed);
        String header = "line,code,other,named,otherName\n";
        // 1 번 행이 O-1 의 이름을 적고, 2 번 행이 O-1 을 관계의 끝으로 가리킨다.
        IngestResult first = put(pack, "mixed", 1, D1, header + "1,B-1,,O-1,하나\n2,B-2,O-1,,\n");
        assertThat(first.mapping().notices()).extracting(IngestNotice::subject).containsExactly("ro-own-ended|Base|B-2");

        // 1 번 행이 없어진다. 저장소에는 아직 O-1 의 이름이 있지만 이 판이 끝낸다.
        IngestResult second = put(pack, "mixed", 2, D2, header + "2,B-2,O-1,,\n");

        assertThat(second.mapping().notices()).extracting(IngestNotice::rowNo, IngestNotice::subject)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "ro-own-ended|Base|B-2"),
                    org.assertj.core.groups.Tuple.tuple(2, "ro-own-ended|Other|O-1"));
    }

    @Test
    void 키가_겹쳐_건너뛴_행과_앞_판의_것을_이어_둔_항목이_가리킨_개체는_알리지_않는다() {
        PackDefinition pack = pack("ro-skipped");
        put(pack, "others", 1, D1, "other,otherName\nO-1,하나\n");
        put(pack, "src", 1, D1, "code,title,other,bare\nB-1,첫째,O-1,\n");

        // B-1 은 두 번 적혀 건너뛰고 앞 판의 것을 이어 둔다. 두 행이 속성 없는 O-7, O-8 을 가리킨다.
        IngestResult result = put(pack, "src", 2, D2, "code,title,other,bare\nB-1,첫째,O-7,\nB-1,다시,O-8,\nB-2,둘째,O-1,\n");

        assertThat(result.mapping().notices()).extracting(IngestNotice::rowNo, IngestNotice::code)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "DUPLICATE_ITEM_KEY"),
                    org.assertj.core.groups.Tuple.tuple(3, "DUPLICATE_ITEM_KEY"));
    }

    @Test
    void 상위_유형에만_속성이_정의된_하위_유형의_개체도_알린다() {
        MappedEntityRef base = new MappedEntityRef("Base", "code");
        MappingDefinition toChild = new MappingDefinition("children", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code", "child"), base, List.of(),
                List.of(new MappedRelation("raises", base, new MappedEntityRef("Child", "child"), null, null)));
        PackDefinition original = with(pack("ro-child"), toChild);
        List<EntityTypeDefinition> types = new java.util.ArrayList<>(original.entityTypes());
        // Child 는 Other 의 하위 유형이다. 스스로 정의한 속성은 없고 Other 의 이름을 물려받는다.
        types.add(new EntityTypeDefinition("Child", "하위", List.of("Other"), null));
        List<RelationTypeDefinition> relationTypes = new java.util.ArrayList<>(original.relationTypes());
        relationTypes.add(new RelationTypeDefinition("raises", "기른다", "Base", "Child", PackImpactDirection.SOURCE_TO_TARGET));
        PackDefinition pack = new PackDefinition(original.packId(), original.label(), original.defaultMaxDepth(),
                original.loadedAt(), types, relationTypes, original.attributes(), original.localizedLabels(), original.mappings());
        put(pack, "src", 1, D1, "code,title,other,bare\nB-1,첫째,,\n");

        IngestResult result = put(pack, "children", 1, D2, "code,child\nB-1,C-1\n");

        assertThat(result.mapping().notices())
            .containsExactly(new IngestNotice(KIND, 2, "NO_ATTRIBUTE_VALUES", "ro-child|Child|C-1", null));
    }

    @Test
    void 가리킨_개체가_천을_넘어도_속성이_있는_것과_없는_것을_가린다() {
        PackDefinition pack = pack("ro-many");
        int count = 2_400;
        StringBuilder others = new StringBuilder("other,otherName\n");
        StringBuilder src = new StringBuilder("code,title,other,bare\n");
        for (int i = 0; i < count; i++) {
            // 짝수 번째 개체만 이름이 있다.
            if (i % 2 == 0) {
                others.append(String.format("O-%05d,이름 %d%n", i, i));
            }
            src.append(String.format("B-%05d,제목,O-%05d,%n", i, i));
        }
        put(pack, "others", 1, D1, others.toString());

        IngestResult result = put(pack, "src", 1, D2, src.toString());

        assertThat(result.mapping().notices()).hasSize(count / 2).allSatisfy(notice -> {
            assertThat(notice.kind()).isEqualTo(KIND);
            assertThat(Integer.parseInt(notice.subject().substring(notice.subject().length() - 5)) % 2).isEqualTo(1);
        });
        // 식별자 O-00001 은 둘째 자료 행(3 번째 줄)이 가리킨다.
        assertThat(result.mapping().notices().get(0))
            .isEqualTo(new IngestNotice(KIND, 3, "NO_ATTRIBUTE_VALUES", "ro-many|Other|O-00001", null));
    }

    @Test
    void 같은_자료로_두_번_만든_알림이_같다() {
        String csv = "code,title,other,bare\nB-2,둘째,O-2,\nB-1,첫째,O-1,\nB-3,셋째,O-2,\n";

        IngestResult one = put(pack("ro-same-1"), "src", 1, D1, csv);
        IngestResult two = put(pack("ro-same-2"), "src", 1, D1, csv);

        assertThat(one.mapping().notices()).extracting(IngestNotice::rowNo, n -> n.subject().replace("ro-same-1", "p"))
            .isEqualTo(two.mapping().notices().stream()
                .map(n -> org.assertj.core.groups.Tuple.tuple(n.rowNo(), n.subject().replace("ro-same-2", "p")))
                .toList());
        assertThat(one.mapping().notices()).hasSize(2);
    }

    // ── 도우미 ───────────────────────────────────────────────────

    private IngestResult put(PackDefinition pack, String source, int editionNo, LocalDate effectiveDate, String csv) {
        return ingest.ingest(pack, source, source + " " + editionNo + ".csv", csv.getBytes(StandardCharsets.UTF_8),
                effectiveDate, AT.plusSeconds(editionNo));
    }

    /** 그 Pack 에 매핑 하나를 더한다. */
    private static PackDefinition with(PackDefinition pack, MappingDefinition extra) {
        List<MappingDefinition> mappings = new java.util.ArrayList<>(pack.mappings());
        mappings.add(extra);
        return new PackDefinition(pack.packId(), pack.label(), pack.defaultMaxDepth(), pack.loadedAt(), pack.entityTypes(),
                pack.relationTypes(), pack.attributes(), pack.localizedLabels(), mappings);
    }

    /**
     * 입력 자료 넷. src: Base 의 제목과, Other 와 Bare 로 가는 관계. others: Other 의 이름. dated: 유효기간이 적힌 Other 의
     * 이름. links: Base 와 Other 의 관계만.
     */
    private static PackDefinition pack(String packId) {
        MappedEntityRef base = new MappedEntityRef("Base", "code");
        MappedEntityRef other = new MappedEntityRef("Other", "other");
        MappingDefinition src = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(), List.of("code"),
                base,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", null, null, null, null, null, null)))),
                List.of(new MappedRelation("links", base, other, null, null),
                        new MappedRelation("holds", base, new MappedEntityRef("Bare", "bare"), null, null)));
        MappingDefinition others = new MappingDefinition("others", MappingFileFormat.CSV, null, null, List.of(),
                List.of("other"), other,
                List.of(new MappedEntity("Other", "other",
                        List.of(new MappedAttribute("otherName", "otherName", null, null, null, null, null, null)))),
                List.of());
        MappingDefinition dated = new MappingDefinition("dated", MappingFileFormat.CSV, null, null, List.of(),
                List.of("other"), other,
                List.of(new MappedEntity("Other", "other",
                        List.of(new MappedAttribute("otherName", "otherName", "from", "to", null, null, null, null)))),
                List.of());
        MappingDefinition links = new MappingDefinition("links", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code", "other"), base, List.of(), List.of(new MappedRelation("links", base, other, null, null)));
        return new PackDefinition(packId, "이름표", 1, AT,
                List.of(new EntityTypeDefinition("Bare", "속성 없는 것", List.of(), null),
                        new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null)),
                List.of(new RelationTypeDefinition("holds", "가진다", "Base", "Bare", PackImpactDirection.SOURCE_TO_TARGET),
                        new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET)),
                List.of(new AttributeDefinition("otherName", "다른 이름", "Other", ValueFormat.STRING),
                        new AttributeDefinition("title", "제목", "Base", ValueFormat.STRING)),
                List.of(), List.of(src, others, dated, links));
    }
}
