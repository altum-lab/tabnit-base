package com.altumlab.tabnit.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * B-05 수용 기준: 영향 범위에 규칙을 적용해 해당하는 규칙을 고른다. 순서가 고정된다.
 *
 * <p>고르기까지다. 조치를 만들거나 저장하지 않는다 (D2 AD-219). 모든 이름과 값은 지어낸 것이다.
 * 자료는 core 의 저장 API 로 넣고, 영향 범위는 core 의 계산으로 얻는다. 시험마다 다른 Pack 식별자를 쓴다.
 * 판의 적용일은 D 이고, 영향 범위와 조건의 값은 D 의 전날과 D 로 본다.
 */
class RuleEvaluatorTest {

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

    // ── 고르기 ──────────────────────────────────────────────────

    @Test
    void 영향_대상에_거는_규칙은_대상_유형과_그_하위_유형의_영향_대상을_고른다() {
        String p = "e-basic";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        link(start, entity(p, "Other", "O-2"));
        link(start, entity(p, "SubOther", "S-1"));
        link(start, entity(p, "Third", "T-1"));
        PackDefinition pack = pack(p, rule("0001", 2, ALL, IMPACT, "Other"));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        assertThat(selection.failures()).isEmpty();
        // 유형이 Other 이거나 그 하위 유형(SubOther)인 것만이다. Third 는 아니다.
        assertThat(selection.matches()).containsExactly(
                new RuleMatch("0001", 2, IMPACT, p + "|Other|O-1", "Other"),
                new RuleMatch("0001", 2, IMPACT, p + "|Other|O-2", "Other"),
                new RuleMatch("0001", 2, IMPACT, p + "|SubOther|S-1", "SubOther"));
    }

    @Test
    void 변동_대상에_거는_규칙은_변동_대상_가운데서_고른다() {
        String p = "e-change";
        EntityRef base = entity(p, "Base", "B-1");
        EntityRef sub = entity(p, "Sub", "B-2");
        EntityRef other = entity(p, "Other", "O-1");
        link(base, other);
        PackDefinition pack = pack(p, rule("0001", 1, ALL, CHANGE, "Base"));

        // 변동 대상은 행이 만든 모든 개체다. 참조로만 나온 개체(other)도 든다.
        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, base, sub, other),
                scope(base, sub, other));

        // 변동 대상 자신이 대상이다. 영향 대상(없음)과 무관하다.
        assertThat(selection.matches()).containsExactly(new RuleMatch("0001", 1, CHANGE, p + "|Base|B-1", "Base"),
                new RuleMatch("0001", 1, CHANGE, p + "|Sub|B-2", "Sub"));
    }

    @Test
    void 변동의_종류가_맞는_규칙만_건다() {
        String p = "e-kind";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        PackDefinition pack = pack(p, rule("0001", 1, List.of(RuleChangeKind.ADDED), IMPACT, "Other"),
                rule("0002", 1, List.of(RuleChangeKind.CHANGED, RuleChangeKind.REMOVED), IMPACT, "Other"),
                rule("0003", 1, ALL, IMPACT, "Other"));

        assertThat(evaluator.select(pack, change(RuleChangeKind.ADDED, start), scope(start)).matches())
            .extracting(RuleMatch::ruleNo)
            .containsExactly("0001", "0003");
        assertThat(evaluator.select(pack, change(RuleChangeKind.REMOVED, start), scope(start)).matches())
            .extracting(RuleMatch::ruleNo)
            .containsExactly("0002", "0003");
    }

    @Test
    void 목록의_차례는_우선순위와_규칙_번호와_대상의_식별자_순이다() {
        String p = "e-order";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef near = entity(p, "Other", "O-9");
        EntityRef far = entity(p, "Other", "O-1");
        link(start, near);
        link(near, far);
        // 적힌 차례는 뒤섞여 있다. 우선순위가 같으면 규칙 번호의 글자 코드 순이다 (D2 AD-103).
        PackDefinition pack = pack(p, rule("0003", 1, ALL, IMPACT, "Other"), rule("0001", 2, ALL, IMPACT, "Other"),
                rule("0002", 1, ALL, CHANGE, "Base"));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        // 같은 규칙 안에서는 대상의 식별자 순이다. 깊이 순이 아니다: 먼 O-1 이 가까운 O-9 보다 앞이다 (D2 AD-222).
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, RuleMatch::priority, RuleMatch::targetEntityId)
            .containsExactly(tuple("0002", 1, p + "|Base|B-1"), tuple("0003", 1, p + "|Other|O-1"),
                    tuple("0003", 1, p + "|Other|O-9"), tuple("0001", 2, p + "|Other|O-1"),
                    tuple("0001", 2, p + "|Other|O-9"));
    }

    @Test
    void 해당하는_것이_없으면_비어_있다() {
        String p = "e-none";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef alone = entity(p, "Base", "B-2");
        link(start, entity(p, "Third", "T-1"));
        PackDefinition noRules = pack(p);
        PackDefinition otherType = pack(p, rule("0001", 1, ALL, IMPACT, "Other"));

        // 규칙이 없는 Pack, 해당하는 유형이 없는 영향 범위, 영향 대상이 하나도 없는 변동.
        assertThat(evaluator.select(noRules, change(RuleChangeKind.CHANGED, start), scope(start)).matches()).isEmpty();
        assertThat(evaluator.select(otherType, change(RuleChangeKind.CHANGED, start), scope(start)).matches()).isEmpty();
        RuleSelection none = evaluator.select(otherType, change(RuleChangeKind.CHANGED, alone), scope(alone));
        assertThat(none.matches()).isEmpty();
        assertThat(none.failures()).isEmpty();
    }

    // ── 조건 ───────────────────────────────────────────────────

    @Test
    void 조건은_속성의_형식에_맞춰_견준다() {
        String p = "e-compare";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef target = entity(p, "Other", "O-1");
        link(start, target);
        value(target, "count", "10");
        value(target, "ratio", "1.5");
        value(target, "since", "2026-03-01");
        value(target, "active", "true");
        value(target, "title", "가나다");

        // { 속성, 비교, 값, 해당하는가 }
        Object[][] cases = {
                // 정수는 수로 견준다. 글자로 견주면 "10" 이 "9" 보다 작다.
                { "count", RuleOperator.GREATER_THAN, List.of("9"), true },
                { "count", RuleOperator.GREATER_THAN, List.of("10"), false },
                { "count", RuleOperator.GREATER_THAN_OR_EQUAL, List.of("10"), true },
                { "count", RuleOperator.LESS_THAN, List.of("100"), true },
                { "count", RuleOperator.LESS_THAN_OR_EQUAL, List.of("9"), false },
                { "count", RuleOperator.EQUALS, List.of("10"), true },
                { "count", RuleOperator.NOT_EQUALS, List.of("10"), false },
                { "count", RuleOperator.NOT_EQUALS, List.of("11"), true },
                { "count", RuleOperator.IN, List.of("1", "10", "100"), true },
                { "count", RuleOperator.IN, List.of("1", "100"), false },
                // 소수는 값으로 견준다.
                { "ratio", RuleOperator.EQUALS, List.of("1.5"), true },
                { "ratio", RuleOperator.GREATER_THAN_OR_EQUAL, List.of("1.5"), true },
                { "ratio", RuleOperator.GREATER_THAN, List.of("1.49"), true },
                { "ratio", RuleOperator.LESS_THAN, List.of("1.5"), false },
                { "ratio", RuleOperator.LESS_THAN, List.of("10"), true },
                // 날짜는 날짜로 견준다.
                { "since", RuleOperator.LESS_THAN, List.of("2026-03-02"), true },
                { "since", RuleOperator.GREATER_THAN, List.of("2026-03-01"), false },
                { "since", RuleOperator.EQUALS, List.of("2026-03-01"), true },
                // 참·거짓과 글자.
                { "active", RuleOperator.EQUALS, List.of("true"), true },
                { "active", RuleOperator.EQUALS, List.of("false"), false },
                { "active", RuleOperator.NOT_EQUALS, List.of("false"), true },
                { "title", RuleOperator.EQUALS, List.of("가나다"), true },
                { "title", RuleOperator.EQUALS, List.of("가나다 "), false },
                { "title", RuleOperator.IN, List.of("가", "가나다"), true },
                // 값이 있는가.
                { "title", RuleOperator.HAS_VALUE, List.of(), true },
                { "title", RuleOperator.HAS_NO_VALUE, List.of(), false } };
        for (Object[] c : cases) {
            @SuppressWarnings("unchecked")
            List<String> ruleValues = (List<String>) c[2];
            PackDefinition pack = pack(p, rule("0001", 1, ALL, IMPACT, "Other",
                    new RuleCondition(IMPACT, null, (String) c[0], (RuleOperator) c[1], ruleValues)));

            RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

            assertThat(selection.failures()).isEmpty();
            assertThat(!selection.matches().isEmpty()).as(c[0] + " " + c[1] + " " + c[2]).isEqualTo(c[3]);
        }
    }

    @Test
    void 저장된_값의_표기가_달라도_값이_같으면_같다() {
        String p = "e-notation";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef target = entity(p, "Other", "O-1");
        link(start, target);
        // 정해진 표기가 아닌 값이 저장돼 있어도 값으로 견준다.
        value(target, "ratio", "1.50");
        value(target, "count", "007");
        PackDefinition pack = pack(p,
                rule("0001", 1, ALL, IMPACT, "Other", new RuleCondition(IMPACT, null, "ratio", RuleOperator.EQUALS,
                        List.of("1.5")), new RuleCondition(IMPACT, null, "count", RuleOperator.IN, List.of("7"))));

        assertThat(evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start)).matches()).hasSize(1);
    }

    @Test
    void 값이_없으면_값이_없다_말고는_어떤_비교도_만족하지_않는다() {
        String p = "e-missing";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        PackDefinition pack = pack(p,
                rule("0001", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.NOT_EQUALS, List.of("5"))),
                rule("0002", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.LESS_THAN, List.of("5"))),
                rule("0003", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.HAS_VALUE, List.of())),
                rule("0004", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.HAS_NO_VALUE, List.of())));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        // "다르다"도 만족하지 않는다 (D2 AD-104). 값이 없는 것은 평가 실패가 아니다.
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo).containsExactly("0004");
        assertThat(selection.failures()).isEmpty();
    }

    @Test
    void 조건의_값은_적용일의_값이고_없으면_전날의_값이다() {
        String p = "e-dates";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef changed = entity(p, "Other", "O-1");
        EntityRef ended = entity(p, "Other", "O-2");
        EntityRef began = entity(p, "Other", "O-3");
        EntityRef gone = entity(p, "Other", "O-4");
        for (EntityRef target : List.of(changed, ended, began, gone)) {
            link(start, target);
        }
        // O-1: 적용일에 5 에서 50 으로 바뀌었다. O-2: 적용일에 끝났다 (전날까지 50). O-3: 적용일에 처음 50.
        // O-4: 전날보다 앞에 끝났다.
        value(changed, "count", "5", START, D);
        value(changed, "count", "50", D, null);
        value(ended, "count", "50", START, D);
        value(began, "count", "50", D, null);
        value(gone, "count", "50", START, D.minusDays(1));
        PackDefinition pack = pack(p,
                rule("0001", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.EQUALS, List.of("50"))),
                rule("0002", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.EQUALS, List.of("5"))),
                rule("0003", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.HAS_NO_VALUE, List.of())));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        // 적용일의 값이 있으면 그것만 본다 (O-1 은 50 이지 5 가 아니다). 없으면 전날의 값 (O-2). 둘 다 없으면 값 없음 (O-4).
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, m -> m.targetEntityId().substring(p.length() + 1))
            .containsExactly(tuple("0001", "Other|O-1"), tuple("0001", "Other|O-2"), tuple("0001", "Other|O-3"),
                    tuple("0003", "Other|O-4"));
    }

    @Test
    void 영향_대상에_거는_규칙이_변동_대상을_보는_조건은_경로가_시작한_변동_대상을_본다() {
        String p = "e-start";
        EntityRef base = entity(p, "Base", "B-1");
        EntityRef third = entity(p, "Third", "T-1");
        EntityRef fromBase = entity(p, "Other", "O-1");
        EntityRef fromThird = entity(p, "Other", "O-2");
        link(base, fromBase);
        link(third, fromThird);
        value(base, "count", "3");
        PackDefinition pack = pack(p,
                // 변동 대상이 Base 일 때만.
                rule("0001", 1, ALL, IMPACT, "Other", new RuleCondition(CHANGE, "Base", null, null, List.of())),
                // 변동 대상의 수가 3 일 때만.
                rule("0002", 1, ALL, IMPACT, "Other",
                        new RuleCondition(CHANGE, null, "count", RuleOperator.EQUALS, List.of("3"))),
                // 영향 대상의 유형을 보는 조건. 하위 유형도 해당한다.
                rule("0003", 1, ALL, IMPACT, "Other", new RuleCondition(IMPACT, "Other", null, null, List.of())));

        // 한 변동 이벤트의 변동 대상이 둘이다 (D2 AD-64). O-1 은 B-1 에서, O-2 는 T-1 에서 닿는다.
        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, base, third), scope(base, third));

        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, m -> m.targetEntityId().substring(p.length() + 1))
            .containsExactly(tuple("0001", "Other|O-1"), tuple("0002", "Other|O-1"), tuple("0003", "Other|O-1"),
                    tuple("0003", "Other|O-2"));
    }

    @Test
    void 한_대상에_규칙이_여럿_걸리면_여럿이고_조건은_모두_만족해야_한다() {
        String p = "e-many-rules";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef target = entity(p, "Other", "O-1");
        link(start, target);
        value(target, "count", "10");
        value(target, "active", "true");
        RuleCondition big = new RuleCondition(IMPACT, null, "count", RuleOperator.GREATER_THAN, List.of("5"));
        RuleCondition on = new RuleCondition(IMPACT, null, "active", RuleOperator.EQUALS, List.of("true"));
        RuleCondition off = new RuleCondition(IMPACT, null, "active", RuleOperator.EQUALS, List.of("false"));
        PackDefinition pack = pack(p, rule("0001", 1, ALL, IMPACT, "Other", big), rule("0002", 1, ALL, IMPACT, "Other", big, on),
                rule("0003", 1, ALL, IMPACT, "Other", big, off), rule("0004", 1, ALL, IMPACT, "Other"));

        assertThat(evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start)).matches())
            .extracting(RuleMatch::ruleNo)
            .containsExactly("0001", "0002", "0004");
    }

    // ── 평가 실패 ────────────────────────────────────────────────

    @Test
    void 저장된_값이_형식과_맞지_않으면_그_규칙_전체가_실패이고_다른_규칙은_계속_평가한다() {
        String p = "e-fail";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef good = entity(p, "Other", "O-1");
        EntityRef bad = entity(p, "Other", "O-2");
        link(start, good);
        link(start, bad);
        value(good, "count", "10");
        // 정수 속성에 수가 아닌 값이 저장돼 있다. 자료와 정의가 어긋난 것이다.
        value(bad, "count", "열");
        value(good, "title", "이름");
        value(bad, "title", "이름");
        PackDefinition pack = pack(p,
                rule("0001", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.GREATER_THAN, List.of("5"))),
                rule("0002", 2, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "title", RuleOperator.HAS_VALUE, List.of())),
                // 값이 있는지만 보는 조건은 값을 읽지 않으므로 실패하지 않는다.
                rule("0003", 3, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.HAS_VALUE, List.of())));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        // 규칙 0001 은 멀쩡한 O-1 에 대해서도 아무것도 내지 않는다. 반쯤만 맞는 결과를 내지 않는다 (D2 AD-221).
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, m -> m.targetEntityId().substring(p.length() + 1))
            .containsExactly(tuple("0002", "Other|O-1"), tuple("0002", "Other|O-2"), tuple("0003", "Other|O-1"),
                    tuple("0003", "Other|O-2"));
        assertThat(selection.failures())
            .containsExactly(new RuleFailure("0001", "VALUE_FORMAT_MISMATCH", p + "|Other|O-2", "count", "열"));
    }

    @Test
    void 형식마다_맞지_않는_값을_실패로_알린다() {
        String p = "e-fail-formats";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef target = entity(p, "Other", "O-1");
        link(start, target);
        value(target, "count", "1.5");
        value(target, "ratio", "1e3");
        value(target, "since", "2026-02-30");
        value(target, "active", "예");
        PackDefinition pack = pack(p,
                rule("0001", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.EQUALS, List.of("1"))),
                rule("0002", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "ratio", RuleOperator.EQUALS, List.of("1000"))),
                rule("0003", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "since", RuleOperator.EQUALS, List.of("2026-03-01"))),
                rule("0004", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "active", RuleOperator.EQUALS, List.of("true"))));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        assertThat(selection.matches()).isEmpty();
        // 실패는 규칙 번호 순이다.
        assertThat(selection.failures()).extracting(RuleFailure::ruleNo, RuleFailure::attributeName, RuleFailure::value)
            .containsExactly(tuple("0001", "count", "1.5"), tuple("0002", "ratio", "1e3"),
                    tuple("0003", "since", "2026-02-30"), tuple("0004", "active", "예"));
    }

    @Test
    void 검증을_거치지_않은_틀린_규칙은_예외로_죽지_않고_실패로_알린다() {
        String p = "e-invalid";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef target = entity(p, "Other", "O-1");
        link(start, target);
        value(target, "count", "10");
        // Pack 을 읽을 때 걸러지는 모양이다. 손으로 만든 정의를 넘기면 여기까지 올 수 있다.
        PackDefinition pack = pack(p,
                rule("0001", 1, ALL, IMPACT, "Other", new RuleCondition(IMPACT, null, "count", RuleOperator.EQUALS, List.of())),
                rule("0002", 1, ALL, IMPACT, "Other", new RuleCondition(IMPACT, null, "count", null, List.of("1"))),
                rule("0003", 1, ALL, IMPACT, "Other", new RuleCondition(IMPACT, null, null, null, List.of())),
                rule("0004", 1, ALL, IMPACT, "Other", new RuleCondition(IMPACT, null, "count", RuleOperator.IN, List.of())),
                rule("0005", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.HAS_VALUE, List.of("1"))),
                rule("0006", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "nowhere", RuleOperator.HAS_VALUE, List.of())),
                rule("0007", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.EQUALS, List.of("열"))),
                // 정의에 없는 유형을 대상으로 한 규칙은 아무 데도 걸리지 않을 뿐이다.
                rule("0008", 1, ALL, IMPACT, "Nowhere"), rule("0009", 1, ALL, IMPACT, "Other"));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        assertThat(selection.matches()).extracting(RuleMatch::ruleNo).containsExactly("0009");
        assertThat(selection.failures()).extracting(RuleFailure::ruleNo, RuleFailure::code)
            .containsExactly(tuple("0001", "INVALID_RULE"), tuple("0002", "INVALID_RULE"), tuple("0003", "INVALID_RULE"),
                    tuple("0004", "INVALID_RULE"), tuple("0005", "INVALID_RULE"), tuple("0006", "UNKNOWN_ATTRIBUTE"),
                    tuple("0007", "RULE_VALUE_FORMAT_MISMATCH"));
    }

    @Test
    void 틀린_값이_있으면_다른_조건이_거짓이어도_그_규칙은_실패다() {
        String p = "e-fail-order";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef good = entity(p, "Other", "O-1");
        EntityRef bad = entity(p, "Other", "O-2");
        link(start, good);
        link(start, bad);
        value(good, "count", "10");
        value(good, "title", "x");
        value(bad, "count", "열");
        value(bad, "title", "y");
        value(good, "active", "true");
        value(bad, "active", "false");
        RuleCondition big = new RuleCondition(IMPACT, null, "count", RuleOperator.GREATER_THAN, List.of("5"));
        // O-2 는 다른 조건에서 이미 거짓이다. 그 조건의 속성 이름이 count 보다 앞이든(active) 뒤이든(title) 결과가 같다.
        PackDefinition pack = pack(p,
                rule("0001", 1, ALL, IMPACT, "Other", big,
                        new RuleCondition(IMPACT, null, "active", RuleOperator.EQUALS, List.of("true"))),
                rule("0002", 1, ALL, IMPACT, "Other", big,
                        new RuleCondition(IMPACT, null, "title", RuleOperator.EQUALS, List.of("x"))));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        // 조건의 차례(속성의 이름)에 실패 여부가 달라지지 않는다 (D2 AD-224).
        assertThat(selection.matches()).isEmpty();
        assertThat(selection.failures()).containsExactly(
                new RuleFailure("0001", "VALUE_FORMAT_MISMATCH", p + "|Other|O-2", "count", "열"),
                new RuleFailure("0002", "VALUE_FORMAT_MISMATCH", p + "|Other|O-2", "count", "열"));
    }

    @Test
    void 유형으로_걸러진_대상의_값은_읽지_않고_실패는_규칙마다_처음_걸린_하나다() {
        String p = "e-fail-first";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef first = entity(p, "Other", "O-1");
        EntityRef second = entity(p, "Other", "O-2");
        EntityRef third = entity(p, "Third", "T-1");
        for (EntityRef target : List.of(first, second, third)) {
            link(start, target);
            value(target, "count", "수가 아님 " + target.entityKey());
        }
        RuleCondition big = new RuleCondition(IMPACT, null, "count", RuleOperator.GREATER_THAN, List.of("5"));
        PackDefinition pack = pack(p,
                // 대상 유형이 Sub 인 규칙: 영향 대상 가운데 Sub 가 없다. 아무 값도 읽지 않으므로 실패도 없다.
                rule("0001", 1, ALL, IMPACT, "Sub", big),
                // 변동 대상이 Third 일 때만 거는 규칙: 유형을 보는 조건에서 모두 걸러진다. 실패가 없다.
                rule("0002", 1, ALL, IMPACT, "Other", big, new RuleCondition(CHANGE, "Third", null, null, List.of())),
                // 대상 유형이 Other 인 규칙: O-1 과 O-2 가 모두 틀렸다. 대상의 차례로 처음 걸린 O-1 하나만 담는다.
                rule("0003", 1, ALL, IMPACT, "Other", big));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        assertThat(selection.failures()).containsExactly(
                new RuleFailure("0003", "VALUE_FORMAT_MISMATCH", p + "|Other|O-1", "count", "수가 아님 O-1"));
    }

    @Test
    void 변동_대상의_값도_두_날짜로_보고_틀린_값은_변동_대상의_것으로_알린다() {
        String p = "e-change-values";
        EntityRef removed = entity(p, "Base", "B-1");
        EntityRef broken = entity(p, "Base", "B-2");
        link(removed, entity(p, "Other", "O-1"));
        // 삭제된 행의 변동 대상: 값이 적용일에 끝났다. 전날의 값으로 본다 (D2 AD-101).
        value(removed, "count", "3", START, D);
        value(broken, "count", "셋");
        RuleCondition three = new RuleCondition(CHANGE, null, "count", RuleOperator.EQUALS, List.of("3"));
        PackDefinition pack = pack(p, rule("0001", 1, ALL, IMPACT, "Other", three), rule("0002", 1, ALL, CHANGE, "Base", three));

        RuleSelection ok = evaluator.select(pack, change(RuleChangeKind.REMOVED, removed), scope(removed));
        RuleSelection failed = evaluator.select(pack, change(RuleChangeKind.CHANGED, broken), scope(broken));

        assertThat(ok.matches()).extracting(RuleMatch::ruleNo, RuleMatch::targetEntityType)
            .containsExactly(tuple("0001", "Other"), tuple("0002", "Base"));
        assertThat(failed.failures())
            .containsExactly(new RuleFailure("0002", "VALUE_FORMAT_MISMATCH", p + "|Base|B-2", "count", "셋"));
    }

    @Test
    void 여러_단계_아래의_유형과_상위_유형이_둘인_유형과_정의에_없는_유형() {
        String p = "e-types";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Deep", "D-1"));
        link(start, entity(p, "Both", "X-1"));
        link(start, entity(p, "Unknown", "U-1"));
        List<EntityTypeDefinition> types = List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                new EntityTypeDefinition("Other", "다른 것", List.of(), null),
                new EntityTypeDefinition("SubOther", "아래", List.of("Other"), null),
                new EntityTypeDefinition("Deep", "더 아래", List.of("SubOther"), null),
                new EntityTypeDefinition("Third", "셋째", List.of(), null),
                new EntityTypeDefinition("Both", "둘 다", List.of("Other", "Third"), null));
        PackDefinition pack = new PackDefinition(p, "이름표", 3, AT, types,
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET)),
                List.of(), List.of(), List.of(),
                List.of(rule("0001", 1, ALL, IMPACT, "Other"), rule("0002", 1, ALL, IMPACT, "Third"),
                        rule("0003", 1, ALL, IMPACT, "Other", new RuleCondition(IMPACT, "Third", null, null, List.of()))));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        // 두 단계 아래(Deep)도 Other 다. Both 는 Other 이면서 Third 다. 정의에 없는 유형의 개체는 어디에도 해당하지 않는다.
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, RuleMatch::targetEntityType)
            .containsExactly(tuple("0001", "Both"), tuple("0001", "Deep"), tuple("0002", "Both"), tuple("0003", "Both"));
        assertThat(selection.failures()).isEmpty();
    }

    @Test
    void 음수와_표기가_섞인_수와_아주_큰_수도_값으로_견준다() {
        String p = "e-numbers";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef target = entity(p, "Other", "O-1");
        link(start, target);
        value(target, "ratio", "2.0");
        value(target, "count", "-10");
        String big = "9".repeat(40);
        value(target, "title", big);
        PackDefinition pack = pack(p,
                rule("0001", 1, ALL, IMPACT, "Other", new RuleCondition(IMPACT, null, "ratio", RuleOperator.EQUALS, List.of("2"))),
                rule("0002", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.LESS_THAN, List.of("-9"))),
                rule("0003", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "ratio", RuleOperator.GREATER_THAN, List.of("-0.5"))),
                rule("0004", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.GREATER_THAN, List.of("-" + big))));

        assertThat(evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start)).matches())
            .extracting(RuleMatch::ruleNo)
            .containsExactly("0001", "0002", "0003", "0004");
    }

    @Test
    void 한_영향_대상에_두_변동_대상이_같은_깊이로_닿으면_남은_경로가_시작한_변동_대상을_본다() {
        String p = "e-two-starts";
        EntityRef one = entity(p, "Base", "B-1");
        EntityRef two = entity(p, "Base", "B-2");
        EntityRef shared = entity(p, "Other", "O-1");
        link(one, shared);
        link(two, shared);
        value(two, "count", "3");
        PackDefinition pack = pack(p, rule("0001", 1, ALL, IMPACT, "Other",
                new RuleCondition(CHANGE, null, "count", RuleOperator.EQUALS, List.of("3"))));

        // 대상마다 근거 경로는 하나다. 길이가 같으면 관계의 식별자가 앞선 경로가 남는다 (D2 AD-36): B-1 에서 온 것.
        // 조건은 그 경로가 시작한 B-1 을 본다 (D2 AD-64). B-2 의 수가 3 이어도 해당하지 않는다.
        assertThat(evaluator.select(pack, change(RuleChangeKind.CHANGED, one, two), scope(one, two)).matches()).isEmpty();
    }

    @Test
    void 유형의_위아래가_아주_깊거나_돌아와도_끝난다() {
        String p = "e-deep";
        EntityRef start = entity(p, "Base", "B-1");
        int depth = 3000;
        link(start, entity(p, "T" + depth, "deep"));
        link(start, entity(p, "LoopA", "loop"));
        List<EntityTypeDefinition> types = new ArrayList<>();
        types.add(new EntityTypeDefinition("Base", "바탕", List.of(), null));
        types.add(new EntityTypeDefinition("T0", "맨 위", List.of(), null));
        for (int i = 1; i <= depth; i++) {
            types.add(new EntityTypeDefinition("T" + i, "유형", List.of("T" + (i - 1)), null));
        }
        // 서로를 상위 유형으로 가리킨다. Pack 을 읽을 때 거부되지만 넘겨받아도 끝나야 한다.
        types.add(new EntityTypeDefinition("LoopA", "돌아옴", List.of("LoopB"), null));
        types.add(new EntityTypeDefinition("LoopB", "돌아옴", List.of("LoopA"), null));
        PackDefinition pack = new PackDefinition(p, "이름표", 3, AT, types,
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "T0", PackImpactDirection.SOURCE_TO_TARGET)),
                List.of(), List.of(), List.of(),
                List.of(rule("0001", 1, ALL, IMPACT, "T0"), rule("0002", 1, ALL, IMPACT, "LoopB"),
                        rule("0003", 1, ALL, IMPACT, "T1", new RuleCondition(IMPACT, "T2", null, null, List.of()))));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, RuleMatch::targetEntityType)
            .containsExactly(tuple("0001", "T" + depth), tuple("0002", "LoopA"), tuple("0003", "T" + depth));
    }

    // ── 입력 ───────────────────────────────────────────────────

    @Test
    void 없는_변동_대상과_다른_Pack의_변동_대상은_받지_않는다() {
        String p = "e-input";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef foreign = entity("e-input-other", "Base", "B-1");
        PackDefinition pack = pack(p, rule("0001", 1, ALL, CHANGE, "Base"));
        ImpactScope scope = scope(start);

        assertThatThrownBy(() -> evaluator.select(pack,
                new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(), List.of(p + "|Base|없는 키"), D), scope))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("없는 키");
        assertThatThrownBy(() -> evaluator.select(pack,
                new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(), List.of(start.id(), foreign.id()), D), scope))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("e-input-other");
        assertThatThrownBy(
                () -> evaluator.select(pack, new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(), List.of(), D), scope))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluator.select(null, change(RuleChangeKind.CHANGED, start), scope))
            .isInstanceOf(NullPointerException.class);
        // 저장소가 담지 못하는 적용일은 변동을 만들 때 막는다. 경계의 날짜는 받는다.
        assertThatThrownBy(() -> new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(), List.of(start.id()), LocalDate.MAX))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("적용일");
        for (LocalDate edge : List.of(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31))) {
            RuleSelection atEdge = evaluator.select(
                    pack(p, rule("0001", 1, ALL, CHANGE, "Base",
                            new RuleCondition(CHANGE, null, "count", RuleOperator.HAS_NO_VALUE, List.of()))),
                    new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(), List.of(start.id()), edge), scope);
            assertThat(atEdge.matches()).as(edge.toString()).hasSize(1);
        }
    }

    @Test
    void 잘못_부른_것을_잡고_계속해도_부르는_쪽의_트랜잭션이_깨지지_않는다() {
        String p = "e-outer";
        EntityRef start = entity(p, "Base", "B-1");
        PackDefinition pack = pack(p, rule("0001", 1, ALL, CHANGE, "Base"));
        ImpactScope scope = scope(start);
        org.springframework.transaction.support.TransactionTemplate outer = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(RulesTestDatabase.dataSource()));

        // 부르는 쪽이 자기 트랜잭션 안에서 규칙을 고르다가 잘못 부른 것을 알고, 그것을 적어 두고 끝낸다.
        outer.executeWithoutResult(status -> {
            assertThatThrownBy(() -> evaluator.select(pack,
                    new RuleChange("ev-1", RuleChangeKind.CHANGED, start.id(), List.of(p + "|Base|없는 키"), D), scope))
                .isInstanceOf(IllegalArgumentException.class);
            relations.saveEntity(new EntityRef(p, "Base", "적어 둔 것"), AT);
        });

        // 부르는 쪽의 저장이 남아 있다.
        assertThat(new com.altumlab.tabnit.core.EntityQuery(RulesTestDatabase.dataSource()).find(p + "|Base|적어 둔 것"))
            .isPresent();
    }

    @Test
    void 같은_입력에는_같은_결과이고_변동_대상을_적은_차례와_무관하다() {
        String p = "e-twice";
        EntityRef one = entity(p, "Base", "B-1");
        EntityRef two = entity(p, "Sub", "B-2");
        for (int i = 0; i < 6; i++) {
            EntityRef target = entity(p, i % 2 == 0 ? "Other" : "SubOther", "O-" + i);
            link(i < 3 ? one : two, target);
            value(target, "count", Integer.toString(i * 3));
        }
        PackDefinition pack = pack(p,
                rule("0002", 1, ALL, IMPACT, "Other",
                        new RuleCondition(IMPACT, null, "count", RuleOperator.GREATER_THAN_OR_EQUAL, List.of("6"))),
                rule("0001", 1, ALL, CHANGE, "Base"), rule("0003", 2, ALL, IMPACT, "SubOther"));
        ImpactScope scope = scope(one, two);

        RuleSelection first = evaluator.select(pack, change(RuleChangeKind.CHANGED, one, two), scope);
        RuleSelection second = evaluator.select(pack, change(RuleChangeKind.CHANGED, two, one), scope);

        assertThat(second).isEqualTo(first);
        assertThat(first.matches()).hasSize(2 + 4 + 3);
    }

    @Test
    void 영향_대상이_많아도_모두_본다() {
        String p = "e-large";
        EntityRef start = entity(p, "Base", "B-1");
        int count = 400;
        for (int i = 0; i < count; i++) {
            int n = (i * 7) % count;
            EntityRef target = entity(p, "Other", "O-" + (1000 + n));
            link(start, target);
            value(target, "count", Integer.toString(n));
        }
        PackDefinition pack = pack(p, rule("0001", 1, ALL, IMPACT, "Other",
                new RuleCondition(IMPACT, null, "count", RuleOperator.LESS_THAN, List.of("100"))));

        RuleSelection selection = evaluator.select(pack, change(RuleChangeKind.CHANGED, start), scope(start));

        assertThat(selection.matches()).hasSize(100);
        assertThat(selection.matches().get(0).targetEntityId()).isEqualTo(p + "|Other|O-1000");
        assertThat(selection.matches().get(99).targetEntityId()).isEqualTo(p + "|Other|O-1099");
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private EntityRef entity(String packId, String type, String key) {
        EntityRef entity = new EntityRef(packId, type, key);
        relations.saveEntity(entity, AT);
        return entity;
    }

    private void link(EntityRef source, EntityRef target) {
        relations.saveRelation(new NewRelation(source, target, "links", new ValidityPeriod(START, null), SOURCE), AT);
    }

    private void value(EntityRef entity, String attribute, String value) {
        value(entity, attribute, value, START, null);
    }

    private void value(EntityRef entity, String attribute, String value, LocalDate from, LocalDate to) {
        values.saveAttributeValue(new NewAttributeValue(entity, attribute, value, new ValidityPeriod(from, to), SOURCE), AT);
    }

    /** 변동 대상에서 출발한 영향 범위. 변동 이벤트의 영향 범위처럼 적용일 전날과 적용일로 본다 (D2 AD-100). */
    private ImpactScope scope(EntityRef... starts) {
        List<String> ids = new ArrayList<>();
        for (EntityRef start : starts) {
            ids.add(start.id());
        }
        return impact.impactScope(new ImpactRequest(ids, List.of(D.minusDays(1), D),
                Map.of("links", ImpactDirection.SOURCE_TO_TARGET), 3));
    }

    private static RuleChange change(RuleChangeKind kind, EntityRef... targets) {
        List<String> ids = new ArrayList<>();
        for (EntityRef target : targets) {
            ids.add(target.id());
        }
        return new RuleChange("ev-1", kind, targets[0].id(), ids, D);
    }

    private static RuleDefinition rule(String ruleNo, int priority, List<RuleChangeKind> kinds, RuleSubject appliesTo,
            String targetType, RuleCondition... conditions) {
        return new RuleDefinition(ruleNo, "규칙 " + ruleNo, priority, kinds, appliesTo, targetType, List.of(conditions),
                "할 일", 7);
    }

    /** 지어낸 작은 Pack 의 정의. 유형: Base ⊃ Sub, Other ⊃ SubOther, Third. 속성은 형식마다 하나. */
    private static PackDefinition pack(String packId, RuleDefinition... rules) {
        List<AttributeDefinition> attributes = new ArrayList<>();
        // 시험이 Base 와 Other 양쪽의 개체에 같은 이름의 속성을 넣는다. 지어낸 Pack 이라 유형의 구분을 두지 않는다.
        attributes.add(new AttributeDefinition("active", "쓰는가", "Other", ValueFormat.BOOLEAN));
        attributes.add(new AttributeDefinition("count", "수", "Other", ValueFormat.INTEGER));
        attributes.add(new AttributeDefinition("ratio", "비율", "Other", ValueFormat.DECIMAL));
        attributes.add(new AttributeDefinition("since", "시작", "Other", ValueFormat.DATE));
        attributes.add(new AttributeDefinition("title", "제목", "Other", ValueFormat.STRING));
        return new PackDefinition(packId, "이름표", 3, AT,
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null),
                        new EntityTypeDefinition("Sub", "아래", List.of("Base"), null),
                        new EntityTypeDefinition("SubOther", "다른 것의 아래", List.of("Other"), null),
                        new EntityTypeDefinition("Third", "셋째", List.of(), null)),
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET)),
                attributes, List.of(), List.of(), List.of(rules));
    }
}
