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

    /** 관계 지도 (D2 AD-368). 물을 수 있지만 Pack 마다 반드시 있어야 하는 종류는 아니다. */
    static final String RELATION_MAP = "relation-map";

    /** 속성 값의 시점 조회 (D4 3절, D2 AD-421). 최소 수가 없다. */
    static final String ATTRIBUTE_VALUES = "attribute-values-as-of";

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
                // 날짜나 단계를 먼저 정해 둔다: 골든셋의 번호가 틀려 실패해도 어느 날짜의 질문인지는 보고에 남는다.
                label = where(question);
                label = label(question);
                Object[] pair = switch (question.kind()) {
                    case RELATIONS -> relations(question);
                    case CHANGES -> changes(question, steps);
                    case IMPACT -> impact(question, steps);
                    case ACTIONS -> actions(question, steps);
                    case RELATION_MAP -> relationMap(question);
                    case ATTRIBUTE_VALUES -> attributeValues(question);
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

    /** 속성 값의 시점 조회: 그 날짜에 유효한 값(속성의 이름, 값, 시작일, 종료일)을 제품이 준 차례로 견준다. 없으면 빈 목록이다. */
    private Object[] attributeValues(QuestionFile.Question question) {
        LocalDate asOf = QuestionFile.date(question.body().get("asOf"), "질문 " + question.id() + " 의 asOf");
        String entityId = Ids.entity(this.packId, question.body().get("entity"));
        List<Object> expected = new ArrayList<>();
        for (Object item : list(expect(question).get("values"), question, "values")) {
            Map<?, ?> value = map(item, question, "values 의 항목");
            if (value.get("attribute") == null || value.get("value") == null) {
                throw new Failure("질문 " + question.id() + " 의 values 의 항목에 attribute 와 value 가 있어야 합니다.");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attribute", String.valueOf(value.get("attribute")));
            row.put("value", String.valueOf(value.get("value")));
            row.put("validFrom", QuestionFile.date(value.get("validFrom"), "질문 " + question.id() + " 의 validFrom").toString());
            row.put("validTo", value.get("validTo") == null ? null
                    : QuestionFile.date(value.get("validTo"), "질문 " + question.id() + " 의 validTo").toString());
            expected.add(row);
        }
        Map<?, ?> body = ok(this.product.attributeValues(entityId, asOf), "속성 값의 시점 조회");
        List<Object> actual = new ArrayList<>();
        if (!(body.get("values") instanceof List<?> values)) {
            throw new Failure("속성 값의 시점 조회의 답에 values 목록이 없습니다. 제품의 답이 계약(D7)과 다릅니다.");
        }
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> value)) {
                throw new Failure("속성 값의 시점 조회의 답의 values 에 항목의 묶음이 아닌 것이 있습니다. 제품의 답이 계약(D7)과 다릅니다.");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attribute", value.get("attributeName"));
            row.put("value", value.get("value"));
            row.put("validFrom", value.get("validFrom"));
            row.put("validTo", value.get("validTo"));
            actual.add(row);
        }
        return new Object[] { expected, actual };
    }

    /** 관계 지도: 노드(개체와 깊이)와 간선을 제품이 준 차례로 견준다. 수와 표시와 멈춘 깊이는 적었을 때만 견준다. */
    private Object[] relationMap(QuestionFile.Question question) {
        LocalDate asOf = QuestionFile.date(question.body().get("asOf"), "질문 " + question.id() + " 의 asOf");
        int depth = mapDepth(question);
        String entityId = Ids.entity(this.packId, question.body().get("entity"));
        Map<?, ?> expect = expect(question);
        Map<String, Object> expected = new LinkedHashMap<>();
        List<Object> nodes = new ArrayList<>();
        for (Object item : list(expect.get("nodes"), question, "nodes")) {
            Map<?, ?> node = map(item, question, "nodes 의 항목");
            if (node.get("depth") == null) {
                throw new Failure("정답의 형식이 틀렸습니다: nodes 의 항목에 깊이(depth)가 없습니다.");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", Ids.entity(this.packId, node.get("entity")));
            row.put("depth", node.get("depth"));
            nodes.add(row);
        }
        expected.put("nodes", nodes);
        List<Object> edges = new ArrayList<>();
        for (Object item : list(expect.get("edges"), question, "edges")) {
            Map<?, ?> edge = map(item, question, "edges 의 항목");
            if (edge.get("type") == null) {
                throw new Failure("정답의 형식이 틀렸습니다: edges 의 항목에 관계 유형(type)이 없습니다.");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", String.valueOf(edge.get("type")));
            row.put("source", Ids.entity(this.packId, edge.get("source")));
            row.put("target", Ids.entity(this.packId, edge.get("target")));
            row.put("validFrom", QuestionFile.date(edge.get("validFrom"), "질문 " + question.id() + " 의 validFrom").toString());
            row.put("validTo", edge.get("validTo") == null ? null
                    : QuestionFile.date(edge.get("validTo"), "질문 " + question.id() + " 의 validTo").toString());
            edges.add(row);
        }
        expected.put("edges", edges);
        List<String> extras = List.of("totalNodes", "truncated", "reachedDepth");
        for (String extra : extras) {
            if (expect.containsKey(extra)) {
                expected.put(extra, expect.get(extra));
            }
        }
        Map<?, ?> body = ok(this.product.relationMap(entityId, asOf, depth), "관계 지도");
        Map<String, Object> actual = new LinkedHashMap<>();
        List<Object> actualNodes = new ArrayList<>();
        for (Object item : (List<?>) body.get("nodes")) {
            Map<?, ?> node = (Map<?, ?>) item;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", node.get("entityId"));
            row.put("depth", node.get("depth"));
            actualNodes.add(row);
        }
        actual.put("nodes", actualNodes);
        List<Object> actualEdges = new ArrayList<>();
        for (Object item : (List<?>) body.get("edges")) {
            Map<?, ?> edge = (Map<?, ?>) item;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", edge.get("relationType"));
            row.put("source", edge.get("sourceEntityId"));
            row.put("target", edge.get("targetEntityId"));
            row.put("validFrom", edge.get("validFrom"));
            row.put("validTo", edge.get("validTo"));
            actualEdges.add(row);
        }
        actual.put("edges", actualEdges);
        for (String extra : extras) {
            if (expected.containsKey(extra)) {
                actual.put(extra, body.get(extra));
            }
        }
        return new Object[] { expected, actual };
    }

    /** 관계 지도의 질문이 적은 깊이. 반드시 적는다: 제품의 기본값에 기대는 질문은 없다. */
    private static int mapDepth(QuestionFile.Question question) {
        if (question.body().get("depth") == null) {
            throw new QuestionFileException("질문 " + question.id() + " 에 깊이(depth)가 없습니다. 관계 지도의 질문은 깊이를 적습니다.");
        }
        return QuestionFile.integer(question.body().get("depth"), "질문 " + question.id() + " 의 depth (깊이)");
    }

    /**
     * 변동: 바뀐 항목(키와 종류)을 견준다. 정답의 항목에 바뀐 값(changedValues)을 적었으면 속성마다의 이전 값과
     * 새 값도 제품이 준 차례로 견준다 (D2 AD-372). 적지 않은 정답은 전과 같이 키와 종류만 본다.
     */
    private Object[] changes(QuestionFile.Question question, Map<Integer, Product.Reply> steps) {
        List<Object> expected = new ArrayList<>();
        int withValues = 0;
        int items = 0;
        for (Object item : list(expect(question).get("changes"), question, "changes")) {
            Map<?, ?> change = map(item, question, "changes 의 항목");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemKey", itemKey(change.get("itemKey"), question));
            row.put("kind", String.valueOf(change.get("kind")).toUpperCase(Locale.ROOT));
            items++;
            if (change.containsKey("changedValues")) {
                withValues++;
                row.put("changedValues", expectedValues(change.get("changedValues"), question));
            }
            expected.add(row);
        }
        if (withValues != 0 && withValues != items) {
            throw new Failure("정답의 형식이 틀렸습니다: 바뀐 값(changedValues)을 일부 항목에만 적었습니다. 모든 항목에 적거나 "
                    + "하나도 적지 않습니다 (모르는 것은 null, 없는 것은 빈 목록).");
        }
        boolean values = withValues > 0;
        List<Object> actual = new ArrayList<>();
        for (Map<?, ?> change : stepChanges(step(question.body().get("step"), question), steps)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemKey", change.get("itemKey"));
            row.put("kind", change.get("kind"));
            if (values) {
                // 모른다(null)는 항목이 있고 값이 비어 있는 것이다. 항목이 아예 없는 답은 모른다가 아니라 빠진 것이다.
                if (!change.containsKey("changedValues")) {
                    throw new Failure("제품의 답에 바뀐 값(changedValues)이 없습니다: 항목 " + change.get("itemKey"));
                }
                row.put("changedValues", actualValues(change.get("changedValues")));
            }
            actual.add(row);
        }
        return new Object[] { expected, actual };
    }

    /** 정답에 적은 바뀐 값. null 은 모른다는 뜻이고 빈 목록은 바뀐 값이 없다는 뜻이다: 둘은 다르다. */
    private List<Object> expectedValues(Object written, QuestionFile.Question question) {
        if (written == null) {
            return null;
        }
        List<Object> values = new ArrayList<>();
        for (Object item : list(written, question, "changedValues")) {
            Map<?, ?> value = map(item, question, "changedValues 의 항목");
            if (value.get("attribute") == null) {
                throw new Failure("정답의 형식이 틀렸습니다: changedValues 의 항목에 속성의 이름(attribute)이 없습니다.");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", Ids.entity(this.packId, value.get("entity")));
            row.put("attribute", String.valueOf(value.get("attribute")));
            row.put("before", value.get("before") == null ? null : String.valueOf(value.get("before")));
            row.put("after", value.get("after") == null ? null : String.valueOf(value.get("after")));
            values.add(row);
        }
        return values;
    }

    /** 제품이 준 바뀐 값을 정답과 같은 모양으로 옮긴다. 이름표는 견주지 않는다. */
    private static List<Object> actualValues(Object given) {
        if (!(given instanceof List<?> items)) {
            return null;
        }
        List<Object> values = new ArrayList<>();
        for (Object item : items) {
            Map<?, ?> value = (Map<?, ?>) item;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", value.get("entityId"));
            row.put("attribute", value.get("attributeName"));
            row.put("before", value.get("before"));
            row.put("after", value.get("after"));
            values.add(row);
        }
        return values;
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
                // 값이 비어 있어도 견줄 수 있게 담는다. 틀린 답은 오류가 아니라 기대와 실제의 차이로 낸다.
                pairs.add(java.util.Arrays.asList(String.valueOf(((Map<?, ?>) item).get("entityType")), ((Map<?, ?>) item).get("count")));
            }
            actual.put("countsByType", pairs);
        }
        if (expected.containsKey("countsByDepth")) {
            List<Object> pairs = new ArrayList<>();
            for (Object item : (List<?>) body.get("countsByDepth")) {
                pairs.add(java.util.Arrays.asList(String.valueOf(((Map<?, ?>) item).get("depth")), ((Map<?, ?>) item).get("count")));
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
        String golden = golden(question);
        String where = where(question);
        if (golden == null) {
            return where;
        }
        return where.isEmpty() ? "[" + golden + "]" : where + " [" + golden + "]";
    }

    /**
     * 질문에 적은 골든셋의 번호 (D4 3절, D2 AD-421). 보고에 함께 적기만 한다: 판정에 쓰지 않는다.
     *
     * @return 번호. 적지 않았으면 null
     */
    private static String golden(QuestionFile.Question question) {
        Object golden = question.body().get("golden");
        if (golden == null) {
            return null;
        }
        if (!(golden instanceof String text) || text.isBlank()) {
            throw new Failure("질문 " + question.id() + " 의 golden 은 골든셋의 번호 하나를 글자로 적어야 합니다 (예: CQ-03). 받은 값: "
                    + golden);
        }
        return text;
    }

    private static String where(QuestionFile.Question question) {
        Map<String, Object> body = question.body();
        if (RELATION_MAP.equals(question.kind())) {
            return QuestionFile.date(body.get("asOf"), "질문 " + question.id() + " 의 asOf") + " 깊이 " + mapDepth(question);
        }
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
