package com.altumlab.tabnit.pack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.sparql.graph.GraphFactory;

/**
 * Pack 폴더의 규칙 정의(rules/*.ttl)를 읽어 내부 모델로 만들고, 온톨로지와 맞는지 확인한다 (D8 2절 ~ 3절, D2 AD-213).
 *
 * <p>읽기만 한다. 규칙을 실행하지 않는다 (rules 모듈). 읽는 것은 D8 에 적힌 낱말뿐이다. 그 밖의 문장은 규칙 안쪽에
 * 있어도 문제로 알린다. 조건은 안쪽 정의(이름 없는 정의)로 적는다. 그 라벨은 읽을 때마다 달라지므로 문구와 차례에
 * 쓰지 않는다. 특정 규칙의 내용을 알지 못한다.
 */
final class RuleReader {

    static final String FOLDER = "rules";

    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";

    private static final String V = PackReader.VOCAB;

    private static final String XSD = PackReader.XSD;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);

    /** 조건의 정해진 차례: 대상, 유형을 보는 것이 먼저, 유형, 속성, 비교, 값. */
    private static final Comparator<RuleCondition> CONDITIONS = Comparator.comparing(RuleCondition::subject)
        .thenComparing((RuleCondition c) -> c.typeIs() == null)
        .thenComparing(RuleCondition::typeIs, Comparator.nullsFirst(PackOrder.STRINGS))
        .thenComparing(RuleCondition::attributeName, Comparator.nullsFirst(PackOrder.STRINGS))
        .thenComparing(RuleCondition::operator, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing((RuleCondition c) -> String.join("\u0000", c.values()), PackOrder.STRINGS);

    private final Path directory;

    private final String prefix;

    private final Map<String, EntityTypeDefinition> entityTypes;

    private final Map<String, AttributeDefinition> attributes;

    private final List<PackProblem> problems;

    /** 규칙 번호마다 그것을 적은 파일. 겹치는지 보는 데 쓴다. */
    private final TreeMap<String, List<String>> filesByRuleNo = new TreeMap<>(PackOrder.STRINGS);

    // 파일 하나를 읽는 동안의 상태.
    private String file;

    private Map<Node, List<Triple>> bySubject;

    /** 읽은 문장. 들어 있는지만 본다. 순회하지 않는다. */
    private Set<Triple> consumed;

    /** 이미 연 안쪽 정의. 들어 있는지만 본다. */
    private Set<Node> opened;

    /** 지금 읽는 규칙의 번호. 문제를 알릴 때 함께 적는다. 아직 모르면 null. */
    private String ruleNo;

    private RuleReader(Path directory, String prefix, Map<String, EntityTypeDefinition> entityTypes,
            Map<String, AttributeDefinition> attributes, List<PackProblem> problems) {
        this.directory = directory;
        this.prefix = prefix;
        this.entityTypes = entityTypes;
        this.attributes = attributes;
        this.problems = problems;
    }

    /** 규칙을 모두 읽는다. 문제는 problems 에 더한다. 돌려주는 목록은 규칙 번호의 글자 코드 순이다. */
    static List<RuleDefinition> read(Path directory, String prefix, Map<String, EntityTypeDefinition> entityTypes,
            Map<String, AttributeDefinition> attributes, List<PackProblem> problems) {
        return new RuleReader(directory, prefix, entityTypes, attributes, problems).readAll();
    }

    private List<RuleDefinition> readAll() {
        Path folder = directory.resolve(FOLDER);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<String> names;
        try (Stream<Path> list = Files.list(folder)) {
            names = list.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".ttl"))
                .sorted(PackOrder.STRINGS)
                .toList();
        }
        catch (IOException | UncheckedIOException e) {
            problems.add(new PackProblem(FOLDER + "/", "", "규칙 폴더를 읽지 못했습니다: " + e.getMessage()
                    + ". 폴더의 권한을 확인하세요."));
            return List.of();
        }
        TreeMap<String, RuleDefinition> byRuleNo = new TreeMap<>(PackOrder.STRINGS);
        for (String name : names) {
            for (RuleDefinition rule : readFile(name)) {
                byRuleNo.putIfAbsent(rule.ruleNo(), rule);
            }
        }
        // 규칙 번호는 Pack 안에서 겹치지 않는다 (D8 2절).
        filesByRuleNo.forEach((no, files) -> {
            if (files.size() > 1) {
                TreeSet<String> distinct = new TreeSet<>(PackOrder.STRINGS);
                distinct.addAll(files);
                problems.add(new PackProblem(FOLDER + "/" + distinct.first(), no, "규칙 번호 \"" + no + "\" 이(가) 규칙 "
                        + files.size() + "개에 적혀 있습니다 (" + String.join(", ", distinct)
                        + "). 규칙 번호는 Pack 안에서 겹치지 않습니다. 번호를 다르게 적으세요."));
            }
        });
        // 규칙 번호는 글자 코드 순으로 견준다. 자릿수가 서로 다르면 뜻한 차례가 아닐 수 있어 받지 않는다
        // (D2 AD-103, AD-215).
        TreeMap<Integer, List<String>> byLength = new TreeMap<>();
        for (String no : filesByRuleNo.keySet()) {
            byLength.computeIfAbsent(no.codePointCount(0, no.length()), k -> new ArrayList<>()).add(no);
        }
        if (byLength.size() > 1) {
            List<String> shown = new ArrayList<>();
            byLength.forEach((length, numbers) -> shown.add(length + "자: " + String.join(", ",
                    numbers.stream().limit(3).map(no -> "\"" + no + "\"").toList()) + (numbers.size() > 3 ? " 등" : "")));
            problems.add(new PackProblem(FOLDER + "/", "", "규칙 번호의 자릿수가 서로 다릅니다 (" + String.join(" / ", shown)
                    + "). 규칙 번호는 글자 코드 순으로 견주므로 \"10\" 이 \"2\" 보다 앞섭니다. \"0002\", \"0010\" 처럼 자릿수를 맞춰 적으세요."));
        }
        return List.copyOf(byRuleNo.values());
    }

    private List<RuleDefinition> readFile(String name) {
        file = FOLDER + "/" + name;
        ruleNo = null;
        Graph graph = GraphFactory.createDefaultGraph();
        try {
            RDFParser.source(directory.resolve(FOLDER).resolve(name)).lang(Lang.TURTLE)
                .errorHandler(new PackReader.StopAtFirst()).parse(graph);
        }
        catch (RiotException e) {
            problem("", "Turtle 문법이 틀렸습니다: " + e.getMessage() + ". 그 줄을 고치세요.");
            return List.of();
        }
        bySubject = new HashMap<>();
        consumed = new HashSet<>();
        opened = new HashSet<>();
        List<Triple> all = new ArrayList<>();
        TreeMap<String, Node> ruleSubjects = new TreeMap<>(PackOrder.STRINGS);
        graph.find().forEachRemaining(triple -> {
            all.add(triple);
            bySubject.computeIfAbsent(triple.getSubject(), k -> new ArrayList<>()).add(triple);
            if (triple.getSubject().isURI() && isRuleType(triple)) {
                ruleSubjects.put(triple.getSubject().getURI(), triple.getSubject());
            }
        });
        // 이름 없이 적은 규칙. 같은 문구는 한 번만 남는다.
        TreeSet<String> unnamed = new TreeSet<>(PackOrder.STRINGS);
        for (Triple triple : all) {
            if (triple.getSubject().isBlank() && isRuleType(triple)) {
                discard(triple.getSubject());
                unnamed.add("규칙에는 이름(주소)을 붙여 적습니다. <" + prefix + "이름> a tabnit:Rule 의 꼴로 적으세요.");
            }
        }
        unnamed.forEach(message -> problem(PackReader.UNNAMED, message));
        List<RuleDefinition> rules = new ArrayList<>();
        for (Node subject : ruleSubjects.values()) {
            RuleDefinition rule = readRule(subject);
            if (rule != null) {
                rules.add(rule);
            }
        }
        ruleNo = null;
        // 어느 규칙에도 속하지 않은 문장. 문제는 끝에서 정렬하고 같은 것은 한 번만 남으므로 읽는 차례와 무관하다.
        for (Triple triple : all) {
            if (!consumed.contains(triple)) {
                Node subject = triple.getSubject();
                String item = subject.isURI() ? itemOf(subject.getURI()) : PackReader.UNNAMED;
                problem(item, "D8 에 없는 문장입니다: " + PackReader.show(triple.getPredicate())
                        + ". 이 파일에는 tabnit:Rule 과 그 안쪽의 항목만 적습니다. 빼세요.");
            }
        }
        return rules;
    }

    private static boolean isRuleType(Triple triple) {
        return isUri(triple.getPredicate(), RDF + "type") && isUri(triple.getObject(), V + "Rule");
    }

    /** 규칙 하나를 읽는다. 문제가 하나라도 있으면 null. */
    private RuleDefinition readRule(Node subject) {
        int before = problems.size();
        String item = itemOf(subject.getURI());
        ruleNo = null;
        Block top = new Block(subject);
        // 규칙 번호를 먼저 읽는다. 이 규칙의 다른 문제에 번호를 함께 적기 위해서다.
        String number = text(top, V + "ruleNo", "tabnit:ruleNo", item, true);
        if (number != null && !number.matches("[A-Za-z0-9-]{1,50}")) {
            // 규칙 번호는 사람이 읽고 글자 코드 순으로 견주는 값이다. 눈에 보이지 않는 글자가 들지 않게 한다 (D2 AD-217).
            String shown = number.length() > 60 ? number.substring(0, 60) + "…" : number;
            problem(item, "tabnit:ruleNo \"" + shown + "\" 을(를) 쓸 수 없습니다 (" + number.length()
                    + "자). 영어 대소문자, 숫자, 붙임표(-)만 써서 50자 안으로 적으세요.");
            number = null;
        }
        if (number != null) {
            ruleNo = number;
            filesByRuleNo.computeIfAbsent(number, k -> new ArrayList<>()).add(file.substring(FOLDER.length() + 1));
        }
        if (!subject.getURI().startsWith(prefix) || subject.getURI().length() == prefix.length()) {
            problem(item, "이 Pack 의 주소로 시작하지 않는 규칙입니다. <" + prefix + "이름> 의 꼴로 적으세요.");
        }
        for (Node type : top.take(RDF + "type")) {
            if (!isUri(type, V + "Rule")) {
                problem(item, "D8 에 없는 문장입니다: a " + PackReader.show(type) + ". 빼세요.");
            }
        }
        String label = text(top, RDFS + "label", "rdfs:label", item, true);
        Integer priority = integer(top, V + "priority", "tabnit:priority", item, 1, "1 이상의 정수를 적으세요. 수가 작을수록 먼저입니다.");
        Integer dueInDays = integer(top, V + "dueInDays", "tabnit:dueInDays", item, 0, "0 이상의 정수를 적으세요. 기한까지의 일수입니다.");
        String actionText = text(top, V + "actionText", "tabnit:actionText", item, true);

        // 어떤 종류의 변동에 걸리는가. 적지 않으면 모든 종류다.
        TreeSet<RuleChangeKind> kinds = new TreeSet<>();
        for (Node node : top.take(V + "whenChangeKind")) {
            RuleChangeKind kind = isUri(node, V + "Added") ? RuleChangeKind.ADDED
                    : isUri(node, V + "Changed") ? RuleChangeKind.CHANGED
                            : isUri(node, V + "Removed") ? RuleChangeKind.REMOVED : null;
            if (kind == null) {
                problem(item, "tabnit:whenChangeKind 의 값 " + PackReader.show(node)
                        + " 을(를) 모릅니다. tabnit:Added, tabnit:Changed, tabnit:Removed 가운데서 적으세요.");
            }
            else {
                kinds.add(kind);
            }
        }
        if (kinds.isEmpty()) {
            kinds.addAll(List.of(RuleChangeKind.values()));
        }
        RuleSubject appliesTo = subject(top, V + "appliesTo", "tabnit:appliesTo", item);
        String targetType = name(top, V + "targetType", "tabnit:targetType", item);
        if (targetType != null && !entityTypes.containsKey(targetType)) {
            problem(item, "tabnit:targetType 의 개체 유형 " + targetType + " 이(가) 정의되지 않았습니다. 그 유형을 정의하거나 이름을 고치세요.");
        }

        TreeSet<RuleCondition> conditions = new TreeSet<>(CONDITIONS);
        for (Node node : top.take(V + "condition")) {
            RuleCondition condition = condition(node, item);
            if (condition != null) {
                conditions.add(condition);
            }
        }
        // 대상이 변동 대상 자신이면 영향 대상이 없다. 영향 대상을 보는 조건을 둘 수 없다 (D2 AD-118).
        if (appliesTo == RuleSubject.CHANGE_TARGET
                && conditions.stream().anyMatch(c -> c.subject() == RuleSubject.IMPACT_TARGET)) {
            problem(item, "대상이 변동 대상인 규칙(tabnit:appliesTo tabnit:ChangeTarget)의 조건이 영향 대상을 봅니다 "
                    + "(tabnit:subject tabnit:ImpactTarget). 그 조건을 빼거나 규칙의 대상을 영향 대상으로 바꾸세요.");
        }
        top.rejectOthers(item);
        if (problems.size() > before) {
            return null;
        }
        return new RuleDefinition(number, label, priority, List.copyOf(kinds), appliesTo, targetType,
                List.copyOf(conditions), actionText, dueInDays);
    }

    /** 조건 하나를 읽는다. 문제가 있으면 알리고 null. */
    private RuleCondition condition(Node node, String item) {
        int before = problems.size();
        Block block = block(node, "tabnit:condition", item);
        if (block == null) {
            return null;
        }
        RuleSubject subject = subject(block, V + "subject", "조건의 tabnit:subject", item);
        List<Node> types = block.take(V + "typeIs");
        List<Node> properties = block.take(V + "property");
        List<Node> operators = block.take(V + "operator");
        List<Node> values = block.take(V + "value");
        RuleCondition condition = null;
        if (!types.isEmpty()) {
            condition = typeCondition(subject, types, properties, operators, values, item);
        }
        else if (properties.isEmpty() && operators.isEmpty() && values.isEmpty()) {
            problem(item, "조건이 아무것도 보지 않습니다. 유형을 보려면 tabnit:typeIs 를, 속성 값을 보려면 tabnit:property 와 "
                    + "tabnit:operator 를 적으세요.");
        }
        else {
            condition = attributeCondition(subject, properties, operators, values, item);
        }
        block.rejectOthers(item);
        if (problems.size() > before) {
            // 이 조건은 이미 틀렸다고 알렸다. 값의 자리에 겹쳐 적은 안쪽 정의는 따로 알리지 않는다.
            values.stream().filter(Node::isBlank).forEach(this::discard);
            return null;
        }
        return condition;
    }

    private RuleCondition typeCondition(RuleSubject subject, List<Node> types, List<Node> properties,
            List<Node> operators, List<Node> values, String item) {
        if (!properties.isEmpty() || !operators.isEmpty() || !values.isEmpty()) {
            problem(item, "유형을 보는 조건(tabnit:typeIs)에는 tabnit:property, tabnit:operator, tabnit:value 를 함께 적지 않습니다. "
                    + "속성 값을 보는 조건은 따로 적으세요.");
            return null;
        }
        if (types.size() != 1) {
            problem(item, "조건의 tabnit:typeIs 는 하나만 적습니다 (지금 " + types.size() + "개).");
            return null;
        }
        String type = localName(types.get(0), "조건의 tabnit:typeIs", item);
        if (type == null) {
            return null;
        }
        if (!entityTypes.containsKey(type)) {
            problem(item, "조건의 tabnit:typeIs 의 개체 유형 " + type + " 이(가) 정의되지 않았습니다. 그 유형을 정의하거나 이름을 고치세요.");
            return null;
        }
        return subject == null ? null : new RuleCondition(subject, type, null, null, List.of());
    }

    private RuleCondition attributeCondition(RuleSubject subject, List<Node> properties, List<Node> operators,
            List<Node> values, String item) {
        if (properties.size() != 1) {
            problem(item, "조건의 tabnit:property 는 반드시 하나 적습니다 (지금 " + properties.size() + "개).");
            return null;
        }
        String name = localName(properties.get(0), "조건의 tabnit:property", item);
        if (name == null) {
            return null;
        }
        AttributeDefinition attribute = attributes.get(name);
        if (attribute == null) {
            problem(item, "조건의 tabnit:property 의 속성 " + name + " 이(가) 정의되지 않았습니다. 그 속성을 정의하거나 이름을 고치세요.");
            return null;
        }
        if (operators.size() != 1) {
            problem(item, "조건의 tabnit:operator 는 반드시 하나 적습니다 (속성 " + name + ", 지금 " + operators.size() + "개).");
            return null;
        }
        RuleOperator operator = operator(operators.get(0));
        if (operator == null) {
            problem(item, "조건의 tabnit:operator 의 값 " + PackReader.show(operators.get(0)) + " 을(를) 모릅니다 (속성 " + name
                    + "). tabnit:Equals, tabnit:NotEquals, tabnit:GreaterThan, tabnit:GreaterThanOrEqual, tabnit:LessThan, "
                    + "tabnit:LessThanOrEqual, tabnit:In, tabnit:HasValue, tabnit:HasNoValue 가운데서 적으세요.");
            return null;
        }
        ValueFormat format = attribute.valueFormat();
        boolean ordering = operator == RuleOperator.GREATER_THAN || operator == RuleOperator.GREATER_THAN_OR_EQUAL
                || operator == RuleOperator.LESS_THAN || operator == RuleOperator.LESS_THAN_OR_EQUAL;
        if (ordering && format != ValueFormat.INTEGER && format != ValueFormat.DECIMAL && format != ValueFormat.DATE) {
            problem(item, "크기를 견주는 비교는 정수, 소수, 날짜 속성에만 씁니다. 속성 " + name + " 은(는) 그 형식이 아닙니다. "
                    + "tabnit:Equals 나 tabnit:In 을 쓰세요.");
            return null;
        }
        boolean none = operator == RuleOperator.HAS_VALUE || operator == RuleOperator.HAS_NO_VALUE;
        if (none && !values.isEmpty()) {
            problem(item, "값이 있는지를 보는 비교에는 tabnit:value 를 적지 않습니다 (속성 " + name + "). 빼세요.");
            return null;
        }
        if (!none && values.isEmpty()) {
            problem(item, "조건의 tabnit:value 가 없습니다 (속성 " + name + "). 견줄 값을 적으세요.");
            return null;
        }
        if (!none && operator != RuleOperator.IN && values.size() != 1) {
            problem(item, "조건의 tabnit:value 는 하나만 적습니다 (속성 " + name + ", 지금 " + values.size()
                    + "개). 여러 값 가운데 하나인지를 보려면 tabnit:In 을 쓰세요.");
            return null;
        }
        TreeSet<String> canonical = new TreeSet<>(PackOrder.STRINGS);
        TreeSet<String> wrong = new TreeSet<>(PackOrder.STRINGS);
        for (Node value : values) {
            String read = canonical(value, format);
            if (read == null) {
                wrong.add(PackReader.show(value));
                // 값의 자리에 안쪽 정의를 적었으면 그 안쪽은 따로 알리지 않는다.
                if (value.isBlank()) {
                    discard(value);
                }
            }
            else {
                canonical.add(read);
            }
        }
        if (!wrong.isEmpty()) {
            problem(item, "조건의 tabnit:value " + String.join(", ", wrong) + " 이(가) 속성 " + name + " 의 형식(" + shown(format)
                    + ")과 맞지 않습니다. " + hint(format));
            return null;
        }
        return subject == null ? null : new RuleCondition(subject, null, name, operator, List.copyOf(canonical));
    }

    private static RuleOperator operator(Node node) {
        if (!node.isURI() || !node.getURI().startsWith(V)) {
            return null;
        }
        return switch (node.getURI().substring(V.length())) {
            case "Equals" -> RuleOperator.EQUALS;
            case "NotEquals" -> RuleOperator.NOT_EQUALS;
            case "GreaterThan" -> RuleOperator.GREATER_THAN;
            case "GreaterThanOrEqual" -> RuleOperator.GREATER_THAN_OR_EQUAL;
            case "LessThan" -> RuleOperator.LESS_THAN;
            case "LessThanOrEqual" -> RuleOperator.LESS_THAN_OR_EQUAL;
            case "In" -> RuleOperator.IN;
            case "HasValue" -> RuleOperator.HAS_VALUE;
            case "HasNoValue" -> RuleOperator.HAS_NO_VALUE;
            default -> null;
        };
    }

    /**
     * 값을 속성의 형식에 맞는 정해진 표기로 읽는다. 저장된 속성 값과 같은 표기다 (D6 7절 "값을 읽는 법", D2 AD-176).
     *
     * @return 정해진 표기. 형식과 맞지 않으면 null
     */
    private static String canonical(Node node, ValueFormat format) {
        if (!node.isLiteral()) {
            return null;
        }
        String type = node.getLiteralDatatypeURI();
        String text = node.getLiteralLexicalForm();
        try {
            return switch (format) {
                case STRING -> (XSD + "string").equals(type) && !text.isEmpty() && text.indexOf('\u0000') < 0 ? text : null;
                case INTEGER -> (XSD + "integer").equals(type) ? new BigInteger(unsigned(text)).toString() : null;
                case DECIMAL -> {
                    if (!(XSD + "decimal").equals(type) && !(XSD + "integer").equals(type)) {
                        yield null;
                    }
                    BigDecimal value = new BigDecimal(unsigned(text));
                    yield value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
                }
                case DATE -> {
                    if (!(XSD + "date").equals(type)) {
                        yield null;
                    }
                    LocalDate date = LocalDate.parse(text, DATE);
                    yield date.getYear() < 1 || date.getYear() > 9999 ? null : date.toString();
                }
                case BOOLEAN -> (XSD + "boolean").equals(type) && (text.equals("true") || text.equals("false")) ? text
                        : null;
            };
        }
        catch (NumberFormatException | DateTimeParseException e) {
            return null;
        }
    }

    /** 더하기 부호를 뗀다. 지수 표기와 숫자가 아닌 글자는 받지 않는다. */
    private static String unsigned(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean sign = (c == '+' || c == '-') && i == 0;
            if (!sign && c != '.' && (c < '0' || c > '9')) {
                throw new NumberFormatException(text);
            }
        }
        return text.startsWith("+") ? text.substring(1) : text;
    }

    private static String shown(ValueFormat format) {
        return switch (format) {
            case STRING -> "글자";
            case INTEGER -> "정수";
            case DECIMAL -> "소수";
            case DATE -> "날짜";
            case BOOLEAN -> "참·거짓";
        };
    }

    private static String hint(ValueFormat format) {
        return switch (format) {
            case STRING -> "따옴표로 감싼 비어 있지 않은 글자를 적으세요.";
            case INTEGER -> "따옴표 없이 정수를 적으세요 (예: 10).";
            case DECIMAL -> "따옴표 없이 수를 적으세요 (예: 0.5). 지수 표기는 받지 않습니다.";
            case DATE -> "\"2026-03-01\"^^xsd:date 의 꼴로 적으세요.";
            case BOOLEAN -> "따옴표 없이 true 나 false 를 적으세요.";
        };
    }

    // ── 값 읽기 ─────────────────────────────────────────────────

    /** 변동 대상인가 영향 대상인가를 읽는다. 반드시 하나다. */
    private RuleSubject subject(Block block, String predicate, String shown, String item) {
        List<Node> nodes = block.take(predicate);
        if (nodes.size() != 1) {
            problem(item, shown + " 은(는) 반드시 하나 적습니다 (지금 " + nodes.size()
                    + "개). tabnit:ChangeTarget 또는 tabnit:ImpactTarget 을 적으세요.");
            return null;
        }
        if (isUri(nodes.get(0), V + "ChangeTarget")) {
            return RuleSubject.CHANGE_TARGET;
        }
        if (isUri(nodes.get(0), V + "ImpactTarget")) {
            return RuleSubject.IMPACT_TARGET;
        }
        problem(item, shown + " 의 값 " + PackReader.show(nodes.get(0))
                + " 을(를) 모릅니다. tabnit:ChangeTarget 또는 tabnit:ImpactTarget 을 적으세요.");
        return null;
    }

    /** 이 Pack 의 정의를 가리키는 값 하나를 읽어 이름을 돌려준다. */
    private String name(Block block, String predicate, String shown, String item) {
        List<Node> nodes = block.take(predicate);
        if (nodes.size() != 1) {
            problem(item, shown + " 은(는) 반드시 하나 적습니다 (지금 " + nodes.size() + "개).");
            return null;
        }
        return localName(nodes.get(0), shown, item);
    }

    private String localName(Node node, String shown, String item) {
        if (!node.isURI() || !node.getURI().startsWith(prefix) || node.getURI().length() == prefix.length()) {
            problem(item, shown + " 의 값 " + PackReader.show(node) + " 이(가) 이 Pack 의 정의가 아닙니다. <" + prefix
                    + "이름> 의 꼴로 적으세요.");
            return null;
        }
        return node.getURI().substring(prefix.length());
    }

    /** 비어 있지 않은 글자 값 하나를 읽는다. 없거나, 여럿이거나, 글자가 아니면 문제를 남기고 null. */
    private String text(Block block, String predicate, String shown, String item, boolean required) {
        List<Node> nodes = block.take(predicate);
        if (nodes.isEmpty()) {
            if (required) {
                problem(item, shown + " 이(가) 없습니다. 따옴표로 감싼 글자 하나를 적으세요.");
            }
            return null;
        }
        if (nodes.size() > 1) {
            problem(item, shown + " 은(는) 하나만 적습니다 (지금 " + nodes.size() + "개).");
            return null;
        }
        Node node = nodes.get(0);
        boolean text = node.isLiteral() && (XSD + "string").equals(node.getLiteralDatatypeURI())
                && !node.getLiteralLexicalForm().isEmpty() && node.getLiteralLexicalForm().indexOf('\u0000') < 0;
        if (!text) {
            problem(item, shown + " 은(는) 비어 있지 않은 글자 값이어야 합니다 (지금 " + PackReader.show(node)
                    + "). 따옴표로 감싼 글자를 적으세요. NUL 글자는 쓸 수 없습니다.");
            return null;
        }
        return node.getLiteralLexicalForm();
    }

    /** 정수 값 하나를 읽는다. 없거나, 여럿이거나, 정수가 아니거나, 가장 작은 값보다 작으면 문제를 남기고 null. */
    private Integer integer(Block block, String predicate, String shown, String item, int least, String hint) {
        List<Node> nodes = block.take(predicate);
        if (nodes.size() != 1) {
            problem(item, shown + " 은(는) 반드시 하나 적습니다 (지금 " + nodes.size() + "개). " + hint);
            return null;
        }
        Node node = nodes.get(0);
        if (node.isLiteral() && (XSD + "integer").equals(node.getLiteralDatatypeURI())) {
            try {
                int value = Integer.parseInt(unsigned(node.getLiteralLexicalForm()));
                if (value >= least) {
                    return value;
                }
            }
            catch (NumberFormatException e) {
                // 아래에서 문제로 알린다. 너무 큰 수도 여기로 온다.
            }
        }
        problem(item, shown + " 의 값 " + PackReader.show(node) + " 을(를) 쓸 수 없습니다. " + hint);
        return null;
    }

    private Block block(Node node, String shown, String item) {
        if (!node.isBlank() || !bySubject.containsKey(node)) {
            problem(item, shown + " 은(는) [ … ] 로 감싼 안쪽 정의로 적습니다 (지금 " + PackReader.show(node) + ").");
            // 이름 붙은 것을 가리켰으면 그쪽의 문장은 건드리지 않는다. 그것은 따로 읽고 따로 알린다.
            if (node.isBlank()) {
                discard(node);
            }
            return null;
        }
        if (!opened.add(node)) {
            // 어느 쪽을 먼저 읽는지에 결과가 달라지지 않도록, 함께 가리키는 것 자체를 받지 않는다.
            problem(item, "한 안쪽 정의를 두 곳에서 가리킵니다. 안쪽 정의는 쓰는 곳마다 [ … ] 로 따로 적으세요.");
            return null;
        }
        return new Block(node);
    }

    /** 그 값에서 이름 없는 안쪽 정의를 따라 닿는 문장을 모두 읽은 것으로 표시한다. 이미 문제 하나로 알린 것의 안쪽이다. */
    private void discard(Node start) {
        ArrayList<Node> open = new ArrayList<>(List.of(start));
        Set<Node> seen = new HashSet<>();
        while (!open.isEmpty()) {
            Node node = open.remove(open.size() - 1);
            if (node.isLiteral() || !seen.add(node)) {
                continue;
            }
            // 이름 붙은 주어의 문장은 건드리지 않는다. 그것은 따로 읽거나 "D8 에 없는 문장"으로 알린다.
            if (node.isURI() && node != start) {
                continue;
            }
            for (Triple triple : bySubject.getOrDefault(node, List.of())) {
                if (node.isBlank()) {
                    consumed.add(triple);
                }
                if (triple.getObject().isBlank()) {
                    open.add(triple.getObject());
                }
            }
        }
    }

    private static boolean isUri(Node node, String uri) {
        return node.isURI() && node.getURI().equals(uri);
    }

    /** 문제의 항목으로 적을 이름. 이 Pack 의 주소면 그 뒤의 이름, 아니면 주소 그대로. */
    private String itemOf(String uri) {
        return uri.startsWith(prefix) && uri.length() > prefix.length() ? uri.substring(prefix.length()) : uri;
    }

    /** 문제를 알린다. 규칙 번호를 알면 함께 적는다 (D8 2절). */
    private void problem(String item, String message) {
        problems.add(new PackProblem(file, item, ruleNo == null ? message : message + " (규칙 번호 " + ruleNo + ")"));
    }

    /** 주어 하나의 문장들. 읽은 문장을 표시해 두고, 끝에 남은 것을 D8 에 없는 문장으로 알린다. */
    private final class Block {

        private final List<Triple> triples;

        Block(Node subject) {
            this.triples = bySubject.getOrDefault(subject, List.of());
        }

        /** 그 술어의 값들을 돌려주고 읽은 것으로 표시한다. 돌려주는 차례에 결과가 달라지지 않게 쓴다. */
        List<Node> take(String predicate) {
            List<Node> objects = new ArrayList<>();
            for (Triple triple : triples) {
                if (isUri(triple.getPredicate(), predicate)) {
                    consumed.add(triple);
                    objects.add(triple.getObject());
                }
            }
            return objects;
        }

        /** 읽지 않은 문장을 D8 에 없는 문장으로 알린다. */
        void rejectOthers(String item) {
            TreeSet<String> others = new TreeSet<>(PackOrder.STRINGS);
            for (Triple triple : triples) {
                if (consumed.add(triple)) {
                    others.add(PackReader.show(triple.getPredicate()));
                    // 그 문장의 안쪽에 겹쳐 적은 것은 따로 알리지 않는다. 틀린 곳은 이 문장 하나다.
                    if (triple.getObject().isBlank()) {
                        discard(triple.getObject());
                    }
                }
            }
            for (String predicate : others) {
                problem(item, "D8 에 없는 문장입니다: " + predicate + ". 엔진은 이 문장을 읽지 않습니다. 빼세요.");
            }
        }
    }
}
