package com.altumlab.tabnit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A-14 수용 기준: 식별자로 개체(Pack, 유형, 키)를 읽는다. 없으면 없다고 답한다.
 *
 * <p>모든 이름과 값은 지어낸 것이다. 시험마다 다른 Pack 식별자를 써서 서로 섞이지 않게 한다.
 */
class EntityQueryTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final RelationStore store = new RelationStore(CoreTestDatabase.dataSource());

    private final EntityQuery query = new EntityQuery(CoreTestDatabase.dataSource());

    @Test
    void 식별자로_개체의_Pack과_유형과_키를_읽는다() {
        EntityRef saved = new EntityRef("q-find", "TypeA", "K-1");
        store.saveEntity(saved, RECORDED_AT);

        assertThat(query.find("q-find|TypeA|K-1")).contains(saved);
    }

    @Test
    void 없는_개체는_오류가_아니라_없다고_답한다() {
        store.saveEntity(new EntityRef("q-none", "TypeA", "K-1"), RECORDED_AT);

        assertThat(query.find("q-none|TypeA|K-2")).isEmpty();
        assertThat(query.find("q-none|TypeB|K-1")).isEmpty();
        assertThat(query.find("q-none-other|TypeA|K-1")).isEmpty();
        // 식별자의 모양이 아닌 글자도 없는 개체일 뿐이다.
        assertThat(query.find("아무 글자")).isEmpty();
        // 조회는 아무것도 만들지 않는다.
        assertThat(query.find("q-none|TypeA|K-2")).isEmpty();
    }

    @Test
    void 식별자에_구분_글자가_든_개체도_저장한_그대로_읽는다() {
        // 유형과 키에 세로 막대와 역빗금이 들었다. 식별자를 풀어 읽지 않고 저장된 값을 돌려준다.
        for (EntityRef saved : List.of(new EntityRef("q-chars", "Type|A", "K\\1"),
                new EntityRef("q-chars", "Type", "A|K\\1"), new EntityRef("q-chars", "유형 가", "키 % _ ' \" 😀"),
                new EntityRef("q-chars", "T", "a|b|c"))) {
            store.saveEntity(saved, RECORDED_AT);

            assertThat(query.find(saved.id())).as(saved.id()).contains(saved);
        }
    }

    @Test
    void 여러_식별자를_한_번에_물으면_있는_것만_식별자_순으로_돌려준다() {
        List<EntityRef> saved = List.of(new EntityRef("q-many", "TypeB", "K-1"), new EntityRef("q-many", "TypeA", "K-2"),
                new EntityRef("q-many", "TypeA", "K-10"), new EntityRef("q-many", "TypeA", "가"));
        saved.forEach(entity -> store.saveEntity(entity, RECORDED_AT));

        List<EntityRef> found = query.findAll(Arrays.asList("q-many|TypeB|K-1", "q-many|TypeA|없는 키",
                "q-many|TypeA|가", "q-many|TypeA|K-2", "q-many|TypeB|K-1", "q-many|TypeA|K-10"));

        // 글자 코드 순이다: K-10 이 K-2 보다 앞선다. 같은 식별자를 두 번 물어도 한 번만 나온다.
        assertThat(found).extracting(EntityRef::id)
            .containsExactly("q-many|TypeA|K-10", "q-many|TypeA|K-2", "q-many|TypeA|가", "q-many|TypeB|K-1");
        assertThat(query.findAll(List.of())).isEmpty();
        assertThat(query.findAll(List.of("q-many|TypeA|없는 키"))).isEmpty();
    }

    @Test
    void 닮은_식별자를_같은_것으로_보지_않는다() {
        // 대소문자, 앞뒤 빈칸, 줄바꿈, 다른 Pack. 글자 그대로 같을 때만 같은 개체다.
        List<EntityRef> saved = List.of(new EntityRef("q-alike", "T", "a"), new EntityRef("q-alike", "T", "A"),
                new EntityRef("q-alike", "T", " a"), new EntityRef("q-alike", "T", "a "),
                new EntityRef("q-alike", "T", "a\nb"), new EntityRef("q-alike-2", "T", "a"));
        saved.forEach(entity -> store.saveEntity(entity, RECORDED_AT));

        assertThat(saved).allSatisfy(entity -> assertThat(query.find(entity.id())).contains(entity));
        assertThat(query.find("q-alike|t|a")).isEmpty();
        assertThat(query.find("q-alike|T|a  ")).isEmpty();
        // 찾는 글자에 든 퍼센트와 밑줄은 아무 글자나 뜻하지 않는다.
        assertThat(query.find("q-alike|T|%")).isEmpty();
        assertThat(query.find("q-alike|T|_")).isEmpty();
        // 차례는 식별자의 글자 코드 순이다. (Pack, 유형, 키) 순이 아니다: 붙임표가 세로 막대보다 앞이라
        // "q-alike-2|…" 가 "q-alike|…" 보다 먼저 온다. 결과가 Pack 별로 모여 나온다고 보면 안 된다.
        assertThat(query.findAll(List.of("q-alike|T|a", "q-alike-2|T|a"))).extracting(EntityRef::packId)
            .containsExactly("q-alike-2", "q-alike");
    }

    @Test
    void 여럿을_읽을_때의_차례는_보충_문자가_섞여도_글자_코드_순이다() {
        // UTF-16 단위로 견주면 보충 문자(U+1F600)가 U+FF5E 보다 앞에 온다. 글자 코드 순에서는 뒤다.
        List<String> keys = List.of("\uFF5E", "\uD83D\uDE00", "z", "\uFB01", "가", "\uD83D\uDE00x");
        List<String> ids = new ArrayList<>();
        for (String key : keys) {
            EntityRef entity = new EntityRef("q-points", "T", key);
            store.saveEntity(entity, RECORDED_AT);
            ids.add(entity.id());
        }

        assertThat(query.findAll(ids)).extracting(EntityRef::entityKey)
            .containsExactly("z", "가", "\uFB01", "\uFF5E", "\uD83D\uDE00", "\uD83D\uDE00x");
    }

    @Test
    void 저장될_수_없을_만큼_긴_식별자와_수만_개의_식별자를_물어도_오류가_없다() {
        // 색인의 한도를 넘는 길이다. 그런 개체는 저장될 수 없으므로 없다고 답한다.
        StringBuilder longId = new StringBuilder("q-long|T|");
        for (int i = 0; longId.length() < 20000; i++) {
            longId.append(Integer.toString(i * 7919 + 13, 36));
        }
        assertThat(query.find(longId.toString())).isEmpty();
        assertThat(query.findAll(List.of(longId.toString()))).isEmpty();

        store.saveEntity(new EntityRef("q-long", "T", "K-25000"), RECORDED_AT);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50000; i++) {
            ids.add("q-long|T|K-" + i);
        }
        assertThat(query.findAll(ids)).extracting(EntityRef::entityKey).containsExactly("K-25000");
    }

    @Test
    void 물은_차례가_달라도_결과가_같다() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            EntityRef entity = new EntityRef("q-order", "T" + (i % 3), "K-" + i);
            store.saveEntity(entity, RECORDED_AT);
            ids.add(entity.id());
        }
        List<String> reversed = new ArrayList<>(ids);
        java.util.Collections.reverse(reversed);

        assertThat(query.findAll(reversed)).isEqualTo(query.findAll(ids)).hasSize(30);
    }

    @Test
    void 아주_많은_식별자를_물어도_모두_답한다() {
        List<String> ids = new ArrayList<>();
        int count = 3000;
        for (int i = 0; i < count; i++) {
            EntityRef entity = new EntityRef("q-large", "TypeA", "K-" + (100000 + i));
            if (i % 2 == 0) {
                store.saveEntity(entity, RECORDED_AT);
            }
            ids.add(entity.id());
        }

        List<EntityRef> found = query.findAll(ids);

        assertThat(found).hasSize(count / 2);
        assertThat(found.get(0).entityKey()).isEqualTo("K-100000");
        assertThat(found.get(found.size() - 1).entityKey()).isEqualTo("K-102998");
    }

    @Test
    void 비어_있거나_저장할_수_없는_글자가_든_식별자는_받지_않는다() {
        assertThatThrownBy(() -> query.find("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.find(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.find("a\u0000b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.find("a\uD800b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.findAll(null)).isInstanceOf(NullPointerException.class);
        // 여럿 가운데 어느 것이 틀렸는지 알린다.
        assertThatThrownBy(() -> query.findAll(Arrays.asList("q|T|k", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2번째");
        assertThatThrownBy(() -> query.findAll(Arrays.asList("q|T|k", null)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
