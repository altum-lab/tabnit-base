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
                "관계 조회: 변동 대상", "관계 조회: 가운데 층의 개체" }) {
            assertThat(result.lines()).as(what + "\n" + all)
                .anyMatch(line -> line.contains(what) && line.matches(".*가운데 +[0-9.]+ ms.*가장 큼 +[0-9.]+ ms.*6번.*"));
        }
        assertThat(all).contains("변동 하나에", "저장소의 크기", "시간을 많이 쓴 질의");
        assertThat(temp.resolve("slow.log")).isRegularFile();
    }
}
