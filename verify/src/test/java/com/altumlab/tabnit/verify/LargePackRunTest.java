package com.altumlab.tabnit.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-07: 생성기가 만든 Pack 이 제품에 코드 수정 없이 들어가고, 넣은 뒤의 수가 셈한 수와 같다.
 *
 * <p>제품을 실제로 띄운다 (빈 PostgreSQL 과 함께). Docker 가 켜져 있어야 한다. 작은 자료로 본다:
 * 큰 자료로 재는 일은 bash verify/large.sh 와 성능 점검(A-07)이 한다.
 */
class LargePackRunTest {

    @TempDir
    Path temp;

    @Test
    void 만든_Pack_을_제품에_넣으면_개체와_관계와_변동과_영향_대상과_조치의_수가_셈한_수와_같다() throws IOException {
        // 층 넷. 한 쪽(50개)을 넘는 영향 대상이 나오게 관계를 넉넉히 둔다.
        LargePack.Expected expected = LargePack.generate(new LargePack.Spec("large-run", 240, 1800, 4, 3), temp);
        assertThat(expected.changes()).anySatisfy(change -> assertThat(change.impactTargets()).isGreaterThan(50));

        LargePackRun.Result result = LargePackRun.run(expected, temp);

        assertThat(result.lines()).as(String.join("\n", result.lines())).noneMatch(line -> line.contains("실패"));
        assertThat(result.failed()).isFalse();
        assertThat(result.lines()).contains("  같음  개체의 수: 240", "  같음  관계의 수: 1800", "  같음  변동의 수: 3");
        assertThat(result.lines().get(result.lines().size() - 1)).startsWith("결과: 통과");
    }

    @Test
    void 셈한_수와_다르면_어느_수가_다른지_알리고_실패로_끝난다() throws IOException {
        LargePack.Expected made = LargePack.generate(new LargePack.Spec("large-wrong", 40, 60, 2, 2), temp);
        // 셈한 수를 일부러 틀리게 고친다.
        LargePack.ExpectedChange first = made.changes().get(0);
        LargePack.Expected wrong = new LargePack.Expected(made.packId(), made.entities() + 1, made.relations(), made.steps(),
                java.util.List.of(new LargePack.ExpectedChange(first.itemKey(), first.impactTargets() + 7, first.actions()),
                        made.changes().get(1)));

        LargePackRun.Result result = LargePackRun.run(wrong, temp);

        assertThat(result.failed()).isTrue();
        assertThat(result.lines()).contains("  실패  개체의 수: 셈한 수 41, 넣은 뒤의 수 40");
        assertThat(result.lines()).anyMatch(line -> line.startsWith("  실패  " + first.itemKey() + " 의 영향 대상의 수"));
        assertThat(result.lines().get(result.lines().size() - 1)).isEqualTo("결과: 실패");
    }
}
