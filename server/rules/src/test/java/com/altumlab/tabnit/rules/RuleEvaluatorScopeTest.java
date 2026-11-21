package com.altumlab.tabnit.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.altumlab.tabnit.core.AttributeValueStore;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.ImpactDirection;
import com.altumlab.tabnit.core.ImpactQuery;
import com.altumlab.tabnit.core.ImpactRequest;
import com.altumlab.tabnit.core.ImpactScope;
import com.altumlab.tabnit.core.NewAttributeValue;
import com.altumlab.tabnit.core.NewRelation;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.RelationStore;
import com.altumlab.tabnit.core.ValidityPeriod;
import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackImpactDirection;
import com.altumlab.tabnit.pack.RelationTypeDefinition;
import com.altumlab.tabnit.pack.RuleChangeKind;
import com.altumlab.tabnit.pack.RuleCompareTo;
import com.altumlab.tabnit.pack.RuleCondition;
import com.altumlab.tabnit.pack.RuleDefinition;
import com.altumlab.tabnit.pack.RuleOperator;
import com.altumlab.tabnit.pack.RuleSubject;
import com.altumlab.tabnit.pack.ValueFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * B-03 (규칙의 형식 넓히기, D2 AD-289 ~ AD-292): 규칙이 변동이 나온 입력 자료와 그 행에서 바뀐 것을 가리고, 조건이
 * 속성 값을 판의 적용일이나 다른 대상의 속성과 견준다.
 *
 * <p>어느 Pack 과도 무관한 일반 시험이다. 모든 이름과 값은 지어낸 것이다. 판의 적용일은 D 다.
 */
class RuleEvaluatorScopeTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final LocalDate START = LocalDate.parse("2026-01-01");

    private static final LocalDate D = LocalDate.parse("2026-06-01");

    private static final Provenance SOURCE = new Provenance("src", 1);

    private static final RuleSubject IMPACT = RuleSubject.IMPACT_TARGET;

    private static final RuleSubject CHANGE = RuleSubject.CHANGE_TARGET;

    private static final List<RuleChangeKind> ALL = List.of(RuleChangeKind.values());

    private final RelationStore relations = new RelationStore(RulesTestDatabase.dataSource());

    private final AttributeValueStore values = new AttributeValueStore(RulesTestDatabase.dataSource());

    private final ImpactQuery impact = new ImpactQuery(RulesTestDatabase.dataSource());

    private final RuleEvaluator evaluator = new RuleEvaluator(RulesTestDatabase.dataSource());

    // ── 입력 자료 (D2 AD-290) ───────────────────────────────────

    @Test
    void 입력_자료를_적은_규칙은_그_입력_자료에서_나온_변동에만_걸린다() {
        String p = "s-source";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        PackDefinition pack = pack(p, scoped("0001", IMPACT, "Other", List.of("src-a", "src-b"), List.of(), List.of()),
                scoped("0002", IMPACT, "Other", List.of("src-c"), List.of(), List.of()),
                scoped("0003", IMPACT, "Other", List.of(), List.of(), List.of()));

        RuleSelection fromB = evaluator.select(pack, change(start, "src-b", List.of(), List.of()), scope(start));
        RuleSelection fromOther = evaluator.select(pack, change(start, "src-z", List.of(), List.of()), scope(start));

        assertThat(fromB.failures()).isEmpty();
        // 적지 않은 규칙(0003)은 모든 입력 자료에 걸린다.
        assertThat(fromB.matches()).extracting(RuleMatch::ruleNo).containsExactly("0001", "0003");
        assertThat(fromOther.matches()).extracting(RuleMatch::ruleNo).containsExactly("0003");
    }

    @Test
    void 입력_자료를_모르는_변동에서_입력_자료를_가리는_규칙은_건너뛰지_않고_실패로_알린다() {
        String p = "s-source-unknown";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        PackDefinition pack = pack(p, scoped("0001", IMPACT, "Other", List.of("src-a"), List.of(), List.of()),
                scoped("0002", IMPACT, "Other", List.of(), List.of(), List.of()));

        // 부르는 쪽이 입력 자료와 바뀐 것을 넘기지 않았다.
        RuleSelection selection = evaluator.select(pack,
                new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(), List.of(start.id()), D), scope(start));

        assertThat(selection.matches()).extracting(RuleMatch::ruleNo).containsExactly("0002");
        assertThat(selection.failures())
            .containsExactly(new RuleFailure("0001", "INPUT_SOURCE_UNKNOWN", null, null, null));
    }

    // ── 바뀐 것 (D2 AD-291) ─────────────────────────────────────

    @Test
    void 바뀐_것을_적은_규칙은_적은_것_가운데_하나라도_바뀐_변동에만_걸린다() {
        String p = "s-changed";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        PackDefinition pack = pack(p,
                scoped("0001", IMPACT, "Other", List.of(), List.of("count", "title"), List.of()),
                scoped("0002", IMPACT, "Other", List.of(), List.of(), List.of("links")),
                scoped("0003", IMPACT, "Other", List.of(), List.of("ratio"), List.of("links")),
                scoped("0004", IMPACT, "Other", List.of(), List.of(), List.of()));

        assertThat(evaluator.select(pack, change(start, "s", List.of("title"), List.of()), scope(start)).matches())
            .extracting(RuleMatch::ruleNo)
            .containsExactly("0001", "0004");
        assertThat(evaluator.select(pack, change(start, "s", List.of("since"), List.of("links")), scope(start))
            .matches()).extracting(RuleMatch::ruleNo).containsExactly("0002", "0003", "0004");
        // 적은 것이 하나도 바뀌지 않았으면 걸리지 않는다. 바뀐 것이 없는 변동도 그렇다.
        assertThat(evaluator.select(pack, change(start, "s", List.of("since"), List.of()), scope(start)).matches())
            .extracting(RuleMatch::ruleNo)
            .containsExactly("0004");
        assertThat(evaluator.select(pack, change(start, "s", List.of(), List.of()), scope(start)).matches())
            .extracting(RuleMatch::ruleNo)
            .containsExactly("0004");
    }

    @Test
    void 속성과_관계_유형의_이름이_같아도_서로_섞이지_않는다() {
        String p = "s-changed-kind";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        PackDefinition pack = pack(p, scoped("0001", IMPACT, "Other", List.of(), List.of("title"), List.of()));

        // 이름이 title 인 관계 유형이 바뀐 것이지 속성 title 이 바뀐 것이 아니다.
        assertThat(evaluator.select(pack, change(start, "s", List.of(), List.of("title")), scope(start)).matches())
            .isEmpty();
    }

    @Test
    void 바뀐_것을_모르는_변동에서_바뀐_것을_가리는_규칙은_건너뛰지_않고_실패로_알린다() {
        String p = "s-changed-unknown";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        PackDefinition pack = pack(p, scoped("0001", IMPACT, "Other", List.of(), List.of("title"), List.of()),
                scoped("0002", IMPACT, "Other", List.of("src-a"), List.of(), List.of()));

        // 입력 자료는 알지만 바뀐 것은 남지 않은 변동이다 (바뀐 것을 남기기 전에 만들어진 변동 이벤트).
        RuleSelection selection = evaluator.select(pack, new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(),
                List.of(start.id()), D, "src-a", null, null), scope(start));

        assertThat(selection.matches()).extracting(RuleMatch::ruleNo).containsExactly("0002");
        assertThat(selection.failures())
            .containsExactly(new RuleFailure("0001", "CHANGED_ITEMS_UNKNOWN", null, null, null));
    }

    @Test
    void 종류가_맞지_않는_변동에서는_모르는_것이_있어도_실패가_아니다() {
        String p = "s-kind-first";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        RuleDefinition added = new RuleDefinition("0001", "규칙", 1, List.of(RuleChangeKind.ADDED), IMPACT, "Other",
                List.of(), "할 일", 7, List.of("src-a"), List.of("title"), List.of());

        RuleSelection selection = evaluator.select(pack(p, added),
                new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(), List.of(start.id()), D), scope(start));

        assertThat(selection.matches()).isEmpty();
        assertThat(selection.failures()).isEmpty();
    }

    // ── 판의 적용일과 견주기 (D2 AD-292) ─────────────────────────

    @Test
    void 날짜_속성을_판의_적용일과_견준다() {
        String p = "s-date";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef before = entity(p, "Other", "O-1");
        EntityRef same = entity(p, "Other", "O-2");
        EntityRef after = entity(p, "Other", "O-3");
        EntityRef none = entity(p, "Other", "O-4");
        for (EntityRef other : List.of(before, same, after, none)) {
            link(start, other);
        }
        value(before, "since", D.minusDays(1).toString());
        value(same, "since", D.toString());
        value(after, "since", D.plusDays(1).toString());
        PackDefinition pack = pack(p, rule("0001", IMPACT, "Other", effective(IMPACT, RuleOperator.LESS_THAN)),
                rule("0002", IMPACT, "Other", effective(IMPACT, RuleOperator.LESS_THAN_OR_EQUAL)),
                rule("0003", IMPACT, "Other", effective(IMPACT, RuleOperator.EQUALS)),
                rule("0004", IMPACT, "Other", effective(IMPACT, RuleOperator.NOT_EQUALS)),
                rule("0005", IMPACT, "Other", effective(IMPACT, RuleOperator.GREATER_THAN)),
                rule("0006", IMPACT, "Other", effective(IMPACT, RuleOperator.GREATER_THAN_OR_EQUAL)));

        RuleSelection selection = evaluator.select(pack, change(start, "s", List.of(), List.of()), scope(start));

        assertThat(selection.failures()).isEmpty();
        // 값이 없는 대상(O-4)은 어느 비교도 만족하지 않는다. "다르다"도 그렇다 (D2 AD-104).
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, m -> m.targetEntityId().substring(p.length() + 7))
            .containsExactly(tuple("0001", "O-1"), tuple("0002", "O-1"), tuple("0002", "O-2"), tuple("0003", "O-2"),
                    tuple("0004", "O-1"), tuple("0004", "O-3"), tuple("0005", "O-3"), tuple("0006", "O-2"),
                    tuple("0006", "O-3"));
    }

    @Test
    void 적용일과_견줄_값이_날짜가_아니면_그_규칙은_실패다() {
        String p = "s-date-bad";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef other = entity(p, "Other", "O-1");
        link(start, other);
        value(other, "since", "어제");
        PackDefinition pack = pack(p, rule("0001", IMPACT, "Other", effective(IMPACT, RuleOperator.LESS_THAN)),
                // 날짜가 아닌 속성을 적용일과 견주는 정의. Pack 을 읽을 때 걸러지지만 넘어와도 죽지 않는다.
                rule("0002", IMPACT, "Other", new RuleCondition(IMPACT, null, "count", RuleOperator.LESS_THAN,
                        List.of(), RuleCompareTo.EFFECTIVE_DATE, null, null)));

        RuleSelection selection = evaluator.select(pack, change(start, "s", List.of(), List.of()), scope(start));

        assertThat(selection.matches()).isEmpty();
        assertThat(selection.failures()).containsExactly(
                new RuleFailure("0001", "VALUE_FORMAT_MISMATCH", other.id(), "since", "어제"),
                new RuleFailure("0002", "INVALID_RULE", null, "count", null));
    }

    // ── 다른 대상의 속성과 견주기 (D2 AD-292) ────────────────────

    @Test
    void 영향_대상의_속성을_변동_대상의_속성과_견준다() {
        String p = "s-other";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef low = entity(p, "Other", "O-1");
        EntityRef equal = entity(p, "Other", "O-2");
        EntityRef high = entity(p, "Other", "O-3");
        EntityRef none = entity(p, "Other", "O-4");
        for (EntityRef other : List.of(low, equal, high, none)) {
            link(start, other);
        }
        value(start, "count", "10");
        value(low, "count", "9");
        // 수는 값으로 견준다. 표기가 달라도 같은 값이다.
        value(equal, "count", "+10");
        value(high, "count", "11");
        PackDefinition pack = pack(p,
                rule("0001", IMPACT, "Other", against(IMPACT, "count", RuleOperator.LESS_THAN, CHANGE, "count")),
                rule("0002", IMPACT, "Other", against(IMPACT, "count", RuleOperator.EQUALS, CHANGE, "count")),
                // 거꾸로: 변동 대상의 값이 영향 대상의 값보다 작은가.
                rule("0003", IMPACT, "Other", against(CHANGE, "count", RuleOperator.LESS_THAN, IMPACT, "count")));

        RuleSelection selection = evaluator.select(pack, change(start, "s", List.of(), List.of()), scope(start));

        assertThat(selection.failures()).isEmpty();
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, m -> m.targetEntityId().substring(p.length() + 7))
            .containsExactly(tuple("0001", "O-1"), tuple("0002", "O-2"), tuple("0003", "O-3"));
    }

    @Test
    void 어느_한쪽에_값이_없으면_만족하지_않는다() {
        String p = "s-other-none";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef other = entity(p, "Other", "O-1");
        link(start, other);
        value(other, "title", "가");
        PackDefinition pack = pack(p,
                rule("0001", IMPACT, "Other", against(IMPACT, "title", RuleOperator.NOT_EQUALS, CHANGE, "title")),
                rule("0002", IMPACT, "Other", against(CHANGE, "title", RuleOperator.NOT_EQUALS, IMPACT, "title")));

        RuleSelection selection = evaluator.select(pack, change(start, "s", List.of(), List.of()), scope(start));

        assertThat(selection.matches()).isEmpty();
        assertThat(selection.failures()).isEmpty();
    }

    @Test
    void 변동_대상에_거는_규칙은_변동_대상의_두_속성을_견준다() {
        String p = "s-self";
        EntityRef first = entity(p, "Base", "B-1");
        EntityRef second = entity(p, "Base", "B-2");
        value(first, "count", "3");
        value(first, "ratio", "1.5");
        value(second, "since", "2026-02-01");
        value(second, "title", "가");
        PackDefinition pack = pack(p,
                // 형식이 다른 두 속성을 견주는 정의. Pack 을 읽을 때 걸러지지만 넘어와도 죽지 않는다.
                rule("0001", CHANGE, "Base", against(CHANGE, "count", RuleOperator.EQUALS, CHANGE, "ratio")),
                rule("0002", CHANGE, "Base", against(CHANGE, "count", RuleOperator.GREATER_THAN_OR_EQUAL, CHANGE, "count")),
                // 변동 대상에 거는 규칙이 영향 대상의 속성과 견주는 정의도 그렇다.
                rule("0003", CHANGE, "Base", against(CHANGE, "count", RuleOperator.EQUALS, IMPACT, "count")));

        RuleSelection selection = evaluator.select(pack,
                new RuleChange("ev-1", RuleChangeKind.CHANGED, first.id(), List.of(first.id(), second.id()), D, "s",
                        List.of(), List.of()),
                scope(first, second));

        // 자기 자신과 견주면 값이 있는 대상만 해당한다.
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, RuleMatch::targetEntityId)
            .containsExactly(tuple("0002", first.id()));
        assertThat(selection.failures()).extracting(RuleFailure::ruleNo, RuleFailure::code)
            .containsExactly(tuple("0001", "INVALID_RULE"), tuple("0003", "INVALID_RULE"));
    }

    @Test
    void 견줄_상대의_저장된_값이_형식과_맞지_않으면_그_규칙은_실패다() {
        String p = "s-other-bad";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef other = entity(p, "Other", "O-1");
        link(start, other);
        value(start, "count", "열");
        value(other, "count", "9");
        PackDefinition pack = pack(p,
                rule("0001", IMPACT, "Other", against(IMPACT, "count", RuleOperator.LESS_THAN, CHANGE, "count")));

        RuleSelection selection = evaluator.select(pack, change(start, "s", List.of(), List.of()), scope(start));

        assertThat(selection.matches()).isEmpty();
        assertThat(selection.failures())
            .containsExactly(new RuleFailure("0001", "VALUE_FORMAT_MISMATCH", start.id(), "count", "열"));
    }

    @Test
    void 조건의_차례가_달라도_틀린_값이_하나라도_있으면_실패다() {
        String p = "s-order";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef other = entity(p, "Other", "O-1");
        link(start, other);
        value(start, "count", "열");
        value(other, "count", "9");
        value(other, "since", D.plusDays(1).toString());
        // 하나는 거짓이고 (적용일보다 뒤다), 하나는 견주는 상대의 값이 형식과 맞지 않는다 (D2 AD-224).
        RuleCondition isFalse = effective(IMPACT, RuleOperator.LESS_THAN);
        RuleCondition broken = against(IMPACT, "count", RuleOperator.LESS_THAN, CHANGE, "count");
        RuleChange change = change(start, "s", List.of(), List.of());

        RuleSelection one = evaluator.select(pack(p, rule("0001", IMPACT, "Other", isFalse, broken)), change, scope(start));
        RuleSelection two = evaluator.select(pack(p, rule("0001", IMPACT, "Other", broken, isFalse)), change, scope(start));

        assertThat(one.matches()).isEmpty();
        assertThat(one.failures())
            .containsExactly(new RuleFailure("0001", "VALUE_FORMAT_MISMATCH", start.id(), "count", "열"));
        assertThat(two).isEqualTo(one);
    }

    @Test
    void 한쪽에_값이_없어도_다른_쪽의_틀린_값은_실패다() {
        String p = "s-none-bad";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef other = entity(p, "Other", "O-1");
        link(start, other);
        value(start, "count", "열");
        PackDefinition pack = pack(p,
                rule("0001", IMPACT, "Other", against(IMPACT, "count", RuleOperator.LESS_THAN, CHANGE, "count")));

        RuleSelection selection = evaluator.select(pack, change(start, "s", List.of(), List.of()), scope(start));

        assertThat(selection.failures())
            .containsExactly(new RuleFailure("0001", "VALUE_FORMAT_MISMATCH", start.id(), "count", "열"));
    }

    @Test
    void 견주는_상대의_값도_적용일에_없으면_전날의_값으로_본다() {
        String p = "s-prev";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef other = entity(p, "Other", "O-1");
        link(start, other);
        // 변동 대상의 값은 적용일에 끝났다 (삭제된 행이 넣었던 값이 이렇다). 전날에는 유효하다 (D2 AD-101).
        values.saveAttributeValue(new NewAttributeValue(start, "count", "10", new ValidityPeriod(START, D), SOURCE), AT);
        value(other, "count", "9");
        PackDefinition pack = pack(p,
                rule("0001", IMPACT, "Other", against(IMPACT, "count", RuleOperator.LESS_THAN, CHANGE, "count")));

        RuleSelection selection = evaluator.select(pack, change(start, "s", List.of(), List.of()), scope(start));

        assertThat(selection.failures()).isEmpty();
        assertThat(selection.matches()).extracting(RuleMatch::targetEntityId).containsExactly(other.id());
    }

    @Test
    void 변동_대상이_여럿이면_그_영향_대상의_근거_경로가_시작한_변동_대상의_값과_견준다() {
        String p = "s-two-starts";
        EntityRef first = entity(p, "Base", "B-1");
        EntityRef second = entity(p, "Base", "B-2");
        EntityRef nearFirst = entity(p, "Other", "O-1");
        EntityRef nearSecond = entity(p, "Other", "O-2");
        link(first, nearFirst);
        link(second, nearSecond);
        value(first, "count", "10");
        value(second, "count", "5");
        value(nearFirst, "count", "7");
        value(nearSecond, "count", "7");
        PackDefinition pack = pack(p,
                rule("0001", IMPACT, "Other", against(IMPACT, "count", RuleOperator.LESS_THAN, CHANGE, "count")));

        RuleSelection selection = evaluator.select(pack, new RuleChange("ev-1", RuleChangeKind.CHANGED, first.id(),
                List.of(first.id(), second.id()), D, "s", List.of(), List.of()), scope(first, second));

        // 7 은 B-1 의 10 보다 작고 B-2 의 5 보다 작지 않다. 영향 대상마다 자기 쪽 변동 대상과 견준다 (D2 AD-64).
        assertThat(selection.matches()).extracting(RuleMatch::targetEntityId).containsExactly(nearFirst.id());
    }

    @Test
    void 가리는_차례는_입력_자료가_먼저다() {
        String p = "s-scope-order";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        PackDefinition pack = pack(p, scoped("0001", IMPACT, "Other", List.of("src-a"), List.of("title"), List.of()));

        // 입력 자료가 맞지 않으면 바뀐 것을 몰라도 실패가 아니다: 어차피 걸리지 않는 변동이다.
        RuleSelection otherSource = evaluator.select(pack, new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(),
                List.of(start.id()), D, "src-z", null, null), scope(start));
        // 둘 다 모르면 먼저 보는 입력 자료의 것으로 알린다.
        RuleSelection nothingKnown = evaluator.select(pack,
                new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(), List.of(start.id()), D), scope(start));

        assertThat(otherSource.matches()).isEmpty();
        assertThat(otherSource.failures()).isEmpty();
        assertThat(nothingKnown.failures())
            .containsExactly(new RuleFailure("0001", "INPUT_SOURCE_UNKNOWN", null, null, null));
    }

    @Test
    void 소수와_날짜와_참거짓도_값으로_견준다() {
        String p = "s-formats";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef other = entity(p, "Other", "O-1");
        link(start, other);
        value(start, "ratio", "1.50");
        value(other, "ratio", "1.5");
        value(start, "since", "2026-03-01");
        value(other, "since", "2026-02-28");
        value(start, "active", "true");
        value(other, "active", "false");
        PackDefinition pack = pack(p,
                rule("0001", IMPACT, "Other", against(IMPACT, "ratio", RuleOperator.EQUALS, CHANGE, "ratio")),
                rule("0002", IMPACT, "Other", against(IMPACT, "since", RuleOperator.LESS_THAN, CHANGE, "since")),
                rule("0003", IMPACT, "Other", against(IMPACT, "active", RuleOperator.NOT_EQUALS, CHANGE, "active")),
                rule("0004", IMPACT, "Other", against(IMPACT, "ratio", RuleOperator.GREATER_THAN, CHANGE, "ratio")));

        RuleSelection selection = evaluator.select(pack, change(start, "s", List.of(), List.of()), scope(start));

        assertThat(selection.failures()).isEmpty();
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo).containsExactly("0001", "0002", "0003");
    }

    @Test
    void 같은_변동에_두_번_적용한_결과가_같다() {
        String p = "s-twice";
        EntityRef start = entity(p, "Base", "B-1");
        for (int i = 0; i < 20; i++) {
            EntityRef other = entity(p, "Other", "O-" + (10 + i));
            link(start, other);
            value(other, "since", D.plusDays(i - 10).toString());
        }
        PackDefinition pack = pack(p,
                new RuleDefinition("0001", "규칙", 1, ALL, IMPACT, "Other",
                        List.of(effective(IMPACT, RuleOperator.LESS_THAN)), "할 일", 7, List.of("s"),
                        List.of("title"), List.of("links")));
        RuleChange change = change(start, "s", List.of("title", "count"), List.of());

        RuleSelection first = evaluator.select(pack, change, scope(start));
        RuleSelection second = evaluator.select(pack, change, scope(start));

        assertThat(first.matches()).hasSize(10);
        assertThat(second).isEqualTo(first);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private static RuleCondition effective(RuleSubject subject, RuleOperator operator) {
        return new RuleCondition(subject, null, "since", operator, List.of(), RuleCompareTo.EFFECTIVE_DATE, null, null);
    }

    private static RuleCondition against(RuleSubject subject, String attribute, RuleOperator operator,
            RuleSubject otherSubject, String otherAttribute) {
        return new RuleCondition(subject, null, attribute, operator, List.of(), RuleCompareTo.ATTRIBUTE, otherSubject,
                otherAttribute);
    }

    private static RuleDefinition rule(String ruleNo, RuleSubject appliesTo, String targetType,
            RuleCondition... conditions) {
        return new RuleDefinition(ruleNo, "규칙 " + ruleNo, 1, ALL, appliesTo, targetType, List.of(conditions), "할 일", 7);
    }

    private static RuleDefinition scoped(String ruleNo, RuleSubject appliesTo, String targetType, List<String> sources,
            List<String> changedAttributes, List<String> changedRelationTypes) {
        return new RuleDefinition(ruleNo, "규칙 " + ruleNo, 1, ALL, appliesTo, targetType, List.of(), "할 일", 7, sources,
                changedAttributes, changedRelationTypes);
    }

    private static RuleChange change(EntityRef start, String inputSourceId, List<String> changedAttributes,
            List<String> changedRelationTypes) {
        return new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(), List.of(start.id()), D, inputSourceId,
                changedAttributes, changedRelationTypes);
    }

    private EntityRef entity(String packId, String type, String key) {
        EntityRef entity = new EntityRef(packId, type, key);
        relations.saveEntity(entity, AT);
        return entity;
    }

    private void link(EntityRef source, EntityRef target) {
        relations.saveRelation(new NewRelation(source, target, "links", new ValidityPeriod(START, null), SOURCE), AT);
    }

    private void value(EntityRef entity, String attribute, String value) {
        values.saveAttributeValue(new NewAttributeValue(entity, attribute, value, new ValidityPeriod(START, null), SOURCE),
                AT);
    }

    private ImpactScope scope(EntityRef... starts) {
        List<String> ids = new ArrayList<>();
        for (EntityRef start : starts) {
            ids.add(start.id());
        }
        return impact.impactScope(new ImpactRequest(ids, List.of(D.minusDays(1), D),
                Map.of("links", ImpactDirection.SOURCE_TO_TARGET), 3));
    }

    /** 지어낸 작은 Pack 의 정의. 유형: Base, Other. 속성은 형식마다 하나. */
    private static PackDefinition pack(String packId, RuleDefinition... rules) {
        List<AttributeDefinition> attributes = new ArrayList<>();
        attributes.add(new AttributeDefinition("active", "쓰는가", "Other", ValueFormat.BOOLEAN));
        attributes.add(new AttributeDefinition("count", "수", "Other", ValueFormat.INTEGER));
        attributes.add(new AttributeDefinition("ratio", "비율", "Other", ValueFormat.DECIMAL));
        attributes.add(new AttributeDefinition("since", "시작", "Other", ValueFormat.DATE));
        attributes.add(new AttributeDefinition("title", "제목", "Other", ValueFormat.STRING));
        return new PackDefinition(packId, "이름표", 3, AT,
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null)),
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET)),
                attributes, List.of(), List.of(), List.of(rules));
    }
}
