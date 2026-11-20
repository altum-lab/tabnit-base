package com.altumlab.tabnit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A-04 영향 범위 계산, A-05 근거 경로 기록의 일반 시험 (D3 7절, D8 1절).
 *
 * <p>특정 Pack 과 무관한 지어낸 값으로 본다: 방향, 돌아오는 관계, 한 대상에 이르는 경로 여럿, 깊이 한계,
 * 유효기간 경계, 날짜 여럿, 순서, 빈 결과, 큰 입력, 잘못된 입력.
 *
 * <p>개체의 키는 한 글자씩 쓴다. 관계 유형마다의 영향 방향은 부르는 쪽이 넘긴다 (D2 AD-66).
 */
class ImpactQueryTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Provenance SRC_1 = new Provenance("src", 1);

    private static final LocalDate DAY = LocalDate.parse("2026-03-01");

    private static final Map<String, ImpactDirection> FORWARD = Map.of("R", ImpactDirection.SOURCE_TO_TARGET);

    private final RelationStore store = new RelationStore(CoreTestDatabase.dataSource());

    private final ImpactQuery impact = new ImpactQuery(CoreTestDatabase.dataSource());

    // ── D3 7절의 예시 ────────────────────────────────────────────

    @Test
    void 변동_대상에서_방향대로_따라가_영향_대상과_근거_경로를_돌려준다() {
        // 가→나, 나→다, 가→다, 다→가, 라→가. 방향은 출발에서 도착으로.
        String p = "imp-d3";
        String r1 = rel(p, "ga", "na", "R");
        rel(p, "na", "da", "R");
        String r3 = rel(p, "ga", "da", "R");
        rel(p, "da", "ga", "R");
        rel(p, "ra", "ga", "R");

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "ga")), List.of(DAY), FORWARD, 3));

        // 나(깊이 1, R1), 다(깊이 1, R3: 나를 거치는 길이 2 의 경로보다 짧다).
        // 가 는 다→가 로 다시 닿지만 변동 대상 자신이라 넣지 않는다. 라 는 방향이 반대라 넣지 않는다.
        assertThat(scope.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "da"), id(p, "na"));
        assertThat(scope.targets()).extracting(ImpactTarget::depth).containsExactly(1, 1);
        assertThat(pathOf(scope, id(p, "na"))).containsExactly(r1);
        assertThat(pathOf(scope, id(p, "da"))).containsExactly(r3);
        assertThat(scope.targets().get(0).path().startEntityId()).isEqualTo(id(p, "ga"));
        assertThat(scope.targets().get(0).path().asOf()).isEqualTo(DAY);
        assertThat(scope.truncated()).isFalse();
    }

    // ── 방향 ───────────────────────────────────────────────────

    @Test
    void 관계_유형마다_넘겨받은_방향으로만_따라간다() {
        String p = "imp-dir";
        rel(p, "a", "fwd", "S2T");
        rel(p, "bwd", "a", "T2S");
        rel(p, "a", "both-out", "BOTH");
        rel(p, "both-in", "a", "BOTH");
        rel(p, "a", "none-out", "NONE");
        rel(p, "none-in", "a", "NONE");
        // 방향과 반대로 놓인 관계는 따라가지 않는다.
        rel(p, "wrong-1", "a", "S2T");
        rel(p, "a", "wrong-2", "T2S");
        Map<String, ImpactDirection> directions = Map.of("S2T", ImpactDirection.SOURCE_TO_TARGET, "T2S",
                ImpactDirection.TARGET_TO_SOURCE, "BOTH", ImpactDirection.BOTH, "NONE", ImpactDirection.NONE);

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), directions, 2));

        assertThat(scope.targets()).extracting(ImpactTarget::entityId)
                .containsExactly(id(p, "both-in"), id(p, "both-out"), id(p, "bwd"), id(p, "fwd"));
    }

    @Test
    void 방향을_넘겨받지_못한_관계_유형을_만나면_어느_유형인지_알리고_멈춘다() {
        String p = "imp-unknown";
        rel(p, "a", "b", "R");
        rel(p, "b", "c", "X");

        assertThatThrownBy(() -> impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 3)))
                .isInstanceOf(UnknownImpactDirectionException.class)
                .hasMessageContaining("X");
    }

    @Test
    void 방향을_모르는_유형이_여럿이면_언제나_같은_것을_알린다() {
        String p = "imp-unknown-many";
        rel(p, "a", "b", "Y");
        rel(p, "a", "c", "X");

        // 관계를 식별자 순으로 읽어 처음 만난 것을 알린다: …|a|T|b|Y… 가 …|a|T|c|X… 보다 앞선다.
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), Map.of(), 3)))
                    .isInstanceOf(UnknownImpactDirectionException.class)
                    .hasMessageContaining("Y");
        }
    }

    @Test
    void 최대_깊이_너머에서_방향을_모르는_유형을_만나도_멈춘다() {
        // 한계 표시를 정하려면 최대 깊이 너머를 따라가야 한다. 그때 만나도 같다.
        String p = "imp-unknown-far";
        rel(p, "a", "b", "R");
        rel(p, "b", "c", "X");

        assertThatThrownBy(() -> impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 1)))
                .isInstanceOf(UnknownImpactDirectionException.class)
                .hasMessageContaining("X");
    }

    @Test
    void 도착에서_출발로_퍼지는_유형에서도_경로를_같은_규칙으로_고른다() {
        String p = "imp-tie-back";
        rel(p, "m", "a", "R");
        rel(p, "z", "m", "R");
        String viaK1 = rel(p, "k", "a", "R");
        String viaK2 = rel(p, "z", "k", "R");

        ImpactScope scope = impact.impactScope(
                request(List.of(id(p, "a")), List.of(DAY), Map.of("R", ImpactDirection.TARGET_TO_SOURCE), 5));

        assertThat(pathOf(scope, id(p, "z"))).containsExactly(viaK1, viaK2);
        assertThat(target(scope, id(p, "z")).depth()).isEqualTo(2);
    }

    @Test
    void 양쪽으로_퍼지는_유형은_온_길로_되돌아가지_않고_한계_표시도_같은_규칙이다() {
        String p = "imp-both";
        rel(p, "b", "a", "R");
        rel(p, "b", "c", "R");
        Map<String, ImpactDirection> both = Map.of("R", ImpactDirection.BOTH);

        ImpactScope atOne = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), both, 1));
        ImpactScope atTwo = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), both, 2));

        assertThat(atOne.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "b"));
        assertThat(atOne.truncated()).isTrue();
        assertThat(atTwo.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "b"), id(p, "c"));
        assertThat(atTwo.truncated()).isFalse();
    }

    @Test
    void 최대_깊이_너머에_퍼지지_않는_유형의_관계만_있으면_한계_표시를_켜지_않는다() {
        String p = "imp-none-far";
        rel(p, "a", "b", "R");
        rel(p, "b", "c", "N");
        Map<String, ImpactDirection> dirs = Map.of("R", ImpactDirection.SOURCE_TO_TARGET, "N", ImpactDirection.NONE);

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), dirs, 1));

        assertThat(scope.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "b"));
        assertThat(scope.truncated()).isFalse();
    }

    @Test
    void 방향을_모르는_유형이라도_기준_날짜에_유효하지_않은_관계는_만나지_않는다() {
        String p = "imp-unknown-ended";
        relBetween(p, "a", "b", "X", "2026-01-01", "2026-03-01");

        assertThat(impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 3)).targets()).isEmpty();
    }

    @Test
    void 퍼지지_않는_유형의_관계는_깊은_곳에서도_건너지_않는다() {
        String p = "imp-none-deep";
        rel(p, "a", "b", "R");
        rel(p, "b", "c", "N");
        rel(p, "c", "d", "R");
        Map<String, ImpactDirection> dirs = Map.of("R", ImpactDirection.SOURCE_TO_TARGET, "N", ImpactDirection.NONE);

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), dirs, 5));

        assertThat(scope.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "b"));
        assertThat(scope.truncated()).isFalse();
    }

    // ── 돌아오는 관계, 경로 여럿 ──────────────────────────────────

    @Test
    void 관계가_돌아와도_끝나고_같은_대상은_한_번만_센다() {
        String p = "imp-cycle";
        rel(p, "a", "b", "R");
        rel(p, "b", "c", "R");
        rel(p, "c", "a", "R");
        rel(p, "c", "b", "R");

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 10));

        assertThat(scope.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "b"), id(p, "c"));
        assertThat(scope.countsByDepth()).containsExactly(Map.entry(1, 1), Map.entry(2, 1));
        assertThat(scope.truncated()).isFalse();
    }

    @Test
    void 경로가_여럿이면_가장_짧은_것을_남긴다() {
        String p = "imp-short";
        rel(p, "a", "b", "R");
        rel(p, "b", "c", "R");
        rel(p, "c", "z", "R");
        String direct = rel(p, "a", "z", "R");

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 5));

        assertThat(target(scope, id(p, "z")).depth()).isEqualTo(1);
        assertThat(pathOf(scope, id(p, "z"))).containsExactly(direct);
    }

    @Test
    void 길이가_같은_경로는_관계_식별자를_변동_대상_쪽부터_비교해_앞선_것을_남긴다() {
        String p = "imp-tie";
        // a 에서 z 로 가는 길이 2 의 경로가 둘이다: m 을 거치는 것과 k 를 거치는 것.
        rel(p, "a", "m", "R");
        rel(p, "m", "z", "R");
        String viaK1 = rel(p, "a", "k", "R");
        String viaK2 = rel(p, "k", "z", "R");

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 5));

        // 첫 관계의 식별자가 …|a|T|k|… < …|a|T|m|… 이므로 k 를 거치는 경로를 남긴다.
        assertThat(pathOf(scope, id(p, "z"))).containsExactly(viaK1, viaK2);
    }

    @Test
    void 경로의_비교는_마지막_관계가_아니라_출발_개체_쪽의_관계부터_한다() {
        String p = "imp-tie-front";
        // z 로 가는 길이 2 의 경로가 둘이다. 첫 관계는 k 쪽이 앞서고(…|a|T|k < …|a|T|m),
        // 마지막 관계는 m 쪽이 앞선다(…|m|T|z < …|z|T|k). 첫 관계로 정해야 한다.
        String aToK = rel(p, "a", "k", "R");
        rel(p, "a", "m", "R");
        String mToZ = rel(p, "m", "z", "R");
        String zToK = rel(p, "z", "k", "B");
        Map<String, ImpactDirection> dirs = Map.of("R", ImpactDirection.SOURCE_TO_TARGET, "B",
                ImpactDirection.TARGET_TO_SOURCE);

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), dirs, 5));

        assertThat(mToZ).isLessThan(zToK);
        assertThat(pathOf(scope, id(p, "z"))).containsExactly(aToK, zToK);
    }

    @Test
    void 출발_개체_둘에서_같은_길이로_닿으면_관계_식별자가_앞선_쪽을_남기고_그_출발_개체를_적는다() {
        // D3 7절: 변동 대상이 둘이고 한 대상에 길이 1 의 경로가 둘인 경우.
        String p = "imp-starts-tie";
        rel(p, "s2", "s1", "R");
        String fromS1 = rel(p, "s1", "da", "R");
        rel(p, "s2", "da", "R");

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "s2"), id(p, "s1")), List.of(DAY), FORWARD, 3));

        assertThat(scope.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "da"));
        assertThat(pathOf(scope, id(p, "da"))).containsExactly(fromS1);
        assertThat(target(scope, id(p, "da")).path().startEntityId()).isEqualTo(id(p, "s1"));
    }

    @Test
    void 두_개체_사이에_관계가_여럿이어도_경로가_하나로_정해진다() {
        String p = "imp-multi";
        String a = id(p, "a");
        store.saveEntity(new EntityRef(p, "T", "a"), RECORDED_AT);
        store.saveEntity(new EntityRef(p, "T", "b"), RECORDED_AT);
        String edition2 = store.saveRelation(new NewRelation(new EntityRef(p, "T", "a"), new EntityRef(p, "T", "b"), "R",
                new ValidityPeriod(LocalDate.parse("2026-01-01"), null), new Provenance("src", 2)), RECORDED_AT);
        String edition1 = rel(p, "a", "b", "R");

        ImpactScope scope = impact.impactScope(request(List.of(a), List.of(DAY), FORWARD, 2));

        assertThat(edition1).isLessThan(edition2);
        assertThat(pathOf(scope, id(p, "b"))).containsExactly(edition1);
    }

    // ── 변동 대상이 여럿 ─────────────────────────────────────────

    @Test
    void 변동_대상이_여럿이면_함께_출발하고_변동_대상은_모두_뺀다() {
        String p = "imp-starts";
        rel(p, "a", "b", "R");
        String bToC = rel(p, "b", "c", "R");
        rel(p, "a", "c2", "R");

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "b"), id(p, "a")), List.of(DAY), FORWARD, 3));

        // b 는 a 에서 닿지만 변동 대상이라 빠진다. c 는 가장 가까운 변동 대상 b 에서 깊이 1 이다.
        assertThat(scope.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "c"), id(p, "c2"));
        assertThat(target(scope, id(p, "c")).depth()).isEqualTo(1);
        assertThat(target(scope, id(p, "c")).path().startEntityId()).isEqualTo(id(p, "b"));
        assertThat(pathOf(scope, id(p, "c"))).containsExactly(bToC);
        assertThat(target(scope, id(p, "c2")).path().startEntityId()).isEqualTo(id(p, "a"));
    }

    // ── 깊이 한계 ──────────────────────────────────────────────

    @Test
    void 최대_깊이까지만_돌려주고_그_너머에_새_대상이_있으면_표시를_켠다() {
        String p = "imp-depth";
        rel(p, "a", "b", "R");
        rel(p, "b", "c", "R");
        rel(p, "c", "d", "R");

        ImpactScope atTwo = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 2));
        ImpactScope atThree = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 3));

        assertThat(atTwo.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "b"), id(p, "c"));
        assertThat(atTwo.truncated()).isTrue();
        // 깊이를 늘리면 결과가 늘어난다. 그 너머에는 아무것도 없어 표시가 꺼진다.
        assertThat(atThree.targets()).extracting(ImpactTarget::entityId)
                .containsExactly(id(p, "b"), id(p, "c"), id(p, "d"));
        assertThat(atThree.truncated()).isFalse();
    }

    @Test
    void 한계_너머의_대상은_깊이를_늘리면_근거_경로와_함께_더해진다() {
        // D3 7절: 가→나, 나→다, 가→다, 다→가 에 다→마 를 더한 경우.
        String p = "imp-depth-more";
        rel(p, "ga", "na", "R");
        rel(p, "na", "da", "R");
        String r3 = rel(p, "ga", "da", "R");
        rel(p, "da", "ga", "R");
        String r6 = rel(p, "da", "ma", "R");

        ImpactScope atOne = impact.impactScope(request(List.of(id(p, "ga")), List.of(DAY), FORWARD, 1));
        ImpactScope atTwo = impact.impactScope(request(List.of(id(p, "ga")), List.of(DAY), FORWARD, 2));

        assertThat(atOne.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "da"), id(p, "na"));
        assertThat(atOne.truncated()).isTrue();
        assertThat(target(atTwo, id(p, "ma")).depth()).isEqualTo(2);
        assertThat(pathOf(atTwo, id(p, "ma"))).containsExactly(r3, r6);
        assertThat(atTwo.truncated()).isFalse();
    }

    @Test
    void 최대_깊이에_남은_관계가_이미_찾은_대상으로만_이어지면_표시를_켜지_않는다() {
        // D3 7절: 가→나, 나→다, 가→다, 다→가. 최대 깊이 1.
        String p = "imp-depth-known";
        rel(p, "ga", "na", "R");
        rel(p, "na", "da", "R");
        rel(p, "ga", "da", "R");
        rel(p, "da", "ga", "R");

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "ga")), List.of(DAY), FORWARD, 1));

        assertThat(scope.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "da"), id(p, "na"));
        assertThat(scope.truncated()).isFalse();
    }

    // ── 유효기간 ───────────────────────────────────────────────

    @Test
    void 기준_날짜에_유효한_관계만_따라간다() {
        String p = "imp-valid";
        relBetween(p, "a", "ended", "R", "2026-01-01", "2026-03-01");
        relBetween(p, "a", "starts", "R", "2026-03-01", null);
        relBetween(p, "a", "future", "R", "2026-03-02", null);

        // 종료일 당일의 관계는 따라가지 않고, 시작일 당일의 관계는 따라간다.
        assertThat(impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 2)).targets())
                .extracting(ImpactTarget::entityId)
                .containsExactly(id(p, "starts"));
        // 종료일 전날.
        assertThat(impact.impactScope(request(List.of(id(p, "a")), List.of(DAY.minusDays(1)), FORWARD, 2)).targets())
                .extracting(ImpactTarget::entityId)
                .containsExactly(id(p, "ended"));
    }

    // ── 날짜 여럿 (D2 AD-115 ~ AD-117) ───────────────────────────

    @Test
    void 날짜가_여럿이면_날짜마다_따라가_합치고_어느_날짜의_경로인지_적는다() {
        String p = "imp-dates";
        relBetween(p, "a", "before", "R", "2026-01-01", "2026-03-01");
        relBetween(p, "a", "after", "R", "2026-03-01", null);
        relBetween(p, "a", "always", "R", "2026-01-01", null);
        LocalDate dayBefore = DAY.minusDays(1);

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "a")), List.of(dayBefore, DAY), FORWARD, 2));

        assertThat(scope.targets()).extracting(ImpactTarget::entityId)
                .containsExactly(id(p, "after"), id(p, "always"), id(p, "before"));
        assertThat(target(scope, id(p, "before")).path().asOf()).isEqualTo(dayBefore);
        assertThat(target(scope, id(p, "after")).path().asOf()).isEqualTo(DAY);
        // 두 날짜 모두 같은 길이로 닿으면 더 늦은 날짜의 경로를 남긴다.
        assertThat(target(scope, id(p, "always")).path().asOf()).isEqualTo(DAY);
    }

    @Test
    void 같은_대상에_두_날짜로_닿으면_더_짧은_경로를_남긴다() {
        String p = "imp-dates-short";
        // 앞 날짜에는 a→z 로 바로 닿고, 뒤 날짜에는 a→m→z 로 돌아서 닿는다.
        String direct = relBetween(p, "a", "z", "R", "2026-01-01", "2026-03-01");
        relBetween(p, "a", "m", "R", "2026-01-01", null);
        relBetween(p, "m", "z", "R", "2026-01-01", null);

        ImpactScope scope = impact
            .impactScope(request(List.of(id(p, "a")), List.of(DAY.minusDays(1), DAY), FORWARD, 3));

        assertThat(target(scope, id(p, "z")).depth()).isEqualTo(1);
        assertThat(target(scope, id(p, "z")).path().asOf()).isEqualTo(DAY.minusDays(1));
        assertThat(pathOf(scope, id(p, "z"))).containsExactly(direct);
    }

    @Test
    void 날짜가_여럿일_때_한계_표시는_합친_결과를_기준으로_켠다() {
        String p = "imp-dates-trunc";
        // 뒤 날짜: a→b→c 로 c 는 깊이 2 (한계 1 의 너머).  앞 날짜: a→c 로 c 는 깊이 1.
        relBetween(p, "a", "b", "R", "2026-01-01", null);
        relBetween(p, "b", "c", "R", "2026-01-01", null);
        relBetween(p, "a", "c", "R", "2026-01-01", "2026-03-01");

        ImpactScope merged = impact
            .impactScope(request(List.of(id(p, "a")), List.of(DAY.minusDays(1), DAY), FORWARD, 1));
        ImpactScope laterOnly = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 1));

        // 뒤 날짜만 보면 c 가 한계 너머에 있어 표시가 켜진다.
        assertThat(laterOnly.truncated()).isTrue();
        // 합치면 c 는 앞 날짜에서 이미 찾은 대상이다. 깊이를 늘려도 결과가 늘지 않으므로 켜지 않는다.
        assertThat(merged.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "b"), id(p, "c"));
        assertThat(merged.truncated()).isFalse();
    }

    @Test
    void 합친_결과에_없는_대상이_두_단계_너머에만_있어도_한계_표시를_켠다() {
        String p = "imp-dates-far";
        // 뒤 날짜: a→b→c→d. 한계 1 에서 한 단계 너머는 c, 두 단계 너머는 d.
        // 앞 날짜: a→c 가 있어 c 는 합친 결과에 있다. d 는 어느 날짜에도 한계 안에 없다.
        relBetween(p, "a", "b", "R", "2026-01-01", null);
        relBetween(p, "b", "c", "R", "2026-01-01", null);
        relBetween(p, "c", "d", "R", "2026-03-01", null);
        relBetween(p, "a", "c", "R", "2026-01-01", "2026-03-01");

        ImpactScope scope = impact
            .impactScope(request(List.of(id(p, "a")), List.of(DAY.minusDays(1), DAY), FORWARD, 1));

        // 깊이를 충분히(3 까지) 늘리면 d 가 더해지므로 표시를 켠다 (D2 AD-117).
        assertThat(scope.targets()).extracting(ImpactTarget::entityId).containsExactly(id(p, "b"), id(p, "c"));
        assertThat(scope.truncated()).isTrue();
    }

    @Test
    void 뒤_날짜의_경로가_더_짧으면_뒤_날짜의_것을_남긴다() {
        String p = "imp-dates-later-short";
        relBetween(p, "a", "m", "R", "2026-01-01", null);
        relBetween(p, "m", "z", "R", "2026-01-01", null);
        String direct = relBetween(p, "a", "z", "R", "2026-03-01", null);

        ImpactScope scope = impact
            .impactScope(request(List.of(id(p, "a")), List.of(DAY.minusDays(1), DAY), FORWARD, 3));

        assertThat(target(scope, id(p, "z")).path().asOf()).isEqualTo(DAY);
        assertThat(pathOf(scope, id(p, "z"))).containsExactly(direct);
    }

    @Test
    void 날짜가_셋이어도_날짜마다_따라가_합친다() {
        String p = "imp-dates-three";
        relBetween(p, "a", "t1", "R", "2026-01-01", "2026-02-01");
        relBetween(p, "a", "t2", "R", "2026-02-01", "2026-03-01");
        relBetween(p, "a", "t3", "R", "2026-03-01", null);
        LocalDate first = LocalDate.parse("2026-01-15");
        LocalDate second = LocalDate.parse("2026-02-15");

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY, first, second), FORWARD, 2));

        assertThat(scope.targets()).extracting(ImpactTarget::entityId)
                .containsExactly(id(p, "t1"), id(p, "t2"), id(p, "t3"));
        assertThat(scope.targets()).extracting(t -> t.path().asOf()).containsExactly(first, second, DAY);
    }

    @Test
    void 앞_날짜에서_한계_표시가_켜져도_뒤_날짜의_너머에서_방향을_모르는_유형을_만나면_멈춘다() {
        String p = "imp-dates-unknown";
        relBetween(p, "a", "b", "R", "2026-01-01", null);
        relBetween(p, "b", "c", "R", "2026-01-01", "2026-03-01");
        relBetween(p, "b", "x", "X", "2026-03-01", null);

        assertThatThrownBy(() -> impact
            .impactScope(request(List.of(id(p, "a")), List.of(DAY.minusDays(1), DAY), FORWARD, 1)))
                .isInstanceOf(UnknownImpactDirectionException.class)
                .hasMessageContaining("X");
    }

    // ── 순서와 수 ──────────────────────────────────────────────

    @Test
    void 영향_대상은_깊이_개체_유형_개체_식별자_순이고_유형별_깊이별_수를_함께_돌려준다() {
        String p = "imp-order";
        EntityRef start = entity(p, "T", "start");
        // 깊이 1: 유형 a 하나, 유형 B 둘, 유형 가 하나. 넣는 순서는 섞는다.
        EntityRef lower = entity(p, "a", "x");
        EntityRef korean = entity(p, "가", "x");
        EntityRef upper2 = entity(p, "B", "y");
        EntityRef upper1 = entity(p, "B", "x");
        for (EntityRef e : List.of(lower, korean, upper2, upper1)) {
            relation(start, e, "R", "2026-01-01", null);
        }
        // 깊이 2: 유형 B 하나.
        EntityRef deep = entity(p, "B", "deep");
        relation(korean, deep, "R", "2026-01-01", null);

        ImpactScope scope = impact.impactScope(request(List.of(start.id()), List.of(DAY), FORWARD, 3));

        // 글자 코드 순: B < a < 가.
        assertThat(scope.targets()).extracting(ImpactTarget::entityId)
                .containsExactly(upper1.id(), upper2.id(), lower.id(), korean.id(), deep.id());
        assertThat(scope.targets()).extracting(ImpactTarget::entityType).containsExactly("B", "B", "a", "가", "B");
        assertThat(scope.countsByType()).containsExactly(Map.entry("B", 3), Map.entry("a", 1), Map.entry("가", 1));
        assertThat(scope.countsByDepth()).containsExactly(Map.entry(1, 4), Map.entry(2, 1));
    }

    @Test
    void 같은_요청을_두_번_하면_결과와_순서가_같다() {
        String p = "imp-twice";
        for (String key : List.of("k3", "k1", "k5", "k2", "k4")) {
            rel(p, "a", key, "R");
            rel(p, key, "shared", "R");
        }
        ImpactRequest request = request(List.of(id(p, "a")), List.of(DAY.minusDays(1), DAY), FORWARD, 3);

        ImpactScope first = impact.impactScope(request);
        ImpactScope second = impact.impactScope(request);

        assertThat(first.targets()).hasSize(6);
        assertThat(second).isEqualTo(first);
    }

    // ── 빈 결과, 큰 입력, Pack 사이의 경계 ───────────────────────────

    @Test
    void 관계가_없는_개체는_빈_영향_범위를_돌려준다() {
        EntityRef alone = entity("imp-alone", "T", "a");

        ImpactScope scope = impact.impactScope(request(List.of(alone.id()), List.of(DAY), FORWARD, 3));

        assertThat(scope.targets()).isEmpty();
        assertThat(scope.countsByType()).isEmpty();
        assertThat(scope.countsByDepth()).isEmpty();
        assertThat(scope.truncated()).isFalse();
    }

    @Test
    void 대상이_많아도_모두_찾고_정해진_순서로_돌려준다() {
        String p = "imp-many";
        int width = 200;
        for (int i = 0; i < width; i++) {
            // 넣는 순서를 섞는다 (7 은 200 과 서로소다).
            String key = String.format("k%03d", (i * 7) % width);
            rel(p, "a", key, "R");
            rel(p, key, "leaf-" + key, "R");
        }

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 2));

        assertThat(scope.targets()).hasSize(400);
        assertThat(scope.countsByDepth()).containsExactly(Map.entry(1, 200), Map.entry(2, 200));
        assertThat(scope.targets().get(0).entityId()).isEqualTo(id(p, "k000"));
        assertThat(scope.targets().get(199).entityId()).isEqualTo(id(p, "k199"));
        assertThat(scope.targets().get(200).entityId()).isEqualTo(id(p, "leaf-k000"));
        assertThat(scope.truncated()).isFalse();
    }

    @Test
    void 긴_사슬도_끝까지_따라가고_최대_깊이가_아주_커도_끝난다() {
        String p = "imp-chain";
        int length = 300;
        for (int i = 0; i < length; i++) {
            rel(p, String.format("n%03d", i), String.format("n%03d", i + 1), "R");
        }

        ImpactScope scope = impact
            .impactScope(request(List.of(id(p, "n000")), List.of(DAY), FORWARD, Integer.MAX_VALUE));

        assertThat(scope.targets()).hasSize(length);
        ImpactTarget last = scope.targets().get(length - 1);
        assertThat(last.entityId()).isEqualTo(id(p, "n300"));
        assertThat(last.depth()).isEqualTo(length);
        assertThat(last.path().relations()).hasSize(length);
        assertThat(scope.truncated()).isFalse();
    }

    @Test
    void 기본_평면_밖의_글자도_저장소와_같은_글자_코드_순으로_정렬한다() {
        // U+FF5E 는 U+1F600 보다 글자 코드가 작다. UTF-16 단위로 비교하면 순서가 뒤집힌다.
        String p = "imp-astral";
        rel(p, "a", "\uD83D\uDE00", "R");
        rel(p, "a", "\uFF5E", "R");

        ImpactScope scope = impact.impactScope(request(List.of(id(p, "a")), List.of(DAY), FORWARD, 1));

        assertThat("\uFF5E".compareTo("\uD83D\uDE00")).isPositive();
        assertThat(scope.targets()).extracting(ImpactTarget::entityId)
                .containsExactly(id(p, "\uFF5E"), id(p, "\uD83D\uDE00"));
        assertThat(CodePointOrder.compare("ab", "a")).isPositive();
        assertThat(CodePointOrder.compare("a", "a")).isZero();
        assertThat(CodePointOrder.compareLists(List.of("a"), List.of("a", "b"))).isNegative();
    }

    @Test
    void 다른_Pack의_같은_키인_개체와_섞이지_않는다() {
        rel("imp-here", "a", "b", "R");
        rel("imp-there", "a", "c", "R");

        ImpactScope scope = impact.impactScope(request(List.of(id("imp-here", "a")), List.of(DAY), FORWARD, 3));

        assertThat(scope.targets()).extracting(ImpactTarget::entityId).containsExactly(id("imp-here", "b"));
    }

    // ── 잘못된 입력 ─────────────────────────────────────────────

    @Test
    void 없는_개체에서는_출발할_수_없다() {
        assertThatThrownBy(
                () -> impact.impactScope(request(List.of("imp-none|T|missing"), List.of(DAY), FORWARD, 3)))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("imp-none|T|missing");
    }

    @Test
    void 변동_대상과_기준_날짜와_방향은_반드시_있어야_하고_최대_깊이는_1_이상이다() {
        List<String> starts = List.of("p|T|a");
        assertThatThrownBy(() -> new ImpactRequest(List.of(), List.of(DAY), FORWARD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("출발 개체");
        assertThatThrownBy(() -> new ImpactRequest(starts, List.of(), FORWARD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("기준 날짜");
        assertThatThrownBy(() -> new ImpactRequest(starts, null, FORWARD, 3)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("기준 날짜");
        assertThatThrownBy(() -> new ImpactRequest(starts, List.of(DAY), null, 3))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("영향 방향");
        assertThatThrownBy(() -> new ImpactRequest(starts, List.of(DAY), FORWARD, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최대 깊이");
    }

    @Test
    void 요청의_변동_대상과_날짜는_넣은_순서와_무관하게_같은_요청이_된다() {
        ImpactRequest one = new ImpactRequest(List.of("p|T|b", "p|T|a", "p|T|a"), List.of(DAY, DAY.minusDays(1)),
                FORWARD, 3);
        ImpactRequest other = new ImpactRequest(List.of("p|T|a", "p|T|b"), List.of(DAY.minusDays(1), DAY),
                new LinkedHashMap<>(FORWARD), 3);

        assertThat(one).isEqualTo(other);
        assertThat(one.startEntityIds()).containsExactly("p|T|a", "p|T|b");
        assertThat(one.asOfDates()).containsExactly(DAY.minusDays(1), DAY);
    }

    @Test
    void 출발_개체와_날짜와_방향의_값_하나하나도_비어_있을_수_없다() {
        List<String> starts = List.of("p|T|a");
        assertThatThrownBy(() -> new ImpactRequest(List.of("p|T|a", ""), List.of(DAY), FORWARD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("출발 개체");
        assertThatThrownBy(() -> new ImpactRequest(java.util.Arrays.asList("p|T|a", null), List.of(DAY), FORWARD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("출발 개체");
        assertThatThrownBy(() -> new ImpactRequest(List.of("p|T|\uD800"), List.of(DAY), FORWARD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("깨진 글자");
        assertThatThrownBy(() -> new ImpactRequest(starts, java.util.Arrays.asList(DAY, null), FORWARD, 3))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("기준 날짜");
        assertThatThrownBy(() -> new ImpactRequest(starts, List.of(DAY), Map.of("", ImpactDirection.BOTH), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("관계 유형");
        Map<String, ImpactDirection> noValue = new LinkedHashMap<>();
        noValue.put("R", null);
        assertThatThrownBy(() -> new ImpactRequest(starts, List.of(DAY), noValue, 3))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("영향 방향");
    }

    @Test
    void 출발_개체_여럿_가운데_하나만_없어도_어느_것인지_알린다() {
        EntityRef here = entity("imp-partly", "T", "a");

        assertThatThrownBy(() -> impact
            .impactScope(request(List.of(here.id(), "imp-partly|T|missing"), List.of(DAY), FORWARD, 3)))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("imp-partly|T|missing");
    }

    @Test
    void 출발_개체가_서로_다른_Pack에_있으면_거부한다() {
        EntityRef here = entity("imp-mix-1", "T", "a");
        EntityRef there = entity("imp-mix-2", "T", "a");

        assertThatThrownBy(
                () -> impact.impactScope(request(List.of(here.id(), there.id()), List.of(DAY), FORWARD, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imp-mix-1")
                .hasMessageContaining("imp-mix-2");
    }

    @Test
    void 방향을_하나도_넘겨받지_않았으면_관계가_없을_때만_계산된다() {
        EntityRef alone = entity("imp-nodir", "T", "alone");
        rel("imp-nodir", "a", "b", "R");

        assertThat(impact.impactScope(request(List.of(alone.id()), List.of(DAY), Map.of(), 3)).targets()).isEmpty();
        assertThatThrownBy(
                () -> impact.impactScope(request(List.of(id("imp-nodir", "a")), List.of(DAY), Map.of(), 3)))
                .isInstanceOf(UnknownImpactDirectionException.class);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private static ImpactRequest request(List<String> starts, List<LocalDate> dates, Map<String, ImpactDirection> dirs,
            int maxDepth) {
        return new ImpactRequest(starts, dates, dirs, maxDepth);
    }

    private static String id(String pack, String key) {
        return new EntityRef(pack, "T", key).id();
    }

    private EntityRef entity(String pack, String type, String key) {
        EntityRef ref = new EntityRef(pack, type, key);
        store.saveEntity(ref, RECORDED_AT);
        return ref;
    }

    /** 유형이 T 인 두 개체를 2026-01-01 부터 끝없이 잇는다. */
    private String rel(String pack, String sourceKey, String targetKey, String type) {
        return relBetween(pack, sourceKey, targetKey, type, "2026-01-01", null);
    }

    private String relBetween(String pack, String sourceKey, String targetKey, String type, String from, String to) {
        return relation(entity(pack, "T", sourceKey), entity(pack, "T", targetKey), type, from, to);
    }

    private String relation(EntityRef source, EntityRef target, String type, String from, String to) {
        ValidityPeriod period = new ValidityPeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to));
        return store.saveRelation(new NewRelation(source, target, type, period, SRC_1), RECORDED_AT);
    }

    private static ImpactTarget target(ImpactScope scope, String entityId) {
        return scope.targets().stream().filter(t -> t.entityId().equals(entityId)).findFirst().orElseThrow();
    }

    private static List<String> pathOf(ImpactScope scope, String entityId) {
        return target(scope, entityId).path().relations().stream().map(Relation::id).toList();
    }
}
