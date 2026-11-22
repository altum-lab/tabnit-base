package com.altumlab.tabnit.verify;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 큰 시험 자료 생성기 (보드 T-07, D2 AD-350). 개체 수와 관계 수를 정해 지어낸 Pack 폴더 하나를 통째로 만든다.
 * 성능 점검(A-07)에 쓴다.
 *
 * <p>모양은 층이다. 개체 유형이 층 하나이고, 위층의 개체가 바로 아래층의 개체 여럿에 이어진다. 변동은 첫 층에서
 * 나고 영향은 아래층으로 퍼진다. 어느 업무 영역도 아닌 지어낸 이름만 쓴다.
 *
 * <p>난수와 현재 시각을 쓰지 않는다. 같은 값을 주면 글자까지 같은 자료가 나온다. 제품을 부르지 않는다:
 * 만든 자료에서 따로 셈한 수({@link Expected})를 함께 내고, 넣은 뒤의 수와 견주는 일은 {@link LargePackRun} 이 한다.
 */
final class LargePack {

    /** 첫 판의 적용일과 바뀐 판의 적용일. 지어낸 날짜다. */
    static final LocalDate FIRST_DATE = LocalDate.of(2026, 1, 1);

    static final LocalDate CHANGED_DATE = LocalDate.of(2026, 3, 1);

    /** 층의 수의 한도. Pack 이 적을 수 있는 최대 깊이(20)에 맞춘다: 깊이는 층의 수보다 하나 적다. */
    static final int MAX_LAYERS = 21;

    private static final Pattern PACK_ID = Pattern.compile("[a-z0-9-]{1,100}");

    private LargePack() {
    }

    /**
     * 만들 자료.
     *
     * @param packId Pack 의 식별자이자 폴더의 이름. 영어 소문자, 숫자, 붙임표
     * @param entities 개체의 수. 층마다 고르게 나눈다 (나머지는 앞 층부터 하나씩)
     * @param relations 관계의 수. 이웃한 층 사이마다 고르게 나눈다
     * @param layers 층의 수. 2 이상
     * @param changes 바뀐 판에서 값이 달라지는 첫 층의 개체의 수. 0 이면 바뀐 판을 만들지 않는다
     */
    record Spec(String packId, int entities, int relations, int layers, int changes) {
    }

    /** 올릴 파일 하나. 올리는 차례대로다. */
    record Step(String inputSource, String file, LocalDate effectiveDate) {
    }

    /**
     * 변동 하나에서 나와야 하는 수.
     *
     * @param itemKey 바뀐 행의 키 (첫 층의 개체의 키)
     * @param impactTargets 영향 대상의 수. 아래층으로 따라가 닿는 개체 모두다
     * @param actions 조치의 수. 마지막 층에 닿은 대상마다 하나, 바뀐 개체 자신에 하나
     */
    record ExpectedChange(String itemKey, int impactTargets, int actions) {
    }

    /**
     * 만든 자료에서 셈한 수. 제품에 넣은 뒤의 수가 이것과 같아야 한다.
     *
     * @param steps 올릴 파일. 층, 그 사이의 관계, 바뀐 판의 차례다
     * @param changes 변동마다의 수. 키의 차례다
     */
    record Expected(String packId, int entities, int relations, List<Step> steps, List<ExpectedChange> changes) {

        Expected {
            steps = List.copyOf(steps);
            changes = List.copyOf(changes);
        }
    }

    /**
     * Pack 폴더를 만든다.
     *
     * @param parent 그 아래에 Pack 의 식별자와 같은 이름의 폴더를 만든다
     * @return 만든 자료에서 셈한 수
     * @throws IllegalArgumentException 만들 수 없는 값일 때. 아무것도 만들지 않는다
     */
    static Expected generate(Spec spec, Path parent) throws IOException {
        int[] layerSizes = split(check(spec).entities(), spec.layers());
        int[] gapSizes = split(spec.relations(), spec.layers() - 1);
        for (int gap = 0; gap < gapSizes.length; gap++) {
            long most = (long) layerSizes[gap] * layerSizes[gap + 1];
            if (gapSizes[gap] > most) {
                throw new IllegalArgumentException("관계의 수가 너무 많습니다. " + (gap + 1) + "층과 " + (gap + 2) + "층 사이에는 관계를 "
                        + most + "개까지 만들 수 있는데 " + gapSizes[gap] + "개가 필요합니다. 관계의 수를 줄이거나 개체의 수를 늘리세요.");
            }
        }
        if (spec.changes() > layerSizes[0]) {
            throw new IllegalArgumentException("변동의 수가 첫 층의 개체의 수(" + layerSizes[0] + ")보다 많습니다. 변동의 수를 줄이세요.");
        }
        Path pack = parent.resolve(spec.packId());
        Files.createDirectories(pack.resolve("data"));
        Files.createDirectories(pack.resolve("mappings"));
        Files.createDirectories(pack.resolve("rules"));
        write(pack.resolve("pack.ttl"), packFile(spec));
        write(pack.resolve("ontology.ttl"), ontology(spec));
        write(pack.resolve("rules/rules.ttl"), rules(spec));
        List<Step> steps = new ArrayList<>();
        for (int layer = 0; layer < spec.layers(); layer++) {
            write(pack.resolve("mappings/" + layerSource(layer) + ".ttl"), layerMapping(spec, layer));
            String file = "data/" + layerSource(layer) + "-v1.csv";
            writeLayer(pack.resolve(file), layer, layerSizes[layer], 0);
            steps.add(new Step(layerSource(layer), file, FIRST_DATE));
        }
        for (int gap = 0; gap < gapSizes.length; gap++) {
            write(pack.resolve("mappings/" + linkSource(gap) + ".ttl"), linkMapping(spec, gap));
            String file = "data/" + linkSource(gap) + "-v1.csv";
            try (BufferedWriter out = Files.newBufferedWriter(pack.resolve(file), StandardCharsets.UTF_8)) {
                out.write("from,to\n");
                for (int j = 0; j < gapSizes[gap]; j++) {
                    int source = j % layerSizes[gap];
                    out.write(key(gap, source) + "," + key(gap + 1, target(j, layerSizes[gap], layerSizes[gap + 1])) + "\n");
                }
            }
            steps.add(new Step(linkSource(gap), file, FIRST_DATE));
        }
        List<ExpectedChange> changes = new ArrayList<>();
        if (spec.changes() > 0) {
            String file = "data/" + layerSource(0) + "-v2.csv";
            writeLayer(pack.resolve(file), 0, layerSizes[0], spec.changes());
            steps.add(new Step(layerSource(0), file, CHANGED_DATE));
            for (int index = 0; index < spec.changes(); index++) {
                changes.add(reach(index, layerSizes, gapSizes));
            }
        }
        return new Expected(spec.packId(), spec.entities(), spec.relations(), steps, changes);
    }

    private static Spec check(Spec spec) {
        if (spec.packId() == null || !PACK_ID.matcher(spec.packId()).matches()) {
            throw new IllegalArgumentException("Pack 의 이름을 쓸 수 없습니다. 영어 소문자, 숫자, 붙임표(-)만 써서 100자 이하로 정하세요.");
        }
        if (spec.layers() < 2 || spec.layers() > MAX_LAYERS) {
            throw new IllegalArgumentException("층의 수는 2 부터 " + MAX_LAYERS + " 까지입니다. 받은 값: " + spec.layers() + ".");
        }
        if (spec.entities() < spec.layers()) {
            throw new IllegalArgumentException("개체의 수는 층의 수(" + spec.layers() + ") 이상이어야 합니다. 층마다 개체가 하나는 있어야 합니다.");
        }
        if (spec.relations() < 0) {
            throw new IllegalArgumentException("관계의 수는 0 이상이어야 합니다. 받은 값: " + spec.relations() + ".");
        }
        if (spec.changes() < 0) {
            throw new IllegalArgumentException("변동의 수는 0 이상이어야 합니다. 받은 값: " + spec.changes() + ".");
        }
        return spec;
    }

    /** 전체를 몇 묶음으로 고르게 나눈다. 나머지는 앞 묶음부터 하나씩 더한다. */
    private static int[] split(int total, int parts) {
        int[] sizes = new int[parts];
        for (int i = 0; i < parts; i++) {
            sizes[i] = total / parts + (i < total % parts ? 1 : 0);
        }
        return sizes;
    }

    /**
     * 한 층 사이의 j 번째 관계의 도착 개체. 출발 개체는 j 를 위층의 수로 나눈 나머지다.
     * 같은 출발에서 나가는 관계마다 도착이 달라, 같은 관계가 두 번 나오지 않는다.
     */
    private static int target(int j, int upper, int lower) {
        int source = j % upper;
        int round = j / upper;
        // 출발마다 도착을 조금씩 밀어, 관계가 아래층에 고르게 닿게 한다.
        return (int) (((long) source * Math.max(1, lower / upper) + round) % lower);
    }

    /** 첫 층의 개체 하나에서 아래층으로 따라가 닿는 개체를 센다. 만든 관계와 같은 셈으로 따라간다. */
    private static ExpectedChange reach(int index, int[] layerSizes, int[] gapSizes) {
        boolean[] current = new boolean[layerSizes[0]];
        current[index] = true;
        int reached = 0;
        int onLast = 0;
        for (int gap = 0; gap < gapSizes.length; gap++) {
            boolean[] next = new boolean[layerSizes[gap + 1]];
            int count = 0;
            for (int j = 0; j < gapSizes[gap]; j++) {
                if (current[j % layerSizes[gap]]) {
                    int target = target(j, layerSizes[gap], layerSizes[gap + 1]);
                    if (!next[target]) {
                        next[target] = true;
                        count++;
                    }
                }
            }
            reached += count;
            onLast = gap == gapSizes.length - 1 ? count : 0;
            current = next;
        }
        return new ExpectedChange(key(0, index), reached, onLast + 1);
    }

    // ── 이름 ─────────────────────────────────────────────────────

    private static String number(int layer) {
        return String.format("%02d", layer + 1);
    }

    private static String layerSource(int layer) {
        return "layer-" + number(layer);
    }

    private static String linkSource(int gap) {
        return "link-" + number(gap);
    }

    private static String type(int layer) {
        return "Layer" + number(layer);
    }

    /** 개체의 키. 층과 그 안에서의 차례다. */
    private static String key(int layer, int index) {
        return "L" + number(layer) + "-" + String.format("%07d", index + 1);
    }

    // ── 파일 ─────────────────────────────────────────────────────

    private static final String NOTE = "# 큰 시험 자료 생성기(verify 의 LargePack)가 만든 Pack 이다. 성능 점검에 쓴다.\n"
            + "# 모든 이름과 값은 지어낸 것이고 어느 업무 영역도 아니다. 손으로 고치지 않는다.\n";

    private static String prefixes(Spec spec) {
        return NOTE + "@prefix rdfs:   <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "@prefix owl:    <http://www.w3.org/2002/07/owl#> .\n" + "@prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .\n"
                + "@prefix tabnit: <urn:tabnit:vocab:> .\n" + "@prefix ex:     <urn:tabnit:pack:" + spec.packId() + ":> .\n\n";
    }

    private static String packFile(Spec spec) {
        return prefixes(spec) + "<urn:tabnit:pack:" + spec.packId() + ":> a tabnit:Pack ;\n    tabnit:packId \"" + spec.packId()
                + "\" ;\n    rdfs:label \"큰 시험 자료 (개체 " + spec.entities() + ", 관계 " + spec.relations() + ", 층 " + spec.layers()
                + ")\" ;\n    tabnit:defaultMaxDepth " + (spec.layers() - 1) + " .\n";
    }

    private static String ontology(Spec spec) {
        StringBuilder text = new StringBuilder(prefixes(spec));
        for (int layer = 0; layer < spec.layers(); layer++) {
            String n = number(layer);
            text.append("ex:").append(type(layer)).append(" a owl:Class ;\n    rdfs:label \"").append(layer + 1)
                .append("층\" ;\n    tabnit:displayNameProperty ex:name").append(n).append(" .\n");
            text.append("ex:name").append(n).append(" a owl:DatatypeProperty ;\n    rdfs:label \"").append(layer + 1)
                .append("층의 이름\" ;\n    rdfs:domain ex:").append(type(layer)).append(" ;\n    rdfs:range xsd:string .\n");
            text.append("ex:amount").append(n).append(" a owl:DatatypeProperty ;\n    rdfs:label \"").append(layer + 1)
                .append("층의 수량\" ;\n    rdfs:domain ex:").append(type(layer)).append(" ;\n    rdfs:range xsd:integer .\n\n");
        }
        for (int gap = 0; gap < spec.layers() - 1; gap++) {
            text.append("ex:link").append(number(gap)).append(" a owl:ObjectProperty ;\n    rdfs:label \"").append(gap + 1)
                .append("층에서 ").append(gap + 2).append("층으로\" ;\n    rdfs:domain ex:").append(type(gap))
                .append(" ;\n    rdfs:range ex:").append(type(gap + 1)).append(" ;\n    tabnit:impactDirection tabnit:SourceToTarget .\n\n");
        }
        return text.toString();
    }

    private static String layerMapping(Spec spec, int layer) {
        String n = number(layer);
        return prefixes(spec) + "ex:mapping-" + layerSource(layer) + " a tabnit:Mapping ;\n    tabnit:inputSourceId \""
                + layerSource(layer) + "\" ;\n    tabnit:fileFormat tabnit:Csv ;\n"
                + "    tabnit:requiredColumns ( \"key\" \"name\" \"amount\" ) ;\n    tabnit:itemKeyColumns ( \"key\" ) ;\n"
                + "    tabnit:primaryEntity [ tabnit:entityType ex:" + type(layer) + " ; tabnit:keyColumn \"key\" ] ;\n"
                + "    tabnit:entity [\n        tabnit:entityType ex:" + type(layer) + " ;\n        tabnit:keyColumn \"key\" ;\n"
                + "        tabnit:attribute [ tabnit:property ex:name" + n + " ; tabnit:column \"name\" ] ;\n"
                + "        tabnit:attribute [ tabnit:property ex:amount" + n + " ; tabnit:column \"amount\" ]\n    ] .\n";
    }

    private static String linkMapping(Spec spec, int gap) {
        return prefixes(spec) + "ex:mapping-" + linkSource(gap) + " a tabnit:Mapping ;\n    tabnit:inputSourceId \"" + linkSource(gap)
                + "\" ;\n    tabnit:fileFormat tabnit:Csv ;\n    tabnit:requiredColumns ( \"from\" \"to\" ) ;\n"
                + "    tabnit:itemKeyColumns ( \"from\" \"to\" ) ;\n    tabnit:primaryEntity [ tabnit:entityType ex:" + type(gap)
                + " ; tabnit:keyColumn \"from\" ] ;\n    tabnit:relation [\n        tabnit:relationType ex:link" + number(gap)
                + " ;\n        tabnit:sourceEntity [ tabnit:entityType ex:" + type(gap) + " ; tabnit:keyColumn \"from\" ] ;\n"
                + "        tabnit:targetEntity [ tabnit:entityType ex:" + type(gap + 1) + " ; tabnit:keyColumn \"to\" ]\n    ] .\n";
    }

    private static String rules(Spec spec) {
        String first = type(0);
        String last = type(spec.layers() - 1);
        return prefixes(spec)
                // 첫 층의 개체가 바뀌면 마지막 층에 닿은 대상마다 조치 하나.
                + "ex:rule-0001 a tabnit:Rule ;\n    tabnit:ruleNo \"0001\" ;\n    rdfs:label \"마지막 층의 대상을 살핀다\" ;\n"
                + "    tabnit:priority 1 ;\n    tabnit:whenChangeKind tabnit:Changed ;\n    tabnit:appliesTo tabnit:ImpactTarget ;\n"
                + "    tabnit:targetType ex:" + last + " ;\n    tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:typeIs ex:"
                + first + " ] ;\n    tabnit:actionText \"바뀐 것에 맞춰 살핀다\" ;\n    tabnit:dueInDays 14 .\n\n"
                // 바뀐 개체 자신에 조치 하나.
                + "ex:rule-0002 a tabnit:Rule ;\n    tabnit:ruleNo \"0002\" ;\n    rdfs:label \"바뀐 개체 자신을 살핀다\" ;\n"
                + "    tabnit:priority 2 ;\n    tabnit:whenChangeKind tabnit:Changed ;\n    tabnit:appliesTo tabnit:ChangeTarget ;\n"
                + "    tabnit:targetType ex:" + first + " ;\n    tabnit:actionText \"바뀐 내용을 확인한다\" ;\n    tabnit:dueInDays 7 .\n";
    }

    /** 층 하나의 개체. 앞에서부터 changed 개의 행은 수량이 하나 크다 (바뀐 판). */
    private static void writeLayer(Path file, int layer, int size, int changed) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("key,name,amount\n");
            for (int index = 0; index < size; index++) {
                int amount = (index * 37) % 1000 + (index < changed ? 1 : 0);
                out.write(key(layer, index) + "," + (layer + 1) + "층의 개체 " + (index + 1) + "," + amount + "\n");
            }
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
