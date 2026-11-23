package com.altumlab.tabnit.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.altumlab.tabnit.core.AttributeValueStore;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.ImpactDirection;
import com.altumlab.tabnit.core.ImpactPath;
import com.altumlab.tabnit.core.ImpactQuery;
import com.altumlab.tabnit.core.ImpactRequest;
import com.altumlab.tabnit.core.ImpactScope;
import com.altumlab.tabnit.core.ImpactTarget;
import com.altumlab.tabnit.core.NewAttributeValue;
import com.altumlab.tabnit.core.NewRelation;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.Relation;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * B-06 수용 기준: 조치마다 근거가 되는 주 개체와 그 판, 기한, 우선순위가 붙는다.
 *
 * <p>조치는 넘겨받은 영향 범위와 함께 트랜잭션 하나로 저장된다 (D2 AD-209). 확정과 보류는 B-07 이다.
 * 모든 이름과 값은 지어낸 것이다. 시험마다 다른 Pack 식별자와 변동 이벤트 식별자를 쓴다.
 */
class ActionGeneratorTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant NOW = Instant.parse("2026-06-02T03:04:05.123456Z");

    private static final LocalDate START = LocalDate.parse("2026-01-01");

    private static final LocalDate D = LocalDate.parse("2026-06-01");

    private static final Provenance SOURCE = new Provenance("src", 1);

    private static final BasisEdition EDITION = new BasisEdition("src", 2);

    private static final RuleSubject IMPACT = RuleSubject.IMPACT_TARGET;

    private static final RuleSubject CHANGE = RuleSubject.CHANGE_TARGET;

    private static final List<RuleChangeKind> ALL = List.of(RuleChangeKind.values());

    private final JdbcTemplate jdbc = new JdbcTemplate(RulesTestDatabase.dataSource());

    private final RelationStore relations = new RelationStore(RulesTestDatabase.dataSource());

    private final AttributeValueStore values = new AttributeValueStore(RulesTestDatabase.dataSource());

    private final ImpactQuery impact = new ImpactQuery(RulesTestDatabase.dataSource());

    private final ActionGenerator generator = new ActionGenerator(RulesTestDatabase.dataSource());

    private final ActionRecords records = new ActionRecords(RulesTestDatabase.dataSource());

    // ── 수용 기준 ────────────────────────────────────────────────

    @Test
    void 조치마다_근거가_되는_주_개체와_그_판과_기한과_우선순위가_붙는다() {
        String p = "a-basic";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef near = entity(p, "Other", "O-1");
        EntityRef far = entity(p, "Other", "O-2");
        link(start, near);
        link(near, far);
        PackDefinition pack = pack(p, rule("0002", 2, IMPACT, "Other", 30), rule("0001", 1, CHANGE, "Base", 7));
        RuleChange change = change("a-basic|src|2|B-1", start);

        ActionGeneration result = generator.generate(pack, change, EDITION, scope(start), 3, NOW);

        assertThat(result.outcome()).isEqualTo(ActionGeneration.Outcome.STORED);
        assertThat(result.failures()).isEmpty();
        // 차례: 우선순위, 규칙 번호, 대상의 식별자 (D2 AD-222).
        assertThat(result.actions()).extracting(Action::ruleNo, Action::targetEntityId, Action::priority, Action::dueDate)
            .containsExactly(tuple("0001", start.id(), 1, D.plusDays(7)), tuple("0002", near.id(), 2, D.plusDays(30)),
                    tuple("0002", far.id(), 2, D.plusDays(30)));
        Action own = result.actions().get(0);
        Action other = result.actions().get(1);
        // 근거는 변동이 생긴 행의 주 개체와, 그 변동이 나온 입력 자료의 판이다 (D2 AD-120, AD-98).
        assertThat(result.actions()).allSatisfy(action -> {
            assertThat(action.basisEntityId()).isEqualTo(start.id());
            assertThat(action.basisInputSourceId()).isEqualTo("src");
            assertThat(action.basisEditionNo()).isEqualTo(2);
            assertThat(action.changeEventId()).isEqualTo("a-basic|src|2|B-1");
            assertThat(action.packId()).isEqualTo(p);
            assertThat(action.effectiveDate()).isEqualTo(D);
            assertThat(action.status()).isEqualTo(ActionStatus.PENDING);
            assertThat(action.createdAt()).isEqualTo(NOW);
        });
        // 규칙의 내용을 그때의 것으로 적어 둔다 (D2 AD-226).
        assertThat(own.ruleLabel()).isEqualTo("규칙 0001");
        assertThat(own.actionText()).isEqualTo("할 일 0001");
        assertThat(own.appliesTo()).isEqualTo(CHANGE);
        assertThat(own.targetEntityType()).isEqualTo("Base");
        assertThat(other.appliesTo()).isEqualTo(IMPACT);
        assertThat(other.targetEntityType()).isEqualTo("Other");
        // 식별자는 (변동 이벤트, 규칙, 대상)을 이은 글자다 (D2 AD-60). 값 안의 | 앞에는 \ 가 붙는다.
        assertThat(own.id()).isEqualTo("a-basic\\|src\\|2\\|B-1|0001|a-basic\\|Base\\|B-1");
        // 식별자 하나로 조치를 찾을 수 있게 그 해시를 유일한 열로 둔다 (D2 AD-230).
        assertThat(jdbc.queryForObject("select id from action where id_hash = ?", String.class, RulesHash.sha256Hex(own.id())))
            .isEqualTo(own.id());
        // 알려진 값: "abc" 의 SHA-256.
        assertThat(RulesHash.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        // 저장된 것을 읽으면 같다.
        assertThat(records.actions(change.changeEventId())).isEqualTo(result.actions());
    }

    @Test
    void 영향_범위가_근거_경로와_함께_저장된다() {
        String p = "a-scope";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef near = entity(p, "Other", "O-1");
        EntityRef far = entity(p, "Other", "O-2");
        link(start, near);
        link(near, far);
        ImpactScope scope = scope(start);
        RuleChange change = change("ev-scope", start);

        generator.generate(pack(p, rule("0001", 1, IMPACT, "Other", 7)), change, EDITION, scope, 3, NOW);

        StoredImpactScope stored = records.impactScope("ev-scope").orElseThrow();
        assertThat(stored.changeEventId()).isEqualTo("ev-scope");
        assertThat(stored.packId()).isEqualTo(p);
        assertThat(stored.asOfPrevious()).isEqualTo(D.minusDays(1));
        assertThat(stored.asOfEffective()).isEqualTo(D);
        assertThat(stored.maxDepth()).isEqualTo(3);
        assertThat(stored.truncated()).isFalse();
        assertThat(stored.storedAt()).isEqualTo(NOW);
        // 영향 대상의 차례는 깊이, 개체 유형, 개체 식별자다 (D2 AD-61).
        assertThat(stored.targets()).extracting(StoredImpactTarget::entityId, StoredImpactTarget::entityType,
                StoredImpactTarget::depth, StoredImpactTarget::pathStartEntityId, StoredImpactTarget::pathAsOf)
            .containsExactly(tuple(near.id(), "Other", 1, start.id(), D), tuple(far.id(), "Other", 2, start.id(), D));
        // 경로의 단계는 변동 대상 쪽부터의 차례이고, 관계의 내용을 값으로 담는다 (D2 AD-212).
        ImpactPath path = scope.targets().get(1).path();
        assertThat(stored.targets().get(1).steps()).extracting(StoredPathStep::stepNo, StoredPathStep::relationId,
                StoredPathStep::relationType, StoredPathStep::sourceEntityId, StoredPathStep::targetEntityId)
            .containsExactly(tuple(1, path.relations().get(0).id(), "links", start.id(), near.id()),
                    tuple(2, path.relations().get(1).id(), "links", near.id(), far.id()));
        assertThat(records.impactScope("없는 변동")).isEmpty();
        assertThat(records.actions("없는 변동")).isEmpty();
    }

    // ── 일반 시험 ────────────────────────────────────────────────

    @Test
    void 규칙에_걸린_것이_없어도_영향_대상이_없어도_영향_범위는_저장한다() {
        String p = "a-empty";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef alone = entity(p, "Base", "B-2");
        link(start, entity(p, "Other", "O-1"));

        // 규칙이 없는 Pack.
        ActionGeneration noRules = generator.generate(pack(p), change("ev-empty-1", start), EDITION, scope(start), 3, NOW);
        // 영향 대상이 하나도 없는 변동.
        ActionGeneration noTargets = generator.generate(pack(p, rule("0001", 1, IMPACT, "Other", 7)),
                change("ev-empty-2", alone), EDITION, scope(alone), 3, NOW);

        assertThat(noRules.outcome()).isEqualTo(ActionGeneration.Outcome.STORED);
        assertThat(noRules.actions()).isEmpty();
        assertThat(records.impactScope("ev-empty-1").orElseThrow().targets()).hasSize(1);
        assertThat(noTargets.outcome()).isEqualTo(ActionGeneration.Outcome.STORED);
        assertThat(noTargets.actions()).isEmpty();
        assertThat(records.impactScope("ev-empty-2").orElseThrow().targets()).isEmpty();
    }

    @Test
    void 이미_저장된_변동으로_다시_부르면_다시_계산하지_않고_저장된_것을_돌려준다() {
        String p = "a-again";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef target = entity(p, "Other", "O-1");
        link(start, target);
        RuleChange change = change("ev-again", start);
        ActionGeneration first = generator.generate(pack(p, rule("0001", 1, IMPACT, "Other", 7)), change, EDITION,
                scope(start), 3, NOW);

        // 그 사이에 자료가 늘고 규칙이 바뀌었다. 다른 시각에, 다른 판과 깊이로 다시 부른다.
        link(start, entity(p, "Other", "O-2"));
        value(target, "count", "수가 아님");
        PackDefinition changed = pack(p, rule("0001", 9, IMPACT, "Other", 99),
                rule("0002", 1, IMPACT, "Other", 1, new RuleCondition(IMPACT, null, "count", RuleOperator.GREATER_THAN, List.of("1"))));
        ActionGeneration second = generator.generate(changed, change, new BasisEdition("다른 자료", 9), scope(start), 5,
                NOW.plusSeconds(60));

        // 이미 만든 조치와 영향 범위는 그대로다 (D2 AD-119, AD-228). 실패할 규칙도 평가하지 않는다.
        assertThat(second.outcome()).isEqualTo(ActionGeneration.Outcome.ALREADY_STORED);
        assertThat(second.failures()).isEmpty();
        assertThat(second.actions()).isEqualTo(first.actions()).hasSize(1);
        assertThat(records.impactScope("ev-again").orElseThrow().targets()).hasSize(1);
        assertThat(records.impactScope("ev-again").orElseThrow().maxDepth()).isEqualTo(3);
        assertThat(count("action", "ev-again")).isEqualTo(1);
        // 같은 식별자의 변동이 다른 Pack 의 것으로 오면 저장된 것을 돌려주지 않고 잘못 부른 것으로 알린다.
        EntityRef foreign = entity("a-again-other", "Base", "B-1");
        assertThatThrownBy(() -> generator.generate(pack("a-again-other"), change("ev-again", foreign), EDITION, scope(foreign), 3,
                NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a-again");
    }

    @Test
    void 평가에_실패한_규칙이_있으면_아무것도_저장하지_않고_실패로_돌려준다() {
        String p = "a-failed";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef target = entity(p, "Other", "O-1");
        link(start, target);
        value(target, "count", "셋", START, D);
        PackDefinition pack = pack(p, rule("0001", 1, IMPACT, "Other", 7),
                rule("0002", 1, IMPACT, "Other", 7, new RuleCondition(IMPACT, null, "count", RuleOperator.GREATER_THAN, List.of("1"))));
        RuleChange change = change("ev-failed", start);

        ActionGeneration failed = generator.generate(pack, change, EDITION, scope(start), 3, NOW);

        // 멀쩡한 규칙 0001 의 조치도 저장하지 않는다 (D2 AD-227).
        assertThat(failed.outcome()).isEqualTo(ActionGeneration.Outcome.RULE_FAILED);
        assertThat(failed.actions()).isEmpty();
        assertThat(failed.failures())
            .containsExactly(new RuleFailure("0002", "VALUE_FORMAT_MISMATCH", target.id(), "count", "셋"));
        assertThat(records.impactScope("ev-failed")).isEmpty();
        assertThat(count("action", "ev-failed")).isZero();

        // 규칙을 고친 뒤 다시 실행하면 처음부터 계산해 저장한다 (D2 AD-154).
        ActionGeneration retried = generator.generate(pack(p, rule("0001", 1, IMPACT, "Other", 7)), change, EDITION,
                scope(start), 3, NOW);
        assertThat(retried.outcome()).isEqualTo(ActionGeneration.Outcome.STORED);
        assertThat(retried.actions()).hasSize(1);
    }

    @Test
    void 변동_대상이_대상인_조치는_근거_경로가_없고_근거는_언제나_행의_주_개체다() {
        String p = "a-own";
        EntityRef primary = entity(p, "Base", "B-1");
        EntityRef referenced = entity(p, "Base", "B-2");
        EntityRef target = entity(p, "Other", "O-1");
        link(referenced, target);
        RuleChange change = new RuleChange("ev-own", RuleChangeKind.CHANGED, primary.id(),
                List.of(primary.id(), referenced.id()), D);

        ActionGeneration result = generator.generate(
                pack(p, rule("0001", 1, CHANGE, "Base", 7), rule("0002", 2, IMPACT, "Other", 7)), change, EDITION,
                scope(primary, referenced), 3, NOW);

        // 행에 참조로만 나온 개체(B-2)가 대상이어도, 경로가 거기서 시작해도, 근거는 주 개체(B-1)다 (D2 AD-120).
        assertThat(result.actions()).extracting(Action::ruleNo, Action::targetEntityId, Action::basisEntityId)
            .containsExactly(tuple("0001", primary.id(), primary.id()), tuple("0001", referenced.id(), primary.id()),
                    tuple("0002", target.id(), primary.id()));
        // 변동 대상은 영향 대상으로 저장하지 않는다 (D2 AD-34). 경로는 영향 대상에만 있다.
        StoredImpactScope stored = records.impactScope("ev-own").orElseThrow();
        assertThat(stored.targets()).extracting(StoredImpactTarget::entityId, StoredImpactTarget::pathStartEntityId)
            .containsExactly(tuple(target.id(), referenced.id()));
    }

    @Test
    void 기한은_적용일에_달력의_날을_더한_날짜다() {
        String p = "a-due";
        EntityRef start = entity(p, "Base", "B-1");
        LocalDate leapEve = LocalDate.parse("2028-02-28");
        PackDefinition pack = pack(p, rule("0001", 1, CHANGE, "Base", 0), rule("0002", 1, CHANGE, "Base", 1),
                rule("0003", 1, CHANGE, "Base", 2), rule("0004", 1, CHANGE, "Base", 366));
        RuleChange change = new RuleChange("ev-due", RuleChangeKind.ADDED, start.id(), List.of(start.id()), leapEve);

        ActionGeneration result = generator.generate(pack, change, EDITION, scopeAt(leapEve, start), 3, NOW);

        // 0일이면 적용일 그날이다. 윤년의 2월 29일을 하루로 센다. 주말과 공휴일을 따지지 않는다 (D2 AD-99).
        assertThat(result.actions()).extracting(Action::dueDate)
            .containsExactly(leapEve, LocalDate.parse("2028-02-29"), LocalDate.parse("2028-03-01"),
                    LocalDate.parse("2029-02-28"));
    }

    // B-10 (D2 AD-371): 평가하는 도중에 저장소에서 읽지 못한 것도 저장소의 실패다. 정의와 자료가 어긋난 것이 아니다.
    @Test
    void 평가하는_도중에_저장소에서_읽지_못하면_저장소의_실패로_알리고_아무것도_저장하지_않는다() {
        String p = "a-read-fail";
        EntityRef start = entity(p, "Base", "B-1");
        RuleChange change = new RuleChange("ev-read-fail", RuleChangeKind.ADDED, start.id(), List.of(start.id()), D);
        PackDefinition pack = pack(p, rule("0001", 1, CHANGE, "Base", 0));
        // 영향 범위는 미리 계산해 둔다. 실패는 그 뒤, 규칙을 평가하려고 저장소를 읽을 때 난다.
        var scope = scopeAt(D, start);
        // core 의 테이블을 잠깐 읽을 수 없게 한다: 변동이 생긴 행의 주 개체를 읽다가 실패한다.
        jdbc.execute("alter table entity rename to entity_unreadable");
        try {
            assertThatThrownBy(() -> generator.generate(pack, change, EDITION, scope, 3, NOW))
                .isInstanceOf(ActionStorageException.class)
                .hasMessageContaining("ev-read-fail")
                .hasMessageContaining("저장소");
        }
        finally {
            jdbc.execute("alter table entity_unreadable rename to entity");
        }
        assertThat(records.impactScope("ev-read-fail")).isEmpty();
        // 저장소가 돌아오면 같은 변동을 다시 실행해 저장한다.
        assertThat(generator.generate(pack, change, EDITION, scopeAt(D, start), 3, NOW).outcome())
            .isEqualTo(ActionGeneration.Outcome.STORED);
    }

    @Test
    void 기한이_저장소가_담는_날짜를_넘으면_그_규칙의_실패다() {
        String p = "a-due-far";
        EntityRef start = entity(p, "Base", "B-1");
        LocalDate last = LocalDate.of(9999, 12, 31);
        RuleChange change = new RuleChange("ev-due-far", RuleChangeKind.ADDED, start.id(), List.of(start.id()), last);
        PackDefinition pack = pack(p, rule("0001", 1, CHANGE, "Base", 0), rule("0002", 1, CHANGE, "Base", 1),
                rule("0003", 1, CHANGE, "Base", Integer.MAX_VALUE), rule("0004", 1, CHANGE, "Third", Integer.MAX_VALUE));

        ActionGeneration result = generator.generate(pack, change, EDITION, scopeAt(last, start), 3, NOW);

        // 걸린 것이 없는 규칙 0004 는 기한을 셈하지 않으므로 실패가 아니다.
        assertThat(result.outcome()).isEqualTo(ActionGeneration.Outcome.RULE_FAILED);
        assertThat(result.failures()).extracting(RuleFailure::ruleNo, RuleFailure::code)
            .containsExactly(tuple("0002", "DUE_DATE_OUT_OF_RANGE"), tuple("0003", "DUE_DATE_OUT_OF_RANGE"));
        // 틀린 것은 개체가 아니라 규칙에 적힌 일수다: 걸린 개체는 비우고 일수를 값으로 알린다 (D7 5절, D2 AD-371).
        assertThat(result.failures()).extracting(RuleFailure::entityId, RuleFailure::attributeName, RuleFailure::value)
            .containsExactly(tuple(null, null, "1"), tuple(null, null, String.valueOf(Integer.MAX_VALUE)));
        assertThat(records.impactScope("ev-due-far")).isEmpty();
        // 마지막 날에 0일인 규칙만 있으면 저장된다.
        assertThat(generator.generate(pack(p, rule("0001", 1, CHANGE, "Base", 0)), change, EDITION, scopeAt(last, start), 3, NOW)
            .actions()).extracting(Action::dueDate).containsExactly(last);
    }

    @Test
    void 저장하다_실패하면_영향_범위도_조치도_남지_않는다() {
        String p = "a-atomic";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef target = entity(p, "Other", "O-1");
        link(start, target);
        ImpactTarget real = scope(start).targets().get(0);
        Relation relation = real.path().relations().get(0);
        // 관계 유형이 빈 글자인 경로. core 는 이런 것을 내지 않는다. 영향 범위와 영향 대상을 넣은 뒤 단계에서 저장소가 거부한다.
        Relation blank = new Relation(relation.id(), relation.packId(), relation.sourceEntityId(), relation.targetEntityId(), "",
                relation.period(), relation.provenance(), relation.recordedAt());
        ImpactScope broken = new ImpactScope(List.of(new ImpactTarget(real.entityId(), real.entityType(), 1,
                new ImpactPath(start.id(), D, List.of(blank)))), Map.of("Other", 1), Map.of(1, 1), false);

        assertThatThrownBy(() -> generator.generate(pack(p, rule("0001", 1, IMPACT, "Other", 7)), change("ev-atomic", start),
                EDITION, broken, 3, NOW))
            .isInstanceOf(ActionStorageException.class)
            .hasMessageContaining("ev-atomic");

        assertThat(records.impactScope("ev-atomic")).isEmpty();
        assertThat(count("impact_target", "ev-atomic")).isZero();
        assertThat(count("action", "ev-atomic")).isZero();
    }

    @Test
    void 검증을_거치지_않은_틀린_규칙은_저장소의_오류가_아니라_그_규칙의_실패다() {
        String p = "a-invalid";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        // Pack 을 읽을 때 걸러지는 모양이다. 손으로 만든 정의에는 올 수 있다.
        PackDefinition pack = pack(p, rule("0001", 1, IMPACT, "Other", 7),
                new RuleDefinition("0002", "규칙", 1, ALL, IMPACT, "Other", List.of(), "할 일\u0000", 7),
                new RuleDefinition("0003", "규칙\uD800", 1, ALL, IMPACT, "Other", List.of(), "할 일", 7),
                new RuleDefinition("0004", "", 1, ALL, IMPACT, "Other", List.of(), "할 일", 7),
                new RuleDefinition("0005", "규칙", 1, ALL, IMPACT, "Other", List.of(), "", 7),
                rule("0006", 1, IMPACT, "Other", 7), rule("0006", 2, IMPACT, "Other", 9),
                // 평가에서 이미 실패한 규칙이 번호까지 겹쳐도 실패는 하나만 알린다.
                rule("0008", 1, IMPACT, "Other", 7, new RuleCondition(IMPACT, null, "nowhere", RuleOperator.HAS_VALUE, List.of())),
                rule("0008", 1, IMPACT, "Other", 7, new RuleCondition(IMPACT, null, "nowhere", RuleOperator.HAS_VALUE, List.of())),
                // 걸린 것이 없는 규칙은 문구를 보지 않는다.
                new RuleDefinition("0007", "", 1, ALL, IMPACT, "Third", List.of(), "", 7));

        ActionGeneration result = generator.generate(pack, change("ev-invalid", start), EDITION, scope(start), 3, NOW);

        assertThat(result.outcome()).isEqualTo(ActionGeneration.Outcome.RULE_FAILED);
        assertThat(result.failures()).extracting(RuleFailure::ruleNo, RuleFailure::code)
            .containsExactly(tuple("0002", "INVALID_RULE"), tuple("0003", "INVALID_RULE"), tuple("0004", "INVALID_RULE"),
                    tuple("0005", "INVALID_RULE"), tuple("0006", "INVALID_RULE"), tuple("0008", "UNKNOWN_ATTRIBUTE"));
        assertThat(records.impactScope("ev-invalid")).isEmpty();
        // 이모지와 제어 글자는 그대로 저장되고 그대로 읽힌다.
        String odd = "할 일 \uD83D\uDE00 \t\n\u0001 | \\";
        ActionGeneration fine = generator.generate(
                pack(p, new RuleDefinition("0001", odd, 1, ALL, IMPACT, "Other", List.of(), odd, 7)),
                change("ev-invalid|\uD83D\uDE00\\", start), EDITION, scope(start), 3, NOW);
        assertThat(records.actions("ev-invalid|\uD83D\uDE00\\")).isEqualTo(fine.actions()).hasSize(1);
        assertThat(fine.actions().get(0).actionText()).isEqualTo(odd);
    }

    @Test
    void 판의_적용일에_끝나거나_시작하는_관계의_경로는_닿은_날짜와_함께_저장된다() {
        String p = "a-dates";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef ended = entity(p, "Other", "O-1");
        EntityRef begun = entity(p, "Other", "O-2");
        EntityRef gone = entity(p, "Other", "O-3");
        // 적용일에 끝난 관계(전날에는 유효), 적용일에 시작한 관계, 전날에 이미 끝난 관계.
        relations.saveRelation(new NewRelation(start, ended, "links", new ValidityPeriod(START, D), SOURCE), AT);
        relations.saveRelation(new NewRelation(start, begun, "links", new ValidityPeriod(D, null), SOURCE), AT);
        relations.saveRelation(new NewRelation(start, gone, "links", new ValidityPeriod(START, D.minusDays(1)), SOURCE), AT);

        ActionGeneration result = generator.generate(pack(p, rule("0001", 1, IMPACT, "Other", 7)), change("ev-dates", start),
                EDITION, scope(start), 3, NOW);

        // 시작일은 포함, 종료일은 제외다. 없어진 것의 영향은 전날의 관계로 찾는다 (D2 AD-100).
        assertThat(records.impactScope("ev-dates").orElseThrow().targets())
            .extracting(StoredImpactTarget::entityId, StoredImpactTarget::pathAsOf)
            .containsExactly(tuple(ended.id(), D.minusDays(1)), tuple(begun.id(), D));
        assertThat(result.actions()).extracting(Action::targetEntityId).containsExactly(ended.id(), begun.id());
    }

    @Test
    void 최대_깊이에서_멈춘_표시와_마이크로초보다_세밀한_시각() {
        String p = "a-truncated";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef near = entity(p, "Other", "O-1");
        link(start, near);
        link(near, entity(p, "Other", "O-2"));
        ImpactScope shallow = impact.impactScope(new ImpactRequest(List.of(start.id()), List.of(D.minusDays(1), D),
                Map.of("links", ImpactDirection.SOURCE_TO_TARGET), 1));
        assertThat(shallow.truncated()).isTrue();
        Instant fine = Instant.parse("2026-06-02T03:04:05.123456789Z");

        ActionGeneration result = generator.generate(pack(p, rule("0001", 1, IMPACT, "Other", 7)), change("ev-truncated", start),
                EDITION, shallow, 1, fine);

        StoredImpactScope stored = records.impactScope("ev-truncated").orElseThrow();
        assertThat(stored.truncated()).isTrue();
        assertThat(stored.maxDepth()).isEqualTo(1);
        // 마이크로초로 자른다. 돌려준 값과 저장된 값이 같다.
        Instant cut = Instant.parse("2026-06-02T03:04:05.123456Z");
        assertThat(stored.storedAt()).isEqualTo(cut);
        assertThat(result.actions()).extracting(Action::createdAt).containsExactly(cut);
        assertThat(records.actions("ev-truncated")).isEqualTo(result.actions());
    }

    @Test
    void 같은_변동을_여러_곳에서_함께_저장해도_한_번만_저장된다() throws Exception {
        String p = "a-race";
        EntityRef start = entity(p, "Base", "B-1");
        for (int i = 0; i < 20; i++) {
            link(start, entity(p, "Other", "O-" + (10 + i)));
        }
        PackDefinition pack = pack(p, rule("0001", 1, IMPACT, "Other", 7));
        ImpactScope scope = scope(start);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 10; round++) {
                RuleChange change = change("ev-race-" + round, start);
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                List<java.util.concurrent.Future<ActionGeneration>> futures = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    futures.add(pool.submit(() -> {
                        go.await();
                        return generator.generate(pack, change, EDITION, scope, 3, NOW);
                    }));
                }
                go.countDown();
                int stored = 0;
                for (java.util.concurrent.Future<ActionGeneration> future : futures) {
                    ActionGeneration result = future.get();
                    stored += result.outcome() == ActionGeneration.Outcome.STORED ? 1 : 0;
                    assertThat(result.outcome()).isNotEqualTo(ActionGeneration.Outcome.RULE_FAILED);
                    assertThat(result.actions()).hasSize(20);
                }
                assertThat(stored).isEqualTo(1);
                assertThat(count("action", change.changeEventId())).isEqualTo(20);
                assertThat(count("impact_target", change.changeEventId())).isEqualTo(20);
            }
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 부르는_쪽의_트랜잭션이_되돌려지면_저장한_것도_함께_사라진다() {
        String p = "a-rollback";
        EntityRef start = entity(p, "Base", "B-1");
        link(start, entity(p, "Other", "O-1"));
        PackDefinition pack = pack(p, rule("0001", 1, IMPACT, "Other", 7));
        ImpactScope scope = scope(start);
        org.springframework.transaction.support.TransactionTemplate outer = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(RulesTestDatabase.dataSource()));

        outer.executeWithoutResult(status -> {
            assertThat(generator.generate(pack, change("ev-rollback", start), EDITION, scope, 3, NOW).outcome())
                .isEqualTo(ActionGeneration.Outcome.STORED);
            status.setRollbackOnly();
        });

        assertThat(records.impactScope("ev-rollback")).isEmpty();
        assertThat(count("action", "ev-rollback")).isZero();
    }

    @Test
    void 잘못_부른_것은_오류로_알리고_아무것도_저장하지_않는다() {
        String p = "a-wrong";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef target = entity(p, "Other", "O-1");
        EntityRef foreign = entity("a-wrong-other", "Base", "B-1");
        link(start, target);
        PackDefinition pack = pack(p, rule("0001", 1, IMPACT, "Other", 7));
        ImpactScope scope = scope(start);
        RuleChange change = change("ev-wrong", start);

        assertThatThrownBy(() -> generator.generate(null, change, EDITION, scope, 3, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.generate(pack, null, EDITION, scope, 3, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.generate(pack, change, null, scope, 3, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.generate(pack, change, EDITION, null, 3, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.generate(pack, change, EDITION, scope, 3, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.generate(pack, change, EDITION, scope, 0, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("최대 깊이");
        // 영향 범위가 넘겨받은 최대 깊이보다 깊다.
        link(target, entity(p, "Other", "O-2"));
        assertThatThrownBy(() -> generator.generate(pack, change, EDITION, scope(start), 1, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("최대 깊이");
        // 입력 자료와 판 번호.
        assertThatThrownBy(() -> new BasisEdition(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BasisEdition("src", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BasisEdition(null, 1)).isInstanceOf(NullPointerException.class);
        // 주 개체가 저장소에 없거나 다른 Pack 의 것이다.
        assertThatThrownBy(() -> generator.generate(pack,
                new RuleChange("ev-wrong", RuleChangeKind.CHANGED, p + "|Base|없는 키", List.of(start.id()), D), EDITION, scope, 3, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("주 개체");
        assertThatThrownBy(() -> generator.generate(pack,
                new RuleChange("ev-wrong", RuleChangeKind.CHANGED, foreign.id(), List.of(start.id()), D), EDITION, scope, 3, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("주 개체");
        // 주 개체가 변동 대상에 없다.
        EntityRef outsider = entity(p, "Base", "B-9");
        assertThatThrownBy(() -> generator.generate(pack,
                new RuleChange("ev-wrong", RuleChangeKind.CHANGED, outsider.id(), List.of(start.id()), D), EDITION, scope, 3, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("변동 대상에 없습니다");
        // 변동 대상이 영향 대상으로 들어 있다: 변동 대상 전부에서 출발한 영향 범위가 아니다.
        assertThatThrownBy(() -> generator.generate(pack,
                new RuleChange("ev-wrong", RuleChangeKind.CHANGED, start.id(), List.of(start.id(), target.id()), D), EDITION, scope,
                3, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("변동 대상입니다");
        // 저장할 수 없는 글자가 든 식별자.
        for (String bad : List.of("ev\u0000", "ev\uD800x", "ev\uDC00")) {
            assertThatThrownBy(() -> generator.generate(pack, change(bad, start), EDITION, scope, 3, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("저장할 수 없는 글자");
        }
        assertThatThrownBy(() -> generator.generate(pack, change("ev-wrong", start), new BasisEdition("src\u0000", 1), scope, 3, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        // 적용일이 받는 첫 날이면 그 전날을 담을 수 없다.
        assertThatThrownBy(() -> generator.generate(pack,
                new RuleChange("ev-wrong", RuleChangeKind.CHANGED, start.id(), List.of(start.id()), LocalDate.of(1, 1, 1)), EDITION,
                new ImpactScope(List.of(), Map.of(), Map.of(), false), 3, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("0001-01-02");
        // 영향 범위가 이 변동의 두 날짜(적용일 전날, 적용일)로 계산한 것이 아니다.
        assertThatThrownBy(() -> generator.generate(pack, change, EDITION, scopeAt(D.plusDays(5), start), 3, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("기준 날짜");
        // 같은 영향 대상이 두 번 들어 있다.
        ImpactTarget one = scope.targets().get(0);
        ImpactScope doubled = new ImpactScope(List.of(one, one), Map.of("Other", 2), Map.of(1, 2), false);
        assertThatThrownBy(() -> generator.generate(pack, change, EDITION, doubled, 3, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("두 번");
        // 근거 경로가 변동 대상이 아닌 개체에서 시작하거나, 시작이 없다.
        for (String wrongStart : java.util.Arrays.asList(target.id(), null)) {
            ImpactScope elsewhere = new ImpactScope(List.of(new ImpactTarget(one.entityId(), one.entityType(), 1,
                    new ImpactPath(wrongStart, D, one.path().relations()))), Map.of("Other", 1), Map.of(1, 1), false);
            assertThatThrownBy(() -> generator.generate(pack, change, EDITION, elsewhere, 3, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시작합니다");
        }
        // 깊이와 근거 경로의 길이가 다르다.
        ImpactScope lying = new ImpactScope(List.of(new ImpactTarget(one.entityId(), one.entityType(), 2, one.path())),
                Map.of("Other", 1), Map.of(2, 1), false);
        assertThatThrownBy(() -> generator.generate(pack, change, EDITION, lying, 3, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("근거 경로");
        assertThatThrownBy(() -> records.impactScope(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> records.actions(null)).isInstanceOf(NullPointerException.class);
        // 깨진 글자가 든 식별자로 읽으면 다른 식별자의 것이 읽히지 않고 오류다.
        assertThatThrownBy(() -> records.actions("ev\uD800")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> records.impactScope("ev\uD800")).isInstanceOf(IllegalArgumentException.class);

        assertThat(records.impactScope("ev-wrong")).isEmpty();
        assertThat(count("action", "ev-wrong")).isZero();
    }

    @Test
    void 잘못_부른_것을_잡고_계속해도_부르는_쪽의_트랜잭션이_깨지지_않는다() {
        String p = "a-outer";
        EntityRef start = entity(p, "Base", "B-1");
        PackDefinition pack = pack(p, rule("0001", 1, CHANGE, "Base", 7));
        ImpactScope scope = scope(start);
        org.springframework.transaction.support.TransactionTemplate outer = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(RulesTestDatabase.dataSource()));

        outer.executeWithoutResult(status -> {
            assertThatThrownBy(() -> generator.generate(pack,
                    new RuleChange("ev-outer", RuleChangeKind.CHANGED, p + "|Base|없는 키", List.of(start.id()), D), EDITION,
                    scope, 3, NOW))
                .isInstanceOf(IllegalArgumentException.class);
            relations.saveEntity(new EntityRef(p, "Base", "적어 둔 것"), AT);
        });

        assertThat(new com.altumlab.tabnit.core.EntityQuery(RulesTestDatabase.dataSource()).find(p + "|Base|적어 둔 것"))
            .isPresent();
    }

    @Test
    void 식별자가_아주_길어도_저장된다() {
        String p = "a-long";
        // 키 하나가 200자인 한글이다. 식별자를 그대로 유일 제약에 넣으면 색인의 한계에 걸린다.
        EntityRef start = entity(p, "Base", "가".repeat(200));
        EntityRef target = entity(p, "Other", "나".repeat(200));
        link(start, target);
        String eventId = p + "|src|2|" + "다".repeat(200);

        ActionGeneration result = generator.generate(pack(p, rule("0001", 1, IMPACT, "Other", 7)), change(eventId, start),
                EDITION, scope(start), 3, NOW);

        assertThat(result.actions()).hasSize(1);
        assertThat(records.actions(eventId)).isEqualTo(result.actions());
        assertThat(records.impactScope(eventId).orElseThrow().targets().get(0).entityId()).isEqualTo(target.id());
    }

    @Test
    void 같은_자료의_두_변동은_식별자만_다른_같은_결과를_낸다() {
        String p = "a-twice";
        EntityRef start = entity(p, "Base", "B-1");
        for (int i = 0; i < 300; i++) {
            link(start, entity(p, i % 2 == 0 ? "Other" : "SubOther", "O-" + (1000 + i)));
        }
        PackDefinition pack = pack(p, rule("0002", 1, IMPACT, "Other", 7), rule("0001", 1, IMPACT, "SubOther", 3),
                rule("0003", 2, CHANGE, "Base", 1));

        ActionGeneration first = generator.generate(pack, change("ev-twice-1", start), EDITION, scope(start), 3, NOW);
        ActionGeneration second = generator.generate(pack, change("ev-twice-2", start), EDITION, scope(start), 3, NOW);

        assertThat(first.actions()).hasSize(150 + 300 + 1);
        assertThat(second.actions()).extracting(Action::ruleNo, Action::targetEntityId, Action::dueDate, Action::priority)
            .containsExactlyElementsOf(first.actions().stream()
                .map(a -> tuple(a.ruleNo(), a.targetEntityId(), a.dueDate(), a.priority()))
                .toList());
        assertThat(records.impactScope("ev-twice-2").orElseThrow().targets())
            .usingRecursiveComparison()
            .isEqualTo(records.impactScope("ev-twice-1").orElseThrow().targets());
        // 읽는 차례도 만든 차례와 같다.
        assertThat(records.actions("ev-twice-1")).isEqualTo(first.actions());
    }

    // ── Pack 의 조치 목록과 영향 범위의 수 (D-06 의 서버 쪽) ──────────────────

    @Test
    void Pack_의_조치_목록은_기한_우선순위_규칙_번호_대상_순이고_상태와_변동으로_거른다() {
        String p = "a-list";
        EntityRef first = entity(p, "Base", "B-1");
        EntityRef second = entity(p, "Base", "B-2");
        EntityRef target = entity(p, "Other", "O-1");
        link(first, target);
        link(second, target);
        // 규칙 0001 은 기한이 늦고 우선순위가 높다. 0002 는 기한이 이르고 우선순위가 낮다. 0003 은 0002 와 기한이 같다.
        PackDefinition pack = pack(p, rule("0001", 1, IMPACT, "Other", 30), rule("0002", 5, IMPACT, "Other", 7),
                rule("0003", 2, CHANGE, "Base", 7));
        generator.generate(pack, change("ev-list-b", second), EDITION, scope(second), 3, NOW);
        generator.generate(pack, change("ev-list-a", first), EDITION, scope(first), 3, NOW);
        // 다른 Pack 의 조치는 섞이지 않는다.
        EntityRef foreign = entity("a-list-other", "Base", "B-1");
        generator.generate(pack("a-list-other", rule("0003", 1, CHANGE, "Base", 1)), change("ev-list-x", foreign), EDITION,
                scope(foreign), 3, NOW);

        List<Action> all = records.packActions(p, null, null);

        // 기한이 이른 것부터 (D2 AD-243). 같으면 우선순위, 규칙 번호, 대상의 식별자, 조치의 식별자.
        assertThat(all).extracting(Action::dueDate, Action::priority, Action::ruleNo, Action::targetEntityId,
                Action::changeEventId)
            .containsExactly(tuple(D.plusDays(7), 2, "0003", first.id(), "ev-list-a"),
                    tuple(D.plusDays(7), 2, "0003", second.id(), "ev-list-b"),
                    tuple(D.plusDays(7), 5, "0002", target.id(), "ev-list-a"),
                    tuple(D.plusDays(7), 5, "0002", target.id(), "ev-list-b"),
                    tuple(D.plusDays(30), 1, "0001", target.id(), "ev-list-a"),
                    tuple(D.plusDays(30), 1, "0001", target.id(), "ev-list-b"));
        // 변동 하나로 좁힌다. 차례의 기준은 같다.
        assertThat(records.packActions(p, null, "ev-list-b")).extracting(Action::ruleNo, Action::changeEventId)
            .containsExactly(tuple("0003", "ev-list-b"), tuple("0002", "ev-list-b"), tuple("0001", "ev-list-b"));
        // 상태로 거른다. 지금은 모두 대기다.
        assertThat(records.packActions(p, ActionStatus.PENDING, null)).isEqualTo(all);
        assertThat(records.packActions(p, ActionStatus.CONFIRMED, null)).isEmpty();
        assertThat(records.packActions(p, ActionStatus.ON_HOLD, "ev-list-a")).isEmpty();
        // 없는 Pack, 다른 Pack 의 변동, 없는 변동은 빈 목록이다.
        assertThat(records.packActions("a-list-없는-pack", null, null)).isEmpty();
        assertThat(records.packActions(p, null, "ev-list-x")).isEmpty();
        assertThat(records.packActions(p, null, "없는 변동")).isEmpty();
        assertThatThrownBy(() -> records.packActions(null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> records.packActions(p, null, "ev\uD800")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 저장된_영향_범위의_유형별_수와_깊이별_수는_영향_대상의_수와_맞는다() {
        String p = "a-counts";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef near = entity(p, "Other", "O-1");
        link(start, near);
        link(start, entity(p, "SubOther", "S-1"));
        link(near, entity(p, "Other", "O-2"));
        link(near, entity(p, "Third", "T-1"));
        generator.generate(pack(p), change("ev-counts", start), EDITION, scope(start), 3, NOW);
        EntityRef alone = entity(p, "Base", "B-2");
        generator.generate(pack(p), change("ev-counts-none", alone), EDITION, scope(alone), 3, NOW);

        StoredImpactScope stored = records.impactScope("ev-counts").orElseThrow();

        // 유형의 이름의 글자 코드 순, 깊이 순이다. 두 합계는 영향 대상의 수와 같다 (D2 AD-63).
        assertThat(stored.countsByType()).containsExactly(Map.entry("Other", 2), Map.entry("SubOther", 1),
                Map.entry("Third", 1));
        assertThat(stored.countsByDepth()).containsExactly(Map.entry(1, 2), Map.entry(2, 2));
        assertThat(stored.targets()).hasSize(4);
        StoredImpactScope none = records.impactScope("ev-counts-none").orElseThrow();
        assertThat(none.countsByType()).isEmpty();
        assertThat(none.countsByDepth()).isEmpty();
    }

    // ── 나눠 주기 (D-13, D2 AD-347) ──────────────────────────────

    @Test
    void 조치를_나눠_받아_이으면_한_번에_받은_것과_순서까지_같고_거른_전체_수가_함께_온다() {
        String p = "a-page";
        EntityRef start = entity(p, "Base", "B-1");
        for (int i = 0; i < 12; i++) {
            // 넣는 차례를 섞는다.
            link(start, entity(p, "Other", "O-" + String.format("%02d", (i * 5) % 12)));
        }
        PackDefinition pack = pack(p, rule("0001", 1, IMPACT, "Other", 30), rule("0002", 5, IMPACT, "Other", 7),
                rule("0003", 2, CHANGE, "Base", 7));
        generator.generate(pack, change("ev-page", start), EDITION, scope(start), 3, NOW);
        List<Action> all = records.packActions(p, null, null);
        assertThat(all).hasSize(25);

        List<Action> joined = new ArrayList<>();
        for (int offset = 0; offset < 35; offset += 7) {
            ActionPage page = records.packActions(p, null, null, offset, 7);
            assertThat(page.total()).isEqualTo(25);
            assertThat(page.actions()).hasSize(Math.max(0, Math.min(7, 25 - offset)));
            joined.addAll(page.actions());
        }
        assertThat(joined).isEqualTo(all);
        // 거른 것의 전체 수다. 걸러도 나눠 받은 것을 이으면 거른 목록과 같다.
        ActionPage byEvent = records.packActions(p, ActionStatus.PENDING, "ev-page", 20, 10);
        assertThat(byEvent.total()).isEqualTo(25);
        assertThat(byEvent.actions()).isEqualTo(all.subList(20, 25));
        assertThat(records.packActions(p, ActionStatus.CONFIRMED, null, 0, 10)).isEqualTo(new ActionPage(List.of(), 0));
        assertThat(records.packActions("a-page-없는-pack", null, null, 0, 10).total()).isZero();
        // 전체 수를 넘겨 건너뛰면 빈 목록이고 전체 수는 그대로다. 두 번 물어도 같다.
        assertThat(records.packActions(p, null, null, 25, 10)).isEqualTo(new ActionPage(List.of(), 25));
        assertThat(records.packActions(p, null, null, 7, 7)).isEqualTo(records.packActions(p, null, null, 7, 7));
        assertThatThrownBy(() -> records.packActions(p, null, null, -1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> records.packActions(p, null, null, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 영향_대상을_나눠_받아_이으면_한_번에_받은_것과_같고_전체_수와_유형별_깊이별_수는_나누기_전의_것이다() {
        String p = "a-scope-page";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef near = entity(p, "Other", "O-near");
        link(start, near);
        for (int i = 0; i < 9; i++) {
            int n = (i * 4) % 9;
            link(start, entity(p, n % 2 == 0 ? "Other" : "Third", "T-" + n));
            link(near, entity(p, "Third", "far-" + n));
        }
        generator.generate(pack(p, rule("0001", 1, IMPACT, "Other", 7)), change("ev-scope-page", start), EDITION,
                scope(start), 3, NOW);
        StoredImpactScope whole = records.impactScope("ev-scope-page").orElseThrow();
        assertThat(whole.targets()).hasSize(19);

        List<StoredImpactTarget> joined = new ArrayList<>();
        for (int offset = 0; offset < 24; offset += 4) {
            StoredImpactScopePage page = records.impactScopePage("ev-scope-page", null, offset, 4).orElseThrow();
            // 머리의 값과 수는 어느 쪽에서나 나누기 전의 것이다.
            assertThat(page.total()).isEqualTo(19);
            assertThat(page.countsByType()).isEqualTo(whole.countsByType());
            assertThat(page.countsByDepth()).isEqualTo(whole.countsByDepth());
            assertThat(new ArrayList<>(page.countsByType().keySet())).isEqualTo(new ArrayList<>(whole.countsByType().keySet()));
            assertThat(page.scope().targets()).hasSize(Math.max(0, Math.min(4, 19 - offset)));
            assertThat(page.scope().asOfEffective()).isEqualTo(whole.asOfEffective());
            assertThat(page.scope().storedAt()).isEqualTo(whole.storedAt());
            joined.addAll(page.scope().targets());
        }
        // 근거 경로까지 같다.
        assertThat(joined).isEqualTo(whole.targets());
        assertThat(records.impactScopePage("ev-scope-page", null, 19, 4).orElseThrow().scope().targets()).isEmpty();
    }

    @Test
    void 영향_대상_하나만_물으면_그_대상과_근거_경로만_오고_수는_거르지_않은_것이다() {
        String p = "a-scope-one";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef near = entity(p, "Other", "O-1");
        EntityRef far = entity(p, "Other", "O-2");
        EntityRef outside = entity(p, "Other", "O-9");
        link(start, near);
        link(near, far);
        generator.generate(pack(p, rule("0001", 1, IMPACT, "Other", 7)), change("ev-scope-one", start), EDITION,
                scope(start), 3, NOW);
        StoredImpactScope whole = records.impactScope("ev-scope-one").orElseThrow();

        StoredImpactScopePage one = records.impactScopePage("ev-scope-one", far.id(), 0, 50).orElseThrow();

        assertThat(one.scope().targets()).containsExactly(whole.targets().get(1));
        assertThat(one.scope().targets().get(0).steps()).hasSize(2);
        assertThat(one.total()).isEqualTo(2);
        assertThat(one.countsByDepth()).isEqualTo(whole.countsByDepth());
        // 영향 대상이 아닌 개체(범위 밖의 개체, 변동 대상 자신, 없는 개체)는 빈 목록이다. 영향 범위는 있다.
        for (String notTarget : new String[] { outside.id(), start.id(), "a-scope-one|Other|없는 것" }) {
            StoredImpactScopePage none = records.impactScopePage("ev-scope-one", notTarget, 0, 50).orElseThrow();
            assertThat(none.scope().targets()).isEmpty();
            assertThat(none.total()).isEqualTo(2);
        }
        // 대상 하나를 물을 때 건너뛰면 빈 목록이다.
        assertThat(records.impactScopePage("ev-scope-one", far.id(), 1, 50).orElseThrow().scope().targets()).isEmpty();
        // 저장된 영향 범위가 없으면 비어 있다. 틀린 값은 받지 않는다.
        assertThat(records.impactScopePage("없는 변동", null, 0, 50)).isEmpty();
        assertThatThrownBy(() -> records.impactScopePage("ev-scope-one", null, -1, 50))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> records.impactScopePage("ev-scope-one", null, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> records.impactScopePage("ev-scope-one", "", 0, 50))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 나눠_읽을_때도_근거_경로가_깊이와_맞지_않는_영향_대상은_내보내지_않고_알린다() {
        String p = "a-scope-broken";
        EntityRef start = entity(p, "Base", "B-1");
        EntityRef near = entity(p, "Other", "O-1");
        EntityRef far = entity(p, "Other", "O-2");
        link(start, near);
        link(near, far);
        generator.generate(pack(p, rule("0001", 1, IMPACT, "Other", 7)), change("ev-scope-broken", start), EDITION,
                scope(start), 3, NOW);
        // 저장소가 어긋난 경우를 만든다: 먼 대상의 둘째 단계를 지운다.
        int removed = jdbc.update("delete from impact_path_step where change_event_hash = ? and entity_hash = ? and step_no = 2",
                RulesHash.sha256Hex("ev-scope-broken"), RulesHash.sha256Hex(far.id()));
        assertThat(removed).isEqualTo(1);

        // 그 대상이 든 쪽을 읽으면 근거 없이 내보내지 않고 알린다. 하나만 물어도 같다.
        assertThatThrownBy(() -> records.impactScopePage("ev-scope-broken", null, 0, 50))
            .isInstanceOf(ActionStorageException.class)
            .hasMessageContaining(far.id());
        assertThatThrownBy(() -> records.impactScopePage("ev-scope-broken", far.id(), 0, 50))
            .isInstanceOf(ActionStorageException.class);
        // 그 대상이 들지 않은 쪽은 읽힌다: 어긋난 것은 그 쪽을 읽을 때에야 드러난다. 수에는 그 대상도 들어 있다.
        StoredImpactScopePage firstOnly = records.impactScopePage("ev-scope-broken", null, 0, 1).orElseThrow();
        assertThat(firstOnly.scope().targets()).extracting(StoredImpactTarget::entityId).containsExactly(near.id());
        assertThat(firstOnly.total()).isEqualTo(2);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private int count(String table, String changeEventId) {
        return jdbc.queryForObject("select count(*) from " + table + " where change_event_hash = ?", Integer.class,
                RulesHash.sha256Hex(changeEventId));
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
        value(entity, attribute, value, START, null);
    }

    private void value(EntityRef entity, String attribute, String value, LocalDate from, LocalDate to) {
        values.saveAttributeValue(new NewAttributeValue(entity, attribute, value, new ValidityPeriod(from, to), SOURCE), AT);
    }

    private ImpactScope scope(EntityRef... starts) {
        return scopeAt(D, starts);
    }

    /** 변동 대상에서 출발한 영향 범위. 적용일 전날과 적용일로 본다 (D2 AD-100). */
    private ImpactScope scopeAt(LocalDate effectiveDate, EntityRef... starts) {
        List<String> ids = new ArrayList<>();
        for (EntityRef start : starts) {
            ids.add(start.id());
        }
        return impact.impactScope(new ImpactRequest(ids, List.of(effectiveDate.minusDays(1), effectiveDate),
                Map.of("links", ImpactDirection.SOURCE_TO_TARGET), 3));
    }

    private static RuleChange change(String changeEventId, EntityRef... targets) {
        List<String> ids = new ArrayList<>();
        for (EntityRef target : targets) {
            ids.add(target.id());
        }
        return new RuleChange(changeEventId, RuleChangeKind.CHANGED, targets[0].id(), ids, D);
    }

    private static RuleDefinition rule(String ruleNo, int priority, RuleSubject appliesTo, String targetType, int dueInDays,
            RuleCondition... conditions) {
        return new RuleDefinition(ruleNo, "규칙 " + ruleNo, priority, ALL, appliesTo, targetType, List.of(conditions),
                "할 일 " + ruleNo, dueInDays);
    }

    /** 지어낸 작은 Pack 의 정의. 유형: Base, Other ⊃ SubOther, Third. */
    private static PackDefinition pack(String packId, RuleDefinition... rules) {
        return new PackDefinition(packId, "이름표", 3, AT,
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null),
                        new EntityTypeDefinition("SubOther", "다른 것의 아래", List.of("Other"), null),
                        new EntityTypeDefinition("Third", "셋째", List.of(), null)),
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET)),
                List.of(new AttributeDefinition("count", "수", "Other", ValueFormat.INTEGER)), List.of(), List.of(),
                List.of(rules));
    }
}
