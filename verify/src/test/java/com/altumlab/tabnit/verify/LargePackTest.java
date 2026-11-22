package com.altumlab.tabnit.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-07 수용 기준: 개체 수와 관계 수를 정해 지어낸 자료를 만든다. 성능 점검에 쓴다.
 *
 * <p>생성기가 만든 Pack 폴더의 모양과 수를 본다. 제품을 띄우지 않는다: 만든 Pack 이 제품에 실제로 들어가는지는
 * {@link LargePackRunTest} 가 본다. 난수를 쓰지 않으므로 같은 값에는 글자까지 같은 자료가 나와야 한다.
 */
class LargePackTest {

    @TempDir
    Path temp;

    @Test
    void 정한_수만큼의_개체와_관계를_층으로_나눠_만들고_셈한_수를_함께_낸다() throws IOException {
        LargePack.Spec spec = new LargePack.Spec("large-test", 103, 250, 4, 5);

        LargePack.Expected expected = LargePack.generate(spec, temp);

        Path pack = temp.resolve("large-test");
        assertThat(pack.resolve("pack.ttl")).isRegularFile();
        assertThat(pack.resolve("ontology.ttl")).isRegularFile();
        assertThat(pack.resolve("rules/rules.ttl")).isRegularFile();
        // 개체는 층마다 고르게 나눈다 (나머지는 앞 층부터 하나씩). 관계는 이웃한 층 사이마다 고르게 나눈다.
        assertThat(rows(pack.resolve("data/layer-01-v1.csv"))).hasSize(26);
        assertThat(rows(pack.resolve("data/layer-02-v1.csv"))).hasSize(26);
        assertThat(rows(pack.resolve("data/layer-03-v1.csv"))).hasSize(26);
        assertThat(rows(pack.resolve("data/layer-04-v1.csv"))).hasSize(25);
        assertThat(rows(pack.resolve("data/link-01-v1.csv"))).hasSize(84);
        assertThat(rows(pack.resolve("data/link-02-v1.csv"))).hasSize(83);
        assertThat(rows(pack.resolve("data/link-03-v1.csv"))).hasSize(83);
        assertThat(expected.entities()).isEqualTo(103);
        assertThat(expected.relations()).isEqualTo(250);
        // 관계의 두 끝은 있는 개체이고, 같은 관계가 두 번 나오지 않는다.
        for (int gap = 1; gap <= 3; gap++) {
            Set<String> upper = keys(pack.resolve(String.format("data/layer-%02d-v1.csv", gap)));
            Set<String> lower = keys(pack.resolve(String.format("data/layer-%02d-v1.csv", gap + 1)));
            List<String> links = rows(pack.resolve(String.format("data/link-%02d-v1.csv", gap)));
            assertThat(new HashSet<>(links)).hasSameSizeAs(links);
            for (String link : links) {
                String[] ends = link.split(",");
                assertThat(upper).contains(ends[0]);
                assertThat(lower).contains(ends[1]);
            }
        }
        // 바뀐 판: 첫 층의 앞에서부터 정한 수만큼의 행의 값이 다르다.
        List<String> before = rows(pack.resolve("data/layer-01-v1.csv"));
        List<String> after = rows(pack.resolve("data/layer-01-v2.csv"));
        assertThat(after).hasSameSizeAs(before);
        List<String> changedKeys = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                changedKeys.add(before.get(i).split(",")[0]);
            }
        }
        assertThat(changedKeys).hasSize(5);
        assertThat(expected.changes()).extracting(LargePack.ExpectedChange::itemKey).containsExactlyElementsOf(changedKeys);
        // 올리는 차례: 층, 그 사이의 관계, 그리고 바뀐 판.
        assertThat(expected.steps()).extracting(LargePack.Step::inputSource)
            .containsExactly("layer-01", "layer-02", "layer-03", "layer-04", "link-01", "link-02", "link-03", "layer-01");
        assertThat(expected.steps().get(7).effectiveDate()).isAfter(expected.steps().get(0).effectiveDate());
    }

    @Test
    void 변동마다_셈한_영향_대상의_수는_만든_관계를_따로_따라가_센_수와_같다() throws IOException {
        LargePack.Spec spec = new LargePack.Spec("large-count", 60, 140, 3, 4);

        LargePack.Expected expected = LargePack.generate(spec, temp);

        // 생성기의 셈과 따로, 만든 파일을 읽어 아래층으로 따라가 센다.
        Path pack = temp.resolve("large-count");
        TreeMap<String, List<String>> down = new TreeMap<>();
        for (int gap = 1; gap <= 2; gap++) {
            for (String link : rows(pack.resolve(String.format("data/link-%02d-v1.csv", gap)))) {
                String[] ends = link.split(",");
                down.computeIfAbsent(ends[0], key -> new ArrayList<>()).add(ends[1]);
            }
        }
        Set<String> lastLayer = keys(pack.resolve("data/layer-03-v1.csv"));
        for (LargePack.ExpectedChange change : expected.changes()) {
            Set<String> reached = new HashSet<>();
            List<String> frontier = List.of(change.itemKey());
            while (!frontier.isEmpty()) {
                List<String> next = new ArrayList<>();
                for (String key : frontier) {
                    for (String child : down.getOrDefault(key, List.of())) {
                        if (reached.add(child)) {
                            next.add(child);
                        }
                    }
                }
                frontier = next;
            }
            assertThat(change.impactTargets()).as(change.itemKey()).isEqualTo(reached.size());
            // 조치: 마지막 층에 닿은 대상마다 하나, 그리고 바뀐 개체 자신에 하나.
            long onLast = reached.stream().filter(lastLayer::contains).count();
            assertThat(change.actions()).as(change.itemKey()).isEqualTo((int) onLast + 1);
        }
        assertThat(expected.changes()).anySatisfy(change -> assertThat(change.impactTargets()).isPositive());
    }

    @Test
    void 한_개체에서_나가는_관계는_서로_겹치지_않는_아래층_개체에_닿아_영향_범위가_넓게_퍼진다() throws IOException {
        // A-07: 층마다 100개, 위층의 개체 하나에서 관계 10개. 깊이 1 에 10개, 깊이 2 에 10 x 10 = 100개가 닿는다.
        LargePack.Expected expected = LargePack.generate(new LargePack.Spec("large-wide", 300, 2000, 3, 4), temp);

        assertThat(expected.changes()).hasSize(4)
            .allSatisfy(change -> assertThat(change.impactTargets()).isEqualTo(110));
        // 아래층의 개체마다 들어오는 관계의 수도 고르다.
        TreeMap<String, Integer> incoming = new TreeMap<>();
        for (String link : rows(temp.resolve("large-wide/data/link-01-v1.csv"))) {
            incoming.merge(link.split(",")[1], 1, Integer::sum);
        }
        assertThat(incoming).hasSize(100);
        assertThat(incoming.values()).containsOnly(10);
    }

    @Test
    void 같은_값을_주면_글자까지_같은_자료가_나온다() throws IOException {
        LargePack.Spec spec = new LargePack.Spec("large-same", 80, 200, 4, 3);

        LargePack.Expected first = LargePack.generate(spec, temp.resolve("a"));
        LargePack.Expected second = LargePack.generate(spec, temp.resolve("b"));

        assertThat(second).isEqualTo(first);
        try (Stream<Path> files = Files.walk(temp.resolve("a"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path other = temp.resolve("b").resolve(temp.resolve("a").relativize(file).toString());
                assertThat(other).as(file.toString()).hasSameBinaryContentAs(file);
            }
        }
    }

    @Test
    void 가장_작은_자료와_변동이_없는_자료와_가득_찬_관계도_만든다() throws IOException {
        // 층 둘에 개체 하나씩, 관계 하나.
        LargePack.Expected smallest = LargePack.generate(new LargePack.Spec("large-min", 2, 1, 2, 1), temp);
        assertThat(smallest.changes()).singleElement().satisfies(change -> {
            assertThat(change.impactTargets()).isEqualTo(1);
            assertThat(change.actions()).isEqualTo(2);
        });
        // 변동이 없으면 바뀐 판도 없다.
        LargePack.Expected still = LargePack.generate(new LargePack.Spec("large-still", 10, 10, 2, 0), temp);
        assertThat(still.changes()).isEmpty();
        assertThat(still.steps()).hasSize(3);
        assertThat(temp.resolve("large-still/data/layer-01-v2.csv")).doesNotExist();
        // 이웃한 층의 모든 개체를 서로 잇는다 (5 x 5).
        LargePack.generate(new LargePack.Spec("large-full", 10, 25, 2, 1), temp);
        List<String> full = rows(temp.resolve("large-full/data/link-01-v1.csv"));
        assertThat(new HashSet<>(full)).hasSize(25);
        // 관계가 하나도 없는 자료.
        LargePack.Expected none = LargePack.generate(new LargePack.Spec("large-none", 10, 0, 2, 2), temp);
        assertThat(none.changes()).allSatisfy(change -> assertThat(change.impactTargets()).isZero());
    }

    @Test
    void 같은_이름으로_다시_만들면_앞서_만든_파일이_남지_않고_남의_폴더는_지우지_않는다() throws IOException {
        // 층 넷, 변동 있음 → 층 셋, 변동 없음. 앞의 넷째 층과 바뀐 판이 남으면 새 정의와 어긋난다.
        LargePack.generate(new LargePack.Spec("large-again", 40, 60, 4, 2), temp.resolve("a"));
        LargePack.generate(new LargePack.Spec("large-again", 40, 60, 3, 0), temp.resolve("a"));
        LargePack.generate(new LargePack.Spec("large-again", 40, 60, 3, 0), temp.resolve("b"));

        try (Stream<Path> files = Files.walk(temp.resolve("a"))) {
            List<String> made = files.filter(Files::isRegularFile).map(f -> temp.resolve("a").relativize(f).toString()).sorted().toList();
            try (Stream<Path> fresh = Files.walk(temp.resolve("b"))) {
                assertThat(made).isEqualTo(
                        fresh.filter(Files::isRegularFile).map(f -> temp.resolve("b").relativize(f).toString()).sorted().toList());
            }
        }
        // 이 생성기가 만들지 않은 폴더가 그 이름으로 있으면 지우지 않고 알린다.
        Path other = temp.resolve("c/large-other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("남의 파일.txt"), "지우면 안 된다", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> LargePack.generate(new LargePack.Spec("large-other", 10, 10, 2, 1), temp.resolve("c")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("지우지 않았습니다");
        assertThat(other.resolve("남의 파일.txt")).hasContent("지우면 안 된다");
    }

    @Test
    void 만들_수_없는_값은_무엇이_틀렸는지_알리고_아무것도_만들지_않는다() {
        String[][] wrong = { { "Large", "10", "10", "2", "1", "이름" }, { "", "10", "10", "2", "1", "이름" },
                { "p", "10", "10", "1", "1", "층" }, { "p", "10", "10", "22", "1", "층" }, { "p", "3", "1", "4", "1", "개체" },
                { "p", "10", "26", "2", "1", "관계" }, { "p", "10", "-1", "2", "1", "관계" }, { "p", "10", "10", "2", "6", "변동" },
                { "p", "10", "10", "2", "-1", "변동" } };
        for (String[] w : wrong) {
            assertThatThrownBy(() -> LargePack.generate(new LargePack.Spec(w[0], Integer.parseInt(w[1]),
                    Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4])), temp))
                .as(String.join(" ", w))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(w[5]);
        }
        assertThat(temp).isEmptyDirectory();
    }

    private static List<String> rows(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        return lines.subList(1, lines.size());
    }

    private static Set<String> keys(Path csv) throws IOException {
        Set<String> keys = new HashSet<>();
        for (String row : rows(csv)) {
            keys.add(row.split(",")[0]);
        }
        return keys;
    }
}
