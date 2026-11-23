package com.altumlab.tabnit.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.altumlab.tabnit.core.EntityRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 항목의 키를 잇는 규칙은 core 의 식별자와 같아야 한다 (D2 AD-65, AD-122).
 * link 가 그 규칙을 따로 구현하므로, core 가 규칙을 바꾸면 이 시험이 알려 준다.
 */
class LinkOrderTest {

    @Test
    void 값을_잇는_규칙이_core의_식별자와_같다() {
        for (String key : List.of("K-1", "a|b", "c\\d", "\\|", "|", "끝\\", "가 나", "😀|x")) {
            assertThat(LinkOrder.join(List.of("p", "T", key))).isEqualTo(new EntityRef("p", "T", key).id());
        }
    }

    @Test
    void 비교는_글자_코드_순이고_보충_문자를_BMP_뒤에_둔다() {
        List<String> sorted = new ArrayList<>(List.of("b", "😀", "Ａ", "a", "", "가", "B", "ab"));
        sorted.sort(LinkOrder.STRINGS);

        // UTF-16 단위로 견주면 보충 문자(U+1F600)가 U+FF21 보다 앞에 온다. 글자 코드 순에서는 뒤다.
        assertThat(sorted).containsExactly("", "B", "a", "ab", "b", "가", "Ａ", "😀");
    }

    @Test
    void 이은_글자를_나누면_원래_값들이_그대로_나온다() {
        // 빈 값, 세로 막대만 있는 값, 역빗금으로 끝나는 값, 둘이 섞인 값, 보충 문자.
        List<List<String>> cases = List.of(List.of(""), List.of("", ""), List.of("가", "", "나"), List.of("|"),
                List.of("끝\\"), List.of("\\|", "|\\", "\\\\"), List.of("A", "유형", "키|\\", "이름"),
                List.of("\uD83D\uDE00|", "가"));
        for (List<String> values : cases) {
            assertThat(LinkOrder.parts(LinkOrder.join(values))).as(values.toString()).isEqualTo(values);
        }
        // 마지막 값은 lastPart 가 주는 것과 같다.
        assertThat(LinkOrder.lastPart(LinkOrder.join(List.of("가", "나|다\\")))).isEqualTo("나|다\\");
    }
}
