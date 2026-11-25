package com.altumlab.tabnit.pack;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * B-17: 제약 검사기가 자료의 크기에 따라 걸리는 시간과 쓰는 메모리를 잰다. 시험이 아니라 재는 것이다 (D9 의 B-17).
 * 보통의 빌드에서는 돌지 않는다. 돌리는 법 (저장소의 server 폴더에서):
 *
 * <pre>TABNIT_MEASURE=constraints ./gradlew :pack:test --rerun --tests '*ConstraintCheckerMeasure*'</pre>
 *
 * <p>개체 하나에 속성 값 둘과 관계 하나를 둔 지어낸 자료다. 제약은 개수, 자료형, 범위, 유형의 넷이고 개체 열에 하나가
 * 어긴다. 결과는 build/measure/constraint-checker.txt 에 남는다. 걸린 시간은 잰 곳마다 다르다.
 */
@EnabledIfEnvironmentVariable(named = "TABNIT_MEASURE", matches = "constraints")
class ConstraintCheckerMeasure {

    private static final String CONSTRAINTS = """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex:  <urn:tabnit:pack:measure:> .
            ex:BaseShape a sh:NodeShape ;
                sh:targetClass ex:Base ;
                sh:property [ sh:path ex:title ; sh:minCount 1 ; sh:datatype xsd:string ] ;
                sh:property [ sh:path ex:count ; sh:datatype xsd:integer ; sh:minInclusive 1 ] ;
                sh:property [ sh:path ex:links ; sh:class ex:Other ; sh:maxCount 2 ] .
            """;

    @Test
    void 자료의_크기마다_걸린_시간과_메모리를_적는다() throws Exception {
        PackDefinition pack = new PackDefinition("measure", "이름표", 1, Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null)),
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET)),
                List.of(new AttributeDefinition("title", "제목", "Base", ValueFormat.STRING),
                        new AttributeDefinition("count", "수", "Base", ValueFormat.INTEGER)),
                List.of(), List.of(), List.of(), CONSTRAINTS);
        StringBuilder report = new StringBuilder("행의 수 (개체 + 관계 + 속성 값) | 위반 | 걸린 시간 (세 번의 가운데 값, 밀리초) | 늘어난 메모리 (MB)\n");
        for (int entities : new int[] { 2_500, 12_500, 25_000, 50_000, 100_000 }) {
            List<ConstraintData.Entity> list = new ArrayList<>();
            List<ConstraintData.Link> links = new ArrayList<>();
            List<ConstraintData.Value> values = new ArrayList<>();
            list.add(new ConstraintData.Entity("Other|hub", "Other"));
            for (int i = 0; i < entities; i++) {
                String id = "Base|B-" + i;
                list.add(new ConstraintData.Entity(id, "Base"));
                links.add(new ConstraintData.Link(id, "Other|hub", "links"));
                values.add(new ConstraintData.Value(id, "title", "제목 " + i));
                // 열에 하나는 수가 0 이다: 범위를 어긴다.
                values.add(new ConstraintData.Value(id, "count", i % 10 == 0 ? "0" : "5"));
            }
            ConstraintData data = new ConstraintData(list, links, values);
            int rows = list.size() + links.size() + values.size();
            List<Long> taken = new ArrayList<>();
            int found = 0;
            long grown = 0;
            for (int run = 0; run < 3; run++) {
                System.gc();
                long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long started = System.nanoTime();
                List<ConstraintViolation> violations = ConstraintChecker.check(pack, data);
                taken.add(System.nanoTime() - started);
                grown = Math.max(grown, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() - before);
                found = violations.size();
            }
            taken.sort(null);
            assertThat(found).isEqualTo((entities + 9) / 10);
            report.append(String.format(Locale.ROOT, "%d | %d | %.0f | %.0f%n", rows, found, taken.get(1) / 1e6,
                    grown / 1024.0 / 1024.0));
        }
        Path out = Path.of("build/measure/constraint-checker.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
    }
}
