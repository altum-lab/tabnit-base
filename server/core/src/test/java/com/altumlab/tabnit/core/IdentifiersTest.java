package com.altumlab.tabnit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 식별자 문자열을 만드는 규칙 (D5 1절, D2 AD-65). */
class IdentifiersTest {

    @Test
    void 값을_세로_막대로_이어_붙인다() {
        assertThat(Identifiers.join("p", "T", "k1")).isEqualTo("p|T|k1");
    }

    @Test
    void 값_안의_세로_막대와_역빗금_앞에_역빗금을_붙인다() {
        assertThat(Identifiers.join("a|b", "c\\d")).isEqualTo("a\\|b|c\\\\d");
    }

    @Test
    void 구분자가_든_값과_나뉜_값은_식별자가_다르다() {
        // 이스케이프가 없으면 둘 다 "a|b|c" 가 된다.
        assertThat(Identifiers.join("a|b", "c")).isNotEqualTo(Identifiers.join("a", "b|c"));
        assertThat(Identifiers.join("a|b", "c")).isNotEqualTo(Identifiers.join("a", "b", "c"));
        // 역빗금으로 끝나는 값도 다음 구분자와 섞이지 않는다.
        assertThat(Identifiers.join("a\\", "b")).isNotEqualTo(Identifiers.join("a\\|b"));
    }

    @Test
    void 개체의_식별자는_Pack과_유형과_키로_정해진다() {
        assertThat(new EntityRef("p", "T", "k1").id()).isEqualTo("p|T|k1");
        assertThat(new EntityRef("p", "T", "k1").id()).isEqualTo(new EntityRef("p", "T", "k1").id());
    }

    @Test
    void 짝_없는_대리_문자가_든_값은_거부하고_짝이_맞는_글자는_받는다() {
        assertThatThrownBy(() -> new EntityRef("p", "T", "a\uD800b")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("깨진 글자");
        assertThatThrownBy(() -> new EntityRef("p", "T", "a\uDE00")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("깨진 글자");
        assertThat(new EntityRef("p", "T", "\uD83D\uDE00").entityKey()).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void NUL_글자가_든_값은_거부한다() {
        assertThatThrownBy(() -> new EntityRef("p", "T", "a\u0000b")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NUL");
    }

    @Test
    void 비어_있는_값은_거부한다() {
        assertThatThrownBy(() -> new EntityRef("", "T", "k")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Pack");
        assertThatThrownBy(() -> new EntityRef("p", null, "k")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("유형");
        assertThatThrownBy(() -> new EntityRef("p", "T", "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("키");
    }
}
