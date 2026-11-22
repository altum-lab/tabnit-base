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
 * B-14 (D2 AD-338 ~ AD-340): 조건이 규칙의 대상과 어느 관계로 이어진, 그 변동의 영향 범위 안의 대상을 본다.
 * 이어진 대상 가운데 하나라도 조건을 만족하면 걸린다.
 *
 * <p>어느 Pack 과도 무관한 일반 시험이다. 모든 이름과 값은 지어낸 것이다. 판의 적용일은 D 다.
 * 모양: Base 가 Other 로 이어지고 (links), Other 를 Third 가 가진다 (owns: Other → Third). 영향은 Base → Other → Third 로 퍼진다.
 */
class RuleEvaluatorLinkedTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final LocalDate START = LocalDate.parse("2026-01-01");

    private static final LocalDate D = LocalDate.parse("2026-06-01");

    private static final Provenance SOURCE = new Provenance("src", 1);

    private static final RuleSubject IMPACT = RuleSubject.IMPACT_TARGET;

    private static final RuleSubject CHANGE = RuleSubject.CHANGE_TARGET;

    private static final RuleSubject LINKED = RuleSubject.LINKED_TARGET;

    private static final List<RuleChangeKind> ALL = List.of(RuleChangeKind.values());

    private final RelationStore relations = new RelationStore(RulesTestDatabase.dataSource());

    private final AttributeValueStore values = new AttributeValueStore(RulesTestDatabase.dataSource());

    private final ImpactQuery impact = new ImpactQuery(RulesTestDatabase.dataSource());

    private final RuleEvaluator evaluator = new RuleEvaluator(RulesTestDatabase.dataSource());

    @Test
    void 이어진_대상_가운데_하나라도_만족하면_걸리고_영향_범위_밖의_것은_보지_않는다() {
        String p = "k-any";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef low = other(p, start, "O-1", "3");
        EntityRef high = other(p, start, "O-2", "9");
        EntityRef alone = other(p, start, "O-3", "2");
        // O-4 는 이 변동에 닿지 않았다. 값은 조건을 만족하지만 영향 범위 밖이다.
        EntityRef outside = entity(p, "Other", "O-4");
        value(outside, "count", "99");
        EntityRef both = entity(p, "Third", "T-1");
        EntityRef none = entity(p, "Third", "T-2");
        owns(low, both);
        owns(high, both);
        owns(alone, none);
        owns(outside, none);
        PackDefinition pack = pack(p, rule("0001", IMPACT, "Third", linked("owns", "count", RuleOperator.GREATER_THAN, "5")));

        RuleSelection selection = evaluator.select(pack, change(start), scope(start));

        assertThat(selection.failures()).isEmpty();
        // T-1 은 이어진 둘 가운데 O-2 가 만족한다. T-2 는 영향 범위 안의 O-3 이 만족하지 않는다 (O-4 는 보지 않는다).
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, RuleMatch::targetEntityId)
            .containsExactly(tuple("0001", both.id()));
    }

    @Test
    void 같은_관계로_적은_조건들은_이어진_대상_하나가_모두_만족해야_한다() {
        String p = "k-same";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef first = other(p, start, "O-1", "9");
        EntityRef second = other(p, start, "O-2", "1");
        value(first, "title", "가");
        value(second, "title", "나");
        EntityRef owner = entity(p, "Third", "T-1");
        owns(first, owner);
        owns(second, owner);
        // 수가 5 보다 큰 것은 O-1 이고 제목이 "나"인 것은 O-2 다. 둘을 함께 만족하는 대상은 없다.
        PackDefinition split = pack(p, rule("0001", IMPACT, "Third", linked("owns", "count", RuleOperator.GREATER_THAN, "5"),
                linked("owns", "title", RuleOperator.EQUALS, "나")));
        PackDefinition one = pack(p, rule("0001", IMPACT, "Third", linked("owns", "count", RuleOperator.GREATER_THAN, "5"),
                linked("owns", "title", RuleOperator.EQUALS, "가")));

        assertThat(evaluator.select(split, change(start), scope(start)).matches()).isEmpty();
        assertThat(evaluator.select(one, change(start), scope(start)).matches()).extracting(RuleMatch::targetEntityId)
            .containsExactly(owner.id());
    }

    @Test
    void 이어진_대상의_유형과_변동_대상의_속성과_판의_적용일도_본다() {
        String p = "k-kinds";
        EntityRef start = entity(p, "Base", "B-1");
        value(start, "count", "5");
        EntityRef plain = other(p, start, "O-1", "9");
        value(plain, "since", D.minusDays(1).toString());
        EntityRef sub = entity(p, "SubOther", "S-1");
        link(start, sub);
        value(sub, "count", "4");
        value(sub, "since", D.plusDays(1).toString());
        EntityRef ownerOfPlain = entity(p, "Third", "T-1");
        EntityRef ownerOfSub = entity(p, "Third", "T-2");
        owns(plain, ownerOfPlain);
        owns(sub, ownerOfSub);
        PackDefinition pack = pack(p,
                // 이어진 대상이 SubOther 인가.
                rule("0001", IMPACT, "Third", new RuleCondition(LINKED, "SubOther", null, null, List.of(),
                        RuleCompareTo.VALUE, null, null, "owns")),
                // 이어진 대상의 수가 변동 대상의 수보다 큰가.
                rule("0002", IMPACT, "Third", new RuleCondition(LINKED, null, "count", RuleOperator.GREATER_THAN, List.of(),
                        RuleCompareTo.ATTRIBUTE, CHANGE, "count", "owns")),
                // 이어진 대상의 날짜가 판의 적용일보다 앞인가.
                rule("0003", IMPACT, "Third", new RuleCondition(LINKED, null, "since", RuleOperator.LESS_THAN, List.of(),
                        RuleCompareTo.EFFECTIVE_DATE, null, null, "owns")));

        RuleSelection selection = evaluator.select(pack, change(start), scope(start));

        assertThat(selection.failures()).isEmpty();
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, RuleMatch::targetEntityId)
            .containsExactly(tuple("0001", ownerOfSub.id()), tuple("0002", ownerOfPlain.id()),
                    tuple("0003", ownerOfPlain.id()));
    }

    @Test
    void 이어진_대상이_없으면_걸리지_않고_실패도_아니다() {
        String p = "k-none";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef other = other(p, start, "O-1", "9");
        EntityRef owner = entity(p, "Third", "T-1");
        owns(other, owner);
        // links 로는 Third 에 이어진 것이 없다.
        PackDefinition pack = pack(p, rule("0001", IMPACT, "Third", linked("links", "count", RuleOperator.GREATER_THAN, "5")));

        RuleSelection selection = evaluator.select(pack, change(start), scope(start));

        assertThat(selection.matches()).isEmpty();
        assertThat(selection.failures()).isEmpty();
    }

    @Test
    void 적용일_전날까지_이어져_있던_것도_보고_적용일_뒤에_이어지는_것은_보지_않는다() {
        String p = "k-dates";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef ended = other(p, start, "O-1", "9");
        EntityRef later = other(p, start, "O-2", "9");
        EntityRef still = other(p, start, "O-3", "1");
        EntityRef ownerOfEnded = entity(p, "Third", "T-1");
        EntityRef ownerOfLater = entity(p, "Third", "T-2");
        // 가지는 관계가 적용일에 끝났다. 전날에는 유효했다 (두 날짜로 본다, D2 AD-100).
        relations.saveRelation(new NewRelation(ended, ownerOfEnded, "owns", new ValidityPeriod(START, D), SOURCE), AT);
        // 적용일 다음 날에야 시작한다. T-2 는 O-3 으로 영향 범위에 든다.
        relations.saveRelation(new NewRelation(later, ownerOfLater, "owns", new ValidityPeriod(D.plusDays(1), null), SOURCE), AT);
        owns(still, ownerOfLater);
        PackDefinition pack = pack(p, rule("0001", IMPACT, "Third", linked("owns", "count", RuleOperator.GREATER_THAN, "5")));

        RuleSelection selection = evaluator.select(pack, change(start), scope(start));

        assertThat(selection.matches()).extracting(RuleMatch::targetEntityId).containsExactly(ownerOfEnded.id());
    }

    @Test
    void 이어진_대상_하나의_값이_형식과_맞지_않으면_다른_것이_만족해도_그_규칙은_실패다() {
        String p = "k-bad";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef good = other(p, start, "O-1", "9");
        EntityRef bad = other(p, start, "O-2", "아홉");
        EntityRef owner = entity(p, "Third", "T-1");
        owns(good, owner);
        owns(bad, owner);
        PackDefinition pack = pack(p, rule("0001", IMPACT, "Third", linked("owns", "count", RuleOperator.GREATER_THAN, "5")),
                rule("0002", IMPACT, "Third"));

        RuleSelection selection = evaluator.select(pack, change(start), scope(start));

        // 어느 것을 먼저 보든 틀린 값이 하나라도 있으면 실패다 (D2 AD-224). 다른 규칙은 계속 평가한다.
        assertThat(selection.failures())
            .containsExactly(new RuleFailure("0001", "VALUE_FORMAT_MISMATCH", bad.id(), "count", "아홉"));
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo).containsExactly("0002");
    }

    @Test
    void 대상이_변동_대상인_규칙도_이어진_대상을_보고_변동_대상도_영향_범위_안이다() {
        String p = "k-change";
        EntityRef start = entity(p, "Base", "B-1");
        value(start, "title", "가");
        EntityRef other = other(p, start, "O-1", "9");
        // 둘째 변동 대상에 이어진 것은 수가 작다. 그 변동 대상에는 걸리지 않는다.
        EntityRef second = entity(p, "Base", "B-9");
        value(second, "title", "나");
        EntityRef lowOther = other(p, second, "O-9", "1");
        PackDefinition pack = pack(p,
                // 변동 대상(Base)에 건다. links 로 이어진 Other 가운데 영향 범위 안의 것의 수를 본다.
                rule("0001", CHANGE, "Base", linked("links", "count", RuleOperator.GREATER_THAN, "5")),
                // 영향 대상(Other)에 건다. links 로 이어진 반대쪽은 변동 대상인 Base 다.
                rule("0002", IMPACT, "Other", linked("links", "title", RuleOperator.EQUALS, "가")));

        RuleSelection selection = evaluator.select(pack, change(start, second), scope(start, second));

        assertThat(selection.failures()).isEmpty();
        // 0001 은 B-1 에만 (B-9 에 이어진 O-9 는 수가 1 이다). 0002 는 제목이 "가"인 B-1 에 이어진 O-1 에만.
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, RuleMatch::targetEntityId)
            .containsExactly(tuple("0001", start.id()), tuple("0002", other.id()));
        assertThat(scope(start, second).targets()).extracting(t -> t.entityId()).contains(lowOther.id());
    }

    @Test
    void 대상이_관계의_출발이든_도착이든_반대쪽_끝을_보고_관계_유형이_다른_조건은_모두_만족해야_한다() {
        String p = "k-ends";
        EntityRef start = entity(p, "Base", "B-1");
        value(start, "title", "가");
        EntityRef first = other(p, start, "O-1", "1");
        EntityRef second = other(p, start, "O-2", "1");
        EntityRef ownerOfFirst = entity(p, "Third", "T-1");
        EntityRef ownerOfSecond = entity(p, "Third", "T-2");
        owns(first, ownerOfFirst);
        owns(second, ownerOfSecond);
        value(ownerOfFirst, "title", "나");
        value(ownerOfSecond, "title", "다");
        PackDefinition pack = pack(p,
                // 대상 Other 는 owns 의 출발 끝이다. 도착 끝(Third)의 제목을 본다.
                rule("0001", IMPACT, "Other", linked("owns", "title", RuleOperator.EQUALS, "나")),
                // links 로 이어진 것(Base)의 제목과 owns 로 이어진 것(Third)의 제목을 모두 본다.
                rule("0002", IMPACT, "Other", linked("links", "title", RuleOperator.EQUALS, "가"),
                        linked("owns", "title", RuleOperator.EQUALS, "다")),
                rule("0003", IMPACT, "Other", linked("links", "title", RuleOperator.EQUALS, "없는 제목"),
                        linked("owns", "title", RuleOperator.EQUALS, "다")));

        RuleSelection selection = evaluator.select(pack, change(start), scope(start));

        assertThat(selection.failures()).isEmpty();
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, RuleMatch::targetEntityId)
            .containsExactly(tuple("0001", first.id()), tuple("0002", second.id()));
    }

    @Test
    void 이어진_것을_가리는_날짜의_경계() {
        String p = "k-edge";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef startsToday = other(p, start, "O-1", "9");
        EntityRef endedBefore = other(p, start, "O-2", "9");
        EntityRef low = other(p, start, "O-3", "1");
        EntityRef ownerToday = entity(p, "Third", "T-1");
        EntityRef ownerBefore = entity(p, "Third", "T-2");
        // 적용일 당일에 시작한 관계는 본다 (시작일 포함).
        relations.saveRelation(new NewRelation(startsToday, ownerToday, "owns", new ValidityPeriod(D, null), SOURCE), AT);
        // 적용일 전날에 끝난 관계는 전날에도 유효하지 않다 (종료일 제외). 보지 않는다. T-2 는 O-3 으로 영향 범위에 든다.
        relations.saveRelation(new NewRelation(endedBefore, ownerBefore, "owns", new ValidityPeriod(START, D.minusDays(1)), SOURCE),
                AT);
        owns(low, ownerBefore);
        PackDefinition pack = pack(p, rule("0001", IMPACT, "Third", linked("owns", "count", RuleOperator.GREATER_THAN, "5")));

        RuleSelection selection = evaluator.select(pack, change(start), scope(start));

        assertThat(selection.matches()).extracting(RuleMatch::targetEntityId).containsExactly(ownerToday.id());
    }

    @Test
    void 유형으로_걸러진_이어진_대상의_값은_읽지_않고_다른_조건이_거짓이어도_틀린_값은_실패다() {
        String p = "k-typed";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef plainBad = other(p, start, "O-1", "아홉");
        EntityRef sub = entity(p, "SubOther", "S-1");
        link(start, sub);
        value(sub, "count", "9");
        EntityRef owner = entity(p, "Third", "T-1");
        owns(plainBad, owner);
        owns(sub, owner);
        RuleCondition subOnly = new RuleCondition(LINKED, "SubOther", null, null, List.of(), RuleCompareTo.VALUE, null, null,
                "owns");
        PackDefinition typed = pack(p,
                rule("0001", IMPACT, "Third", subOnly, linked("owns", "count", RuleOperator.GREATER_THAN, "5")));
        // 대상의 제목은 없다 (조건이 거짓). 그래도 이어진 대상의 틀린 값은 실패로 알린다 (D2 AD-224).
        PackDefinition falseAndBad = pack(p, rule("0001", IMPACT, "Third",
                new RuleCondition(IMPACT, null, "title", RuleOperator.EQUALS, List.of("없는 제목")),
                linked("owns", "count", RuleOperator.GREATER_THAN, "5")));

        RuleSelection first = evaluator.select(typed, change(start), scope(start));
        RuleSelection second = evaluator.select(falseAndBad, change(start), scope(start));

        // O-1 은 SubOther 가 아니라 걸러진다. 틀린 값을 읽지 않으므로 실패가 아니다.
        assertThat(first.failures()).isEmpty();
        assertThat(first.matches()).extracting(RuleMatch::targetEntityId).containsExactly(owner.id());
        assertThat(second.matches()).isEmpty();
        assertThat(second.failures())
            .containsExactly(new RuleFailure("0001", "VALUE_FORMAT_MISMATCH", plainBad.id(), "count", "아홉"));
    }

    @Test
    void 이어진_대상의_값을_규칙의_대상의_값과도_견주고_변동_대상은_대상의_근거_경로가_시작한_것이다() {
        String p = "k-compare";
        EntityRef first = entity(p, "Base", "B-1");
        EntityRef second = entity(p, "Base", "B-2");
        value(first, "count", "5");
        // B-2 에는 수가 없다.
        EntityRef fromFirst = other(p, first, "O-1", "9");
        EntityRef fromSecond = other(p, second, "O-2", "9");
        EntityRef ownerFirst = entity(p, "Third", "T-1");
        EntityRef ownerSecond = entity(p, "Third", "T-2");
        owns(fromFirst, ownerFirst);
        owns(fromSecond, ownerSecond);
        value(ownerFirst, "count", "7");
        value(ownerSecond, "count", "10");
        PackDefinition pack = pack(p,
                // 이어진 대상의 수가 규칙의 대상(Third)의 수보다 큰가: T-1 (9 > 7) 만.
                rule("0001", IMPACT, "Third", new RuleCondition(LINKED, null, "count", RuleOperator.GREATER_THAN, List.of(),
                        RuleCompareTo.ATTRIBUTE, IMPACT, "count", "owns")),
                // 이어진 대상의 수가 변동 대상의 수보다 큰가: T-1 의 경로는 B-1 (수 5)에서, T-2 의 경로는 B-2 (수 없음)에서
                // 시작한다. 견주는 변동 대상은 대상의 근거 경로가 시작한 것이다 (D2 AD-64). T-2 는 값이 없어 걸리지 않는다.
                rule("0002", IMPACT, "Third", new RuleCondition(LINKED, null, "count", RuleOperator.GREATER_THAN, List.of(),
                        RuleCompareTo.ATTRIBUTE, CHANGE, "count", "owns")));

        RuleSelection selection = evaluator.select(pack, change(first, second), scope(first, second));

        assertThat(selection.failures()).isEmpty();
        assertThat(selection.matches()).extracting(RuleMatch::ruleNo, RuleMatch::targetEntityId)
            .containsExactly(tuple("0001", ownerFirst.id()), tuple("0002", ownerFirst.id()));
    }

    @Test
    void 검증을_거치지_않은_틀린_정의는_죽지_않고_실패로_알린다() {
        String p = "k-invalid";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef other = other(p, start, "O-1", "9");
        EntityRef owner = entity(p, "Third", "T-1");
        owns(other, owner);
        PackDefinition pack = pack(p,
                // 이어진 대상을 본다면서 관계 유형이 없다.
                rule("0001", IMPACT, "Third", new RuleCondition(LINKED, null, "count", RuleOperator.GREATER_THAN, List.of("5"))),
                // Pack 에 없는 관계 유형.
                rule("0002", IMPACT, "Third", linked("nothing", "count", RuleOperator.GREATER_THAN, "5")));

        RuleSelection selection = evaluator.select(pack, change(start), scope(start));

        assertThat(selection.matches()).isEmpty();
        assertThat(selection.failures()).extracting(RuleFailure::ruleNo, RuleFailure::code)
            .containsExactly(tuple("0001", "INVALID_RULE"), tuple("0002", "INVALID_RULE"));
    }

    @Test
    void 이어진_대상이_많아도_모두_보고_두_번_적용한_결과가_같다() {
        String p = "k-many";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef owner = entity(p, "Third", "T-1");
        EntityRef quiet = entity(p, "Third", "T-2");
        for (int i = 0; i < 60; i++) {
            // 마지막 하나만 조건을 만족한다.
            owns(other(p, start, "O-" + (100 + i), i == 59 ? "9" : "1"), owner);
            owns(other(p, start, "Q-" + (100 + i), "1"), quiet);
        }
        PackDefinition pack = pack(p, rule("0001", IMPACT, "Third", linked("owns", "count", RuleOperator.GREATER_THAN, "5")));

        RuleSelection first = evaluator.select(pack, change(start), scope(start));
        RuleSelection second = evaluator.select(pack, change(start), scope(start));

        assertThat(first.matches()).extracting(RuleMatch::targetEntityId).containsExactly(owner.id());
        assertThat(second).isEqualTo(first);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private static RuleCondition linked(String relation, String attribute, RuleOperator operator, String value) {
        return new RuleCondition(LINKED, null, attribute, operator, List.of(value), RuleCompareTo.VALUE, null, null, relation);
    }

    private static RuleDefinition rule(String ruleNo, RuleSubject appliesTo, String targetType, RuleCondition... conditions) {
        return new RuleDefinition(ruleNo, "규칙 " + ruleNo, 1, ALL, appliesTo, targetType, List.of(conditions), "할 일", 7);
    }

    private static RuleChange change(EntityRef... starts) {
        List<String> ids = new ArrayList<>();
        for (EntityRef start : starts) {
            ids.add(start.id());
        }
        return new RuleChange("ev-1", RuleChangeKind.CHANGED, starts[0].id(), ids, D, "s", List.of(), List.of());
    }

    private EntityRef entity(String packId, String type, String key) {
        EntityRef entity = new EntityRef(packId, type, key);
        relations.saveEntity(entity, AT);
        return entity;
    }

    /** start 에서 links 로 이어진 Other 하나. 수(count)를 함께 넣는다. */
    private EntityRef other(String packId, EntityRef start, String key, String count) {
        EntityRef other = entity(packId, "Other", key);
        link(start, other);
        value(other, "count", count);
        return other;
    }

    private void link(EntityRef source, EntityRef target) {
        relations.saveRelation(new NewRelation(source, target, "links", new ValidityPeriod(START, null), SOURCE), AT);
    }

    private void owns(EntityRef other, EntityRef owner) {
        relations.saveRelation(new NewRelation(other, owner, "owns", new ValidityPeriod(START, null), SOURCE), AT);
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
                Map.of("links", ImpactDirection.SOURCE_TO_TARGET, "owns", ImpactDirection.SOURCE_TO_TARGET), 3));
    }

    /** 지어낸 작은 Pack 의 정의. 유형: Base, Other ⊃ SubOther, Third. 관계: links (Base → Other), owns (Other → Third). */
    private static PackDefinition pack(String packId, RuleDefinition... rules) {
        List<AttributeDefinition> attributes = new ArrayList<>();
        attributes.add(new AttributeDefinition("count", "수", "Other", ValueFormat.INTEGER));
        attributes.add(new AttributeDefinition("since", "시작", "Other", ValueFormat.DATE));
        attributes.add(new AttributeDefinition("title", "제목", "Other", ValueFormat.STRING));
        return new PackDefinition(packId, "이름표", 3, AT,
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null),
                        new EntityTypeDefinition("SubOther", "다른 것의 아래", List.of("Other"), null),
                        new EntityTypeDefinition("Third", "셋째", List.of(), null)),
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET),
                        new RelationTypeDefinition("owns", "가진다", "Other", "Third", PackImpactDirection.SOURCE_TO_TARGET)),
                attributes, List.of(), List.of(), List.of(rules));
    }
}
