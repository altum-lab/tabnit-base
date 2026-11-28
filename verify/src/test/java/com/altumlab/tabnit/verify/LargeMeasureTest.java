package com.altumlab.tabnit.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A-07: 큰 시험 자료를 넣은 제품에서 영향 범위 계산과 그 앞뒤의 한 줄기를 재어 적는다.
 *
 * <p>여기서는 작은 자료로 재는 일이 끝까지 도는지와 적는 것의 모양만 본다. 걸린 시간의 값은 보지 않는다
 * (돌리는 곳마다 다르다). 제품을 실제로 띄운다. Docker 가 켜져 있어야 한다.
 */
class LargeMeasureTest {

    @Test
    void 목표는_영향_대상_천_개쯤에서_영점일_초_만_개쯤에서_일_초이고_그_아래로는_잡지_않는다() {
        assertThat(LargeMeasure.targetMillis(0)).isEqualTo(100.0);
        assertThat(LargeMeasure.targetMillis(110)).isEqualTo(100.0);
        assertThat(LargeMeasure.targetMillis(1_000)).isEqualTo(100.0);
        assertThat(LargeMeasure.targetMillis(5_000)).isEqualTo(500.0);
        assertThat(LargeMeasure.targetMillis(10_000)).isEqualTo(1_000.0);
        assertThat(LargeMeasure.targetMillis(10_100)).isEqualTo(1_010.0);
    }

    @Test
    void p95_는_작은_것부터_늘어놓아_백에_아흔다섯째의_자리의_값이다() {
        // 가장 가까운 자리로 잡는다 (사이를 채워 셈하지 않는다): 잰 값 가운데 하나다.
        java.util.List<Long> hundred = new java.util.ArrayList<>();
        for (long value = 100; value >= 1; value--) {
            hundred.add(value);
        }
        assertThat(LargeMeasure.percentile95(hundred)).isEqualTo(95);
        assertThat(LargeMeasure.percentile95(java.util.List.of(7L))).isEqualTo(7);
        assertThat(LargeMeasure.percentile95(java.util.List.of(3L, 1L, 2L))).isEqualTo(3);
        // 스무 개면 열아홉째다.
        java.util.List<Long> twenty = new java.util.ArrayList<>();
        for (long value = 1; value <= 20; value++) {
            twenty.add(value * 10);
        }
        assertThat(LargeMeasure.percentile95(twenty)).isEqualTo(190);
    }

    @TempDir
    Path temp;

    @Test
    void 넣은_뒤에_영향_범위_계산과_조회를_되풀이해_재고_가운데_값과_가장_큰_값을_적는다() throws IOException {
        LargePack.Spec spec = new LargePack.Spec("large-measure", 300, 2000, 3, 3);
        LargePack.Expected expected = LargePack.generate(spec, temp);

        LargePackRun.Result result = LargePackRun.run(expected, temp, new LargeMeasure(spec, 2, temp.resolve("slow.log")));

        String all = String.join("\n", result.lines());
        assertThat(result.failed()).as(all).isFalse();
        // 수를 견준 뒤에 잰다. 재면서도 계산한 영향 대상의 수를 셈한 수와 견준다.
        assertThat(all).contains("결과: 통과");
        assertThat(all).contains("같음  영향 범위 계산 (core) 의 영향 대상의 수: 110");
        for (String what : new String[] { "영향 범위 계산 (core)", "영향 범위 조회: 첫 쪽 50개", "영향 범위 조회: 마지막 쪽",
                "영향 범위 조회: 한 쪽 200개", "영향 범위 조회: 대상 하나", "영향 범위 조회: 끝까지", "조치 조회: 첫 쪽 50개",
                "관계 조회: 변동 대상", "관계 조회: 가운데 층의 개체", "관계 지도: 변동 대상을 가운데로 깊이 3" }) {
            assertThat(result.lines()).as(what + "\n" + all)
                .anyMatch(line -> line.contains(what) && line.matches(".*가운데 +[0-9.]+ ms.*가장 큼 +[0-9.]+ ms.*6번.*"));
        }
        assertThat(all).contains("변동 하나에", "저장소의 크기", "시간을 많이 쓴 질의");
        // 목표와 견준 줄이 있다 (D2 AD-355). 넘어도 실패로 끝내지 않으므로 여기서는 줄의 모양만 본다.
        assertThat(result.lines()).anyMatch(line -> line
            .matches("  (목표 안|목표 넘음)  영향 범위 계산 \\(core\\) 의 가운데 값 [0-9.]+ ms, 목표 100\\.0 ms \\(영향 대상 110개\\)"));
        // 동시에 열을 돌려 잰 p95 를 목표와 견준 줄이 있다 (카드 A-16, D2 AD-427). 변동 3개 x 2번을 열이 함께 돈다.
        assertThat(result.lines()).as(all).anyMatch(line -> line.matches(
                "  (목표 안|목표 넘음)  영향 범위 계산 \\(core\\) 동시 10: p95 [0-9.]+ ms, 목표 2000\\.0 ms \\(계산 60번, 가운데 [0-9.]+ ms, 가장 큼 [0-9.]+ ms\\)"));
        // 두 판의 비교를 잰 줄이 있다.
        assertThat(result.lines()).as(all)
            .anyMatch(line -> line.contains("두 판의 비교") && line.matches(".*가운데 +[0-9.]+ ms.*가장 큼 +[0-9.]+ ms.*2번.*"));
        assertThat(temp.resolve("slow.log")).isRegularFile();
    }
}
