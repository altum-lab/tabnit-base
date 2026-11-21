package com.altumlab.tabnit.verify;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 질문을 제품에 묻고 정답과 견준다 (D4 3절, 4절).
 *
 * <p>목록은 항목도 순서도 같아야 통과다. 수와 날짜도 정확히 같아야 한다 (D2 AD-109). 근거까지 견준다 (D2 AD-114).
 * 정답에 적는 항목만 견준다: 기록한 때 같은 것은 보지 않는다. 질문 하나가 오류를 내도 나머지는 계속 돌린다.
 * 아직 만들지 않은 종류의 질문은 건너뛰지 않고 실패로 낸다 (D2 AD-108).
 *
 * <p>이 클래스에는 정답이 없다. 질문과 정답은 Pack 안에 있다.
 */
final class Evaluator {

    static final String RELATIONS = "relations-as-of";

    static final String CHANGES = "changes";

    static final String IMPACT = "impact";

    static final String ACTIONS = "actions";

    /** 지금 물을 수 있는 종류. 보고와 최소 수의 확인에서도 이 차례로 쓴다. */
    static final List<String> KINDS = List.of(RELATIONS, CHANGES, IMPACT, ACTIONS);

    /** 글자 코드 순 (D2 AD-38). 질문은 번호의 이 순서로 돌린다. */
    static final Comparator<String> CODE_POINT_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    /**
     * 질문 하나의 결과.
     *
     * @param label 어느 날짜나 단계를 물었는가
     * @param expected 정답을 견주는 모양으로 적은 것. 견주기 전에 실패했으면 null
     * @param actual 제품의 답을 같은 모양으로 적은 것. 견주기 전에 실패했으면 null
     * @param reason 견주기 전에 실패한 까닭. 견주었으면 null
     */
    record Result(String id, String kind, String label, boolean passed, String expected, String actual, String reason) {
    }

    private final String packId;

    private final Product product;

    Evaluator(String packId, Product product) {
        this.packId = packId;
        this.product = product;
    }

    /**
     * @param steps 시나리오의 단계마다 판을 올렸을 때의 제품의 답. 단계의 번호로 찾는다
     * @return 질문의 번호 순
     */
    List<Result> evaluate(QuestionFile file, Map<Integer, Product.Reply> steps) {
        List<QuestionFile.Question> ordered = new ArrayList<>(file.questions());
        ordered.sort(Comparator.comparing(QuestionFile.Question::id, CODE_POINT_ORDER));
        List<Result> results = new ArrayList<>();
        for (QuestionFile.Question question : ordered) {
            String label = "";
            try {
                label = label(question);
                Object[] pair = switch (question.kind()) {
                    case RELATIONS -> relations(question);
                    case CHANGES -> changes(question, steps);
                    case IMPACT -> impact(question, steps);
                    case ACTIONS -> actions(question, steps);
                    default -> throw new Failure("아직 만들지 않은 종류입니다: " + question.kind());
                };
                // 묶음째로 견준다. 글자로 적은 것은 보고에만 쓴다: 값 안의 쉼표나 괄호 때문에 다른 묶음이 같은 글자가 될 수 있다.
                Object expected = normalize(pair[0]);
                Object actual = normalize(pair[1]);
                results.add(new Result(question.id(), question.kind(), label, java.util.Objects.equals(expected, actual),
                        render(expected), render(actual), null));
            }
            catch (Failure | QuestionFileException e) {
                results.add(new Result(question.id(), question.kind(), label, false, null, null, e.getMessage()));
            }
            catch (RuntimeException e) {
                // 질문 하나의 오류로 나머지를 멈추지 않는다.
                results.add(new Result(question.id(), question.kind(), label, false, null, null,
                        "묻는 중에 오류가 났습니다: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return results;
    }

    // ── 종류마다: [정답, 제품의 답] 을 같은 모양으로 만든다 ─────────────────

    private Object[] relations(QuestionFile.Question question) {
        LocalDate asOf = QuestionFile.date(question.body().get("asOf"), "질문 " + question.id() + " 의 asOf");
        String entityId = Ids.entity(this.packId, question.body().get("entity"));
        List<Object> expected = new ArrayList<>();
        for (Object item : list(expect(question).get("relations"), question, "relations")) {
            Map<?, ?> relation = map(item, question, "relations 의 항목");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", String.valueOf(relation.get("type")));
            row.put("source", Ids.entity(this.packId, relation.get("source")));
            row.put("target", Ids.entity(this.packId, relation.get("target")));
            row.put("validFrom", QuestionFile.date(relation.get("validFrom"), "질문 " + question.id() + " 의 validFrom").toString());
            row.put("validTo", relation.get("validTo") == null ? null
                    : QuestionFile.date(relation.get("validTo"), "질문 " + question.id() + " 의 validTo").toString());
            expected.add(row);
        }
        Map<?, ?> body = ok(this.product.relations(entityId, asOf), "시점 조회");
        List<Object> actual = new ArrayList<>();
        for (Object item : (List<?>) body.get("relations")) {
            Map<?, ?> relation = (Map<?, ?>) item;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", relation.get("relationType"));
            row.put("source", relation.get("sourceEntityId"));
            row.put("target", relation.get("targetEntityId"));
            row.put("validFrom", relation.get("validFrom"));
            row.put("validTo", relation.get("validTo"));
            actual.add(row);
        }
        return new Object[] { expected, actual };
    }

    private Object[] changes(QuestionFile.Question question, Map<Integer, Product.Reply> steps) {
        List<Object> expected = new ArrayList<>();
        for (Object item : list(expect(question).get("changes"), question, "changes")) {
            Map<?, ?> change = map(item, question, "changes 의 항목");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemKey", itemKey(change.get("itemKey"), question));
            row.put("kind", String.valueOf(change.get("kind")).toUpperCase(Locale.ROOT));
            expected.add(row);
        }
        List<Object> actual = new ArrayList<>();
        for (Map<?, ?> change : stepChanges(step(question.body().get("step"), question), steps)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemKey", change.get("itemKey"));
            row.put("kind", change.get("kind"));
            actual.add(row);
        }
        return new Object[] { expected, actual };
    }

    private Object[] impact(QuestionFile.Question question, Map<Integer, Product.Reply> steps) {
        Map<?, ?> expect = expect(question);
        Map<String, Object> expected = new LinkedHashMap<>();
        // 영향 대상의 목록과 근거는 반드시 적는다. 수와 한계는 적었을 때만 견준다.
        List<Object> targets = new ArrayList<>();
        for (Object item : list(expect.get("targets"), question, "targets")) {
            Map<?, ?> target = map(item, question, "targets 의 항목");
            if (!(target.get("path") instanceof Map<?, ?> path)) {
                throw new Failure("정답의 형식이 틀렸습니다: 영향 대상에 근거 경로(path)가 없습니다. 근거를 빼고 적은 정답은 받지 않습니다.");
            }
            List<Object> relations = new ArrayList<>();
            for (Object step : list(path.get("relations"), question, "path 의 relations")) {
                Map<?, ?> relation = map(step, question, "path 의 relations 의 항목");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", String.valueOf(relation.get("type")));
                row.put("source", Ids.entity(this.packId, relation.get("source")));
                row.put("target", Ids.entity(this.packId, relation.get("target")));
                relations.add(row);
            }
            Map<String, Object> way = new LinkedHashMap<>();
            way.put("from", Ids.entity(this.packId, path.get("from")));
            way.put("asOf", QuestionFile.date(path.get("asOf"), "질문 " + question.id() + " 의 path 의 asOf").toString());
            way.put("relations", relations);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", Ids.entity(this.packId, target.get("entity")));
            row.put("depth", target.get("depth"));
            row.put("path", way);
            targets.add(row);
        }
        expected.put("targets", targets);
        for (String counts : List.of("countsByType", "countsByDepth")) {
            if (expect.containsKey(counts)) {
                List<Object> pairs = new ArrayList<>();
                map(expect.get(counts), question, counts)
                    .forEach((key, count) -> pairs.add(java.util.Arrays.asList(String.valueOf(key), count)));
                expected.put(counts, pairs);
            }
        }
        if (expect.containsKey("truncated")) {
            expected.put("truncated", expect.get("truncated"));
        }

        Map<?, ?> body = ok(this.product.impactScope(changeEventId(question, steps)), "영향 범위");
        Map<String, Object> actual = new LinkedHashMap<>();
        List<Object> actualTargets = new ArrayList<>();
        for (Object item : (List<?>) body.get("targets")) {
            Map<?, ?> target = (Map<?, ?>) item;
            Map<?, ?> path = (Map<?, ?>) target.get("path");
            List<Object> relations = new ArrayList<>();
            for (Object step : (List<?>) path.get("steps")) {
                Map<?, ?> relation = (Map<?, ?>) step;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", relation.get("relationType"));
                row.put("source", relation.get("sourceEntityId"));
                row.put("target", relation.get("targetEntityId"));
                relations.add(row);
            }
            Map<String, Object> way = new LinkedHashMap<>();
            way.put("from", path.get("startEntityId"));
            way.put("asOf", path.get("asOf"));
            way.put("relations", relations);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", target.get("entityId"));
            row.put("depth", target.get("depth"));
            row.put("path", way);
            actualTargets.add(row);
        }
        actual.put("targets", actualTargets);
        if (expected.containsKey("countsByType")) {
            List<Object> pairs = new ArrayList<>();
            for (Object item : (List<?>) body.get("countsByType")) {
                pairs.add(List.of(String.valueOf(((Map<?, ?>) item).get("entityType")), ((Map<?, ?>) item).get("count")));
            }
            actual.put("countsByType", pairs);
        }
        if (expected.containsKey("countsByDepth")) {
            List<Object> pairs = new ArrayList<>();
            for (Object item : (List<?>) body.get("countsByDepth")) {
                pairs.add(List.of(String.valueOf(((Map<?, ?>) item).get("depth")), ((Map<?, ?>) item).get("count")));
            }
            actual.put("countsByDepth", pairs);
        }
        if (expected.containsKey("truncated")) {
            actual.put("truncated", body.get("truncated"));
        }
        return new Object[] { expected, actual };
    }

    private Object[] actions(QuestionFile.Question question, Map<Integer, Product.Reply> steps) {
        List<Object> expected = new ArrayList<>();
        for (Object item : list(expect(question).get("actions"), question, "actions")) {
            Map<?, ?> action = map(item, question, "actions 의 항목");
            if (!(action.get("basis") instanceof Map<?, ?> basis)) {
                throw new Failure("정답의 형식이 틀렸습니다: 조치에 근거와 그 판(basis)이 없습니다. 근거를 빼고 적은 정답은 받지 않습니다.");
            }
            Map<String, Object> ground = new LinkedHashMap<>();
            ground.put("entity", Ids.entity(this.packId, basis.get("entity")));
            ground.put("inputSource", String.valueOf(basis.get("inputSource")));
            ground.put("edition", basis.get("edition"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rule", String.valueOf(action.get("rule")));
            row.put("priority", action.get("priority"));
            row.put("target", Ids.entity(this.packId, action.get("target")));
            row.put("dueDate", QuestionFile.date(action.get("dueDate"), "질문 " + question.id() + " 의 dueDate").toString());
            row.put("basis", ground);
            expected.add(row);
        }
        Map<?, ?> body = ok(this.product.actions(this.packId, changeEventId(question, steps)), "조치");
        List<Object> actual = new ArrayList<>();
        for (Object item : (List<?>) body.get("actions")) {
            Map<?, ?> action = (Map<?, ?>) item;
            Map<?, ?> basis = (Map<?, ?>) action.get("basis");
            Map<String, Object> ground = new LinkedHashMap<>();
            ground.put("entity", ((Map<?, ?>) basis.get("entity")).get("entityId"));
            ground.put("inputSource", basis.get("inputSourceId"));
            ground.put("edition", basis.get("editionNo"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rule", action.get("ruleNo"));
            row.put("priority", action.get("priority"));
            row.put("target", ((Map<?, ?>) action.get("target")).get("entityId"));
            row.put("dueDate", action.get("dueDate"));
            row.put("basis", ground);
            actual.add(row);
        }
        return new Object[] { expected, actual };
    }

    // ── 도우미 ────────────────────────────────────────────────────

    /** 어느 날짜나 단계를 묻는가. 보고에 적는다. */
    private static String label(QuestionFile.Question question) {
        Map<String, Object> body = question.body();
        if (body.get("asOf") != null) {
            return QuestionFile.date(body.get("asOf"), "질문 " + question.id() + " 의 asOf").toString();
        }
        if (body.get("step") != null) {
            return body.get("step") + "단계";
        }
        if (body.get("change") instanceof Map<?, ?> change) {
            return change.get("step") + "단계 " + itemKey(change.get("itemKey"), question);
        }
        return "";
    }

    /** 그 단계에서 나온 변동. 판을 올리지 못한 단계면 실패다. */
    private static List<Map<?, ?>> stepChanges(int step, Map<Integer, Product.Reply> steps) {
        Product.Reply reply = steps.get(step);
        if (reply == null) {
            throw new Failure("시나리오에 " + step + "단계가 없습니다.");
        }
        Map<?, ?> body = ok(reply, step + "단계의 판 올리기");
        List<Map<?, ?>> changes = new ArrayList<>();
        if (body.get("changes") instanceof List<?> items) {
            for (Object item : items) {
                changes.add((Map<?, ?>) item);
            }
        }
        return changes;
    }

    /** 질문이 가리키는 변동 (단계와 항목의 키)의 식별자. */
    private static String changeEventId(QuestionFile.Question question, Map<Integer, Product.Reply> steps) {
        Map<?, ?> change = map(question.body().get("change"), question, "change");
        int step = step(change.get("step"), question);
        String itemKey = itemKey(change.get("itemKey"), question);
        for (Map<?, ?> candidate : stepChanges(step, steps)) {
            if (itemKey.equals(candidate.get("itemKey"))) {
                return String.valueOf(candidate.get("changeEventId"));
            }
        }
        throw new Failure(step + "단계에서 항목 " + itemKey + " 의 변동이 나오지 않았습니다.");
    }

    private static int step(Object value, QuestionFile.Question question) {
        if (value == null) {
            throw new QuestionFileException("질문 " + question.id() + " 에 단계의 번호(step)가 없습니다.");
        }
        return QuestionFile.integer(value, "질문 " + question.id() + " 의 step (단계의 번호)");
    }

    /** 항목의 키: 키 열의 값을 차례대로 적은 목록을 식별자의 규칙으로 잇는다 (D2 AD-122). */
    private static String itemKey(Object value, QuestionFile.Question question) {
        if (!(value instanceof List<?> parts) || parts.isEmpty()) {
            throw new QuestionFileException("질문 " + question.id() + " 의 itemKey 는 키 열의 값을 차례대로 적은 목록이어야 합니다.");
        }
        List<String> texts = new ArrayList<>();
        for (Object part : parts) {
            texts.add(String.valueOf(part));
        }
        return Ids.join(texts);
    }

    private static Map<?, ?> expect(QuestionFile.Question question) {
        return (Map<?, ?>) question.body().get("expect");
    }

    private static List<?> list(Object value, QuestionFile.Question question, String what) {
        if (value instanceof List<?> items) {
            return items;
        }
        throw new QuestionFileException("질문 " + question.id() + " 의 " + what + " 이(가) 없거나 목록이 아닙니다.");
    }

    private static Map<?, ?> map(Object value, QuestionFile.Question question, String what) {
        if (value instanceof Map<?, ?> items) {
            return items;
        }
        throw new QuestionFileException("질문 " + question.id() + " 의 " + what + " 이(가) 없거나 항목의 묶음이 아닙니다.");
    }

    /** 제품이 정상으로 답했을 때의 본문. 아니면 실패다: 상태와 오류의 종류를 까닭에 적는다. */
    private static Map<?, ?> ok(Product.Reply reply, String what) {
        if (reply.status() == 200 && reply.body() instanceof Map<?, ?> body) {
            return body;
        }
        Object code = reply.body() instanceof Map<?, ?> problem ? problem.get("code") : null;
        Object detail = reply.body() instanceof Map<?, ?> problem ? problem.get("detail") : null;
        throw new Failure(what + " 의 답이 정상이 아닙니다: HTTP " + reply.status() + (code == null ? "" : " " + code)
                + (detail == null ? "" : " (" + detail + ")"));
    }

    /**
     * 견줄 수 있게 맞춘다: 묶음과 목록은 그대로 두고, 낱값은 글자로 바꾼다 (비어 있음은 그대로).
     * 정답 파일의 값은 모두 글자로 읽히고 제품의 답에는 수와 참거짓이 있다. 둘을 같은 것으로 본다.
     */
    static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), normalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(normalize(item));
            }
            return result;
        }
        return value == null ? null : String.valueOf(value);
    }

    /** 보고에 적는 모양. 묶음의 항목은 넣은 차례 그대로다. */
    static String render(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder text = new StringBuilder("{");
            map.forEach((key, item) -> text.append(text.length() > 1 ? ", " : "").append(key).append(": ").append(render(item)));
            return text.append("}").toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder text = new StringBuilder("[");
            for (Object item : list) {
                text.append(text.length() > 1 ? ", " : "").append(render(item));
            }
            return text.append("]").toString();
        }
        return String.valueOf(value);
    }

    /** 견주기 전에 실패했다. 까닭을 보고에 적는다. */
    private static final class Failure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }
    }
}
