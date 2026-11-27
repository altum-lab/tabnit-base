package com.altumlab.tabnit.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 검증 질문의 보고 (D4 5절): 질문별 통과와 실패의 목록, 최소 수의 확인, 두 번 돌린 결과의 차이. */
final class Report {

    /** Pack 마다 질문은 이만큼 이상이다 (D2 AD-113). */
    static final int MIN_QUESTIONS = 10;

    /** 종류마다 질문은 이만큼 이상이다 (D2 AD-113). */
    static final int MIN_PER_KIND = 2;

    /** 시점 조회 질문이 묻는 서로 다른 조회 시점은 이만큼 이상이다: 현재와 과거 두 시점 (D2 AD-112, AD-332). */
    static final int MIN_TIME_POINTS = 3;

    private static final Map<String, String> KIND_LABELS = kindLabels();

    private Report() {
    }

    /** Pack 의 질문이 최소 수에 모자란 곳. 모자라지 않으면 비어 있다. */
    static List<String> shortfalls(List<QuestionFile.Question> questions) {
        List<String> problems = new ArrayList<>();
        if (questions.size() < MIN_QUESTIONS) {
            problems.add("질문이 " + questions.size() + "개입니다. " + MIN_QUESTIONS + "개 이상이어야 합니다.");
        }
        for (String kind : Evaluator.KINDS) {
            long count = questions.stream().filter(question -> kind.equals(question.kind())).count();
            if (count < MIN_PER_KIND) {
                problems.add(label(kind) + " (" + kind + ") 질문이 " + count + "개입니다. " + MIN_PER_KIND + "개 이상이어야 합니다.");
            }
        }
        return problems;
    }

    /**
     * 시점 조회 질문의 조회 시점이 모자란 곳. 서로 다른 날짜를 센다. 날짜로 읽을 수 없는 값은 세지 않는다
     * (그 질문은 돌릴 때 실패로 나온다). 모자라지 않으면 비어 있다.
     */
    static List<String> timePointShortfalls(List<QuestionFile.Question> questions) {
        TreeSet<java.time.LocalDate> dates = new TreeSet<>();
        for (QuestionFile.Question question : questions) {
            if (!Evaluator.RELATIONS.equals(question.kind()) || question.body().get("asOf") == null) {
                continue;
            }
            try {
                dates.add(QuestionFile.date(question.body().get("asOf"), "질문 " + question.id() + " 의 asOf"));
            }
            catch (QuestionFileException e) {
                // 읽을 수 없는 날짜는 시점으로 세지 않는다.
            }
        }
        if (dates.size() >= MIN_TIME_POINTS) {
            return List.of();
        }
        return List.of("시점 조회 질문의 서로 다른 조회 시점이 " + dates.size() + "개입니다. " + MIN_TIME_POINTS
                + "개 이상이어야 합니다 (현재와 과거 두 시점). 시점 조회 질문에 다른 날짜의 asOf 를 더하세요.");
    }

    /**
     * 시나리오에 같은 입력 자료의 판이 둘 이상 들어오는 단계가 없으면 알린다. 판이 한 번씩만 들어오면 변동이 없어
     * 변동, 영향 범위, 조치를 물을 수 없다 (D2 AD-332).
     */
    static List<String> editionShortfalls(QuestionFile file) {
        TreeSet<String> seen = new TreeSet<>(Evaluator.CODE_POINT_ORDER);
        for (QuestionFile.Step step : file.steps()) {
            if (!seen.add(step.inputSource())) {
                return List.of();
            }
        }
        return List.of(file.fileName() + ": 시나리오에 같은 입력 자료의 판이 둘 이상 들어오는 단계가 없습니다. "
                + "앞 판과 견줄 둘째 판의 단계를 더하세요.");
    }

    /** 두 번 돌린 결과가 다른 질문의 번호. 통과 여부나 제품의 답이 다르면 다른 것이다. 번호의 글자 코드 순이다. */
    static List<String> differences(List<Evaluator.Result> first, List<Evaluator.Result> second) {
        Map<String, Evaluator.Result> one = byId(first);
        Map<String, Evaluator.Result> two = byId(second);
        TreeSet<String> ids = new TreeSet<>(Evaluator.CODE_POINT_ORDER);
        ids.addAll(one.keySet());
        ids.addAll(two.keySet());
        List<String> different = new ArrayList<>();
        for (String id : ids) {
            Evaluator.Result a = one.get(id);
            Evaluator.Result b = two.get(id);
            // 견주기 전에 막힌 까닭의 문구는 견주지 않는다: 실행마다 다른 값(포트, 접속 주소)이 들 수 있다.
            // 한 번은 막히고 한 번은 견주었으면 제품의 답이 한쪽에만 있어 다른 것으로 잡힌다.
            if (a == null || b == null || a.passed() != b.passed() || !java.util.Objects.equals(a.actual(), b.actual())) {
                different.add(id);
            }
        }
        return different;
    }

    /**
     * 시나리오 하나의 보고.
     *
     * @param differences 두 번 돌린 결과가 달랐던 질문의 번호
     */
    static String scenario(String packId, String scenarioId, List<Evaluator.Result> results, List<String> differences) {
        long passed = results.stream().filter(Evaluator.Result::passed).count();
        StringBuilder text = new StringBuilder();
        text.append("Pack: ").append(packId).append("   시나리오 ").append(scenarioId).append("   통과 ").append(passed)
            .append(" / ").append(results.size()).append("   두 번 실행: ")
            .append(differences.isEmpty() ? "같음" : "다름 (" + String.join(", ", differences) + ")").append('\n');
        for (Evaluator.Result result : results) {
            text.append("  ").append(result.passed() ? "통과" : "실패").append("  ").append(result.id()).append("  ")
                .append(label(result.kind())).append("  ").append(result.label()).append('\n');
            if (result.passed()) {
                continue;
            }
            if (result.reason() != null) {
                text.append("        ").append(result.reason()).append('\n');
            }
            else {
                text.append("        기대: ").append(result.expected()).append('\n');
                text.append("        실제: ").append(result.actual()).append('\n');
            }
        }
        return text.toString();
    }

    /** 대조의 보고에 적는 실패는 이만큼까지다. 나머지는 수로 알린다. */
    static final int MAX_CHECK_FAILURES = 20;

    /**
     * 시나리오 하나의 전수 대조의 보고 (D4 3절 "전수 대조", D2 AD-368). 대조는 수가 많아 통과한 것은 한 줄씩 적지 않는다:
     * 수와, 실패한 것의 기대와 실제를 적는다.
     *
     * @param differences 두 번 돌린 결과가 달랐던 대조의 번호
     */
    static String checks(String packId, String scenarioId, List<Evaluator.Result> results, List<String> differences) {
        long passed = results.stream().filter(Evaluator.Result::passed).count();
        Map<String, Integer> byKind = new LinkedHashMap<>();
        for (String kind : KIND_LABELS.keySet()) {
            int count = (int) results.stream().filter(result -> kind.equals(result.kind())).count();
            if (count > 0) {
                byKind.put(label(kind), count);
            }
        }
        int others = results.size() - byKind.values().stream().mapToInt(Integer::intValue).sum();
        if (others > 0) {
            byKind.put("그 밖", others);
        }
        List<String> kinds = new ArrayList<>();
        byKind.forEach((kind, count) -> kinds.add(kind + " " + count));
        StringBuilder text = new StringBuilder();
        text.append("Pack: ").append(packId).append("   시나리오 ").append(scenarioId).append("   대조 통과 ").append(passed)
            .append(" / ").append(results.size()).append(" (").append(String.join(", ", kinds)).append(")   두 번 실행: ")
            .append(differences.isEmpty() ? "같음" : "다름 (" + String.join(", ", differences) + ")").append('\n');
        int written = 0;
        int failed = 0;
        for (Evaluator.Result result : results) {
            if (result.passed()) {
                continue;
            }
            failed++;
            if (written == MAX_CHECK_FAILURES) {
                continue;
            }
            written++;
            text.append("  실패  ").append(result.id()).append("  ").append(label(result.kind())).append("  ")
                .append(result.label()).append('\n');
            if (result.reason() != null) {
                text.append("        ").append(result.reason()).append('\n');
            }
            else {
                text.append("        기대: ").append(result.expected()).append('\n');
                text.append("        실제: ").append(result.actual()).append('\n');
            }
        }
        if (failed > written) {
            text.append("  실패한 대조가 ").append(failed - written).append("건 더 있습니다.\n");
        }
        return text.toString();
    }

    private static String label(String kind) {
        return KIND_LABELS.getOrDefault(kind, kind);
    }

    private static Map<String, Evaluator.Result> byId(List<Evaluator.Result> results) {
        Map<String, Evaluator.Result> map = new LinkedHashMap<>();
        for (Evaluator.Result result : results) {
            map.put(result.id(), result);
        }
        return map;
    }

    private static Map<String, String> kindLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(Evaluator.RELATIONS, "시점 조회");
        labels.put(Evaluator.CHANGES, "변동");
        labels.put(Evaluator.IMPACT, "영향 범위");
        labels.put(Evaluator.ACTIONS, "조치");
        labels.put(Evaluator.RELATION_MAP, "관계 지도");
        labels.put(Evaluator.ATTRIBUTE_VALUES, "속성 값");
        return labels;
    }
}
