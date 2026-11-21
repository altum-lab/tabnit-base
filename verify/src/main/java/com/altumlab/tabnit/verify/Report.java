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

    /** 두 번 돌린 결과가 다른 질문의 번호. 통과 여부나 제품의 답이 다르면 다른 것이다. 번호 순이다. */
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
            if (a == null || b == null || a.passed() != b.passed() || !java.util.Objects.equals(a.actual(), b.actual())
                    || !java.util.Objects.equals(a.reason(), b.reason())) {
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
        return labels;
    }
}
