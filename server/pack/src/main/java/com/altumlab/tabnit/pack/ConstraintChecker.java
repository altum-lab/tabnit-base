package com.altumlab.tabnit.pack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.validation.ReportEntry;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.sparql.path.P_Alt;
import org.apache.jena.sparql.path.P_Inverse;
import org.apache.jena.sparql.path.P_Link;
import org.apache.jena.sparql.path.P_Path1;
import org.apache.jena.sparql.path.P_Seq;
import org.apache.jena.sparql.path.Path;

/**
 * 넘겨받은 자료에 Pack 의 제약(SHACL)을 걸어 위반을 돌려준다 (D6 6절, D2 AD-389 ~ AD-392, AD-395).
 *
 * <p>자료를 RDF 의 그림으로 바꾸어 건다: 개체는 그 유형의 것이고 (상위 유형도 함께 알린다), 속성 값은 온톨로지가 정한
 * 형식의 값이고, 관계는 출발 개체에서 도착 개체로 가는 문장이다. 저장하지 않고, 저장소를 읽지 않는다. 같은 자료와
 * 같은 제약이면 넘긴 차례와 무관하게 같은 위반이 같은 순서로 나온다. 검사하는 라이브러리의 영어 문구와 이름 없는
 * 정의의 이름은 결과에 넣지 않는다.
 */
public final class ConstraintChecker {

    private static final String SH = "http://www.w3.org/ns/shacl#";

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private static final String SUB_CLASS_OF = "http://www.w3.org/2000/01/rdf-schema#subClassOf";

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    /** 개체의 주소의 앞부분. 뒤에 차례의 수를 붙인다: 식별자의 글자를 주소에 넣지 않는다. */
    private static final String ENTITY = "urn:tabnit:entity:";

    private static final String COMPONENT = "ConstraintComponent";

    /** 순서: 심각한 것부터, 개체의 식별자, 어긴 곳, 제약의 종류, 값, 제약의 이름, 문구. 모두 글자 코드 순이다. */
    private static final Comparator<String> TEXT = Comparator.nullsLast(PackOrder.STRINGS);

    private static final Comparator<ConstraintViolation> ORDER = Comparator
        .comparing(ConstraintViolation::severity)
        .thenComparing(ConstraintViolation::entityId, TEXT)
        .thenComparing(ConstraintViolation::pathKind)
        .thenComparing(ConstraintViolation::pathName, TEXT)
        .thenComparing(ConstraintViolation::constraintKind, TEXT)
        .thenComparing(ConstraintViolation::value, TEXT)
        .thenComparing(ConstraintViolation::valueEntityId, TEXT)
        .thenComparing(ConstraintViolation::constraintName, TEXT)
        .thenComparing(ConstraintViolation::message, TEXT);

    private ConstraintChecker() {
    }

    /**
     * @param pack 적재된 Pack 의 정의. 제약은 적재할 때 검증한 글이다
     * @param data 제약을 걸 자료
     * @return 위반. 심각한 것부터, 그 안에서 개체의 식별자 순이다. 없으면 빈 목록
     * @throws IllegalStateException 저장된 제약의 글을 읽을 수 없을 때 (적재할 때 검증하므로 일어나지 않아야 한다)
     */
    public static List<ConstraintViolation> check(PackDefinition pack, ConstraintData data) {
        Objects.requireNonNull(pack, "Pack 의 정의는 반드시 있어야 합니다");
        Objects.requireNonNull(data, "제약을 걸 자료는 반드시 있어야 합니다");
        if (pack.constraints().isBlank() || data.entities().isEmpty()) {
            return List.of();
        }
        String prefix = PackReader.PACK_ADDRESS + pack.packId() + ":";
        Graph shapesGraph = GraphFactory.createDefaultGraph();
        Shapes shapes;
        try {
            RDFParser.fromString(pack.constraints(), Lang.TURTLE).errorHandler(new PackReader.StopAtFirst()).parse(shapesGraph);
            shapes = Shapes.parse(shapesGraph);
        }
        catch (RuntimeException | StackOverflowError e) {
            throw new IllegalStateException("Pack " + pack.packId() + " 의 저장된 제약을 읽을 수 없습니다. Pack 을 다시 적재하세요.", e);
        }
        if (shapes.isEmpty()) {
            return List.of();
        }

        // 개체의 주소: 식별자의 글자 코드 순으로 차례를 매긴다. 넘긴 차례에 닿지 않는다.
        TreeMap<String, Node> nodeById = new TreeMap<>(PackOrder.STRINGS);
        for (ConstraintData.Entity entity : data.entities()) {
            nodeById.put(entity.id(), null);
        }
        for (ConstraintData.Link link : data.relations()) {
            nodeById.putIfAbsent(link.sourceId(), null);
            nodeById.putIfAbsent(link.targetId(), null);
        }
        Map<String, String> idByAddress = new TreeMap<>();
        int next = 0;
        for (Map.Entry<String, Node> entry : nodeById.entrySet()) {
            String address = ENTITY + next++;
            entry.setValue(NodeFactory.createURI(address));
            idByAddress.put(address, entry.getKey());
        }

        Map<String, AttributeDefinition> attributes = new TreeMap<>(PackOrder.STRINGS);
        pack.attributes().forEach(attribute -> attributes.put(attribute.name(), attribute));
        Map<String, RelationTypeDefinition> relationTypes = new TreeMap<>(PackOrder.STRINGS);
        pack.relationTypes().forEach(type -> relationTypes.put(type.name(), type));
        Map<String, EntityTypeDefinition> entityTypes = new TreeMap<>(PackOrder.STRINGS);
        pack.entityTypes().forEach(type -> entityTypes.put(type.name(), type));

        Graph graph = GraphFactory.createDefaultGraph();
        Node type = NodeFactory.createURI(RDF_TYPE);
        Node subClassOf = NodeFactory.createURI(SUB_CLASS_OF);
        // 상위 유형을 알린다: 상위 유형에 건 제약이 하위 유형의 개체에도 걸린다.
        for (EntityTypeDefinition definition : pack.entityTypes()) {
            for (String parent : definition.parents()) {
                graph.add(Triple.create(NodeFactory.createURI(prefix + definition.name()), subClassOf,
                        NodeFactory.createURI(prefix + parent)));
            }
        }
        for (ConstraintData.Entity entity : data.entities()) {
            graph.add(Triple.create(nodeById.get(entity.id()), type, NodeFactory.createURI(prefix + entity.entityType())));
        }
        for (ConstraintData.Value value : data.values()) {
            Node subject = nodeById.get(value.entityId());
            if (subject == null) {
                // 개체 없이 온 값이다. 넘긴 자료가 서로 맞지 않는다: 부르는 쪽의 잘못이다.
                throw new IllegalArgumentException("속성 값의 개체가 넘겨받은 개체에 없습니다: " + value.entityId());
            }
            AttributeDefinition attribute = attributes.get(value.attributeName());
            graph.add(Triple.create(subject, NodeFactory.createURI(prefix + value.attributeName()),
                    literal(value.value(), attribute == null ? ValueFormat.STRING : attribute.valueFormat())));
        }
        for (ConstraintData.Link link : data.relations()) {
            graph.add(Triple.create(nodeById.get(link.sourceId()), NodeFactory.createURI(prefix + link.relationType()),
                    nodeById.get(link.targetId())));
        }

        Map<Node, String> owners = ConstraintReader.owners(shapesGraph, prefix);
        // 모든 항목이 같은 위반도 하나로 합치지 않는다: 한 제약에 같은 종류를 값 여럿으로 적으면 (꼴 둘, 범위 둘)
        // 서로 다른 위반이 같은 글자로 보인다. 합치면 수가 줄어든다.
        List<ConstraintViolation> found = new ArrayList<>();
        java.util.Collection<ReportEntry> entries;
        try {
            entries = ShaclValidator.get().validate(shapes, graph).getEntries();
        }
        catch (RuntimeException | StackOverflowError e) {
            // 적재할 때 한 번 걸어 보므로 일어나지 않아야 한다. 일어나면 검사하는 쪽의 문구를 내보내지 않고 Pack 을 알린다.
            throw new IllegalStateException("Pack " + pack.packId() + " 의 제약을 자료에 걸지 못했습니다. 제약 파일을 확인해 Pack 을"
                    + " 다시 적재하세요.", e);
        }
        for (ReportEntry entry : entries) {
            // 개체의 유형은 제품이 붙인 것이다. 닫힌 제약(sh:closed)이 그것을 "정해지지 않은 속성"으로 알리지 않게 한다.
            if (entry.resultPath() instanceof P_Link link && link.getNode().isURI() && link.getNode().getURI().equals(RDF_TYPE)
                    && entry.sourceConstraintComponent() != null && entry.sourceConstraintComponent().isURI()
                    && entry.sourceConstraintComponent().getURI().equals(SH + "Closed" + COMPONENT)) {
                continue;
            }
            found.add(violation(entry, shapesGraph, owners, prefix, idByAddress, attributes, relationTypes, entityTypes));
        }
        found.sort(ORDER);
        return List.copyOf(found);
    }

    private static Node literal(String value, ValueFormat format) {
        String datatype = switch (format) {
            case STRING -> "string";
            case INTEGER -> "integer";
            case DECIMAL -> "decimal";
            case DATE -> "date";
            case BOOLEAN -> "boolean";
        };
        // 꼴이 틀린 값도 그 형식의 값으로 넣는다: 자료형의 제약이 그것을 위반으로 알린다.
        return NodeFactory.createLiteralDT(value, TypeMapper.getInstance().getSafeTypeByName(XSD + datatype));
    }

    private static ConstraintViolation violation(ReportEntry entry, Graph shapesGraph, Map<Node, String> owners, String prefix,
            Map<String, String> idByAddress, Map<String, AttributeDefinition> attributes,
            Map<String, RelationTypeDefinition> relationTypes, Map<String, EntityTypeDefinition> entityTypes) {
        Node focus = entry.focusNode();
        String entityId = focus != null && focus.isURI() ? idByAddress.get(focus.getURI()) : null;

        ConstraintViolation.PathKind pathKind = ConstraintViolation.PathKind.NONE;
        String pathName = null;
        String where = "이 개체";
        Path path = entry.resultPath();
        if (path != null) {
            String name = path instanceof P_Link link ? name(link.getNode(), prefix) : null;
            if (name != null && attributes.containsKey(name)) {
                pathKind = ConstraintViolation.PathKind.ATTRIBUTE;
                pathName = name;
                where = "속성 '" + attributes.get(name).label() + "'";
            }
            else if (name != null && relationTypes.containsKey(name)) {
                pathKind = ConstraintViolation.PathKind.RELATION;
                pathName = name;
                where = "관계 '" + relationTypes.get(name).label() + "'";
            }
            else {
                pathKind = ConstraintViolation.PathKind.EXPRESSION;
                pathName = show(path, prefix);
                where = "경로 '" + pathName + "'";
            }
        }

        String value = null;
        String valueEntityId = null;
        Node valueNode = entry.value();
        if (valueNode != null && valueNode.isLiteral()) {
            value = valueNode.getLiteralLexicalForm();
        }
        else if (valueNode != null && valueNode.isURI() && pathKind == ConstraintViolation.PathKind.NONE
                && valueNode.equals(focus)) {
            // 개체 자신에 건 제약에서는 "값"이 그 개체다. 가리키는 개체가 따로 있는 것이 아니다.
            valueEntityId = null;
        }
        else if (valueNode != null && valueNode.isURI()) {
            valueEntityId = idByAddress.get(valueNode.getURI());
            if (valueEntityId == null) {
                String named = name(valueNode, prefix);
                value = named != null ? named : valueNode.getURI();
            }
        }

        Node component = entry.sourceConstraintComponent();
        String kind = component != null && component.isURI() && component.getURI().startsWith(SH)
                ? component.getURI().substring(SH.length())
                : "Unknown";
        if (kind.endsWith(COMPONENT) && kind.length() > COMPONENT.length()) {
            kind = kind.substring(0, kind.length() - COMPONENT.length());
        }

        ConstraintViolation.Severity severity = ConstraintViolation.Severity.VIOLATION;
        if (entry.severity() != null && entry.severity().level() != null && entry.severity().level().isURI()) {
            String level = entry.severity().level().getURI();
            if (level.equals(SH + "Warning")) {
                severity = ConstraintViolation.Severity.WARNING;
            }
            else if (level.equals(SH + "Info")) {
                severity = ConstraintViolation.Severity.INFO;
            }
        }

        Node source = entry.source();
        String constraintName = source == null ? null : owners.get(source);
        String message = authorMessage(shapesGraph, source);
        if (message == null) {
            message = ConstraintMessages.of(kind, where, pathKind, value, valueEntityId != null,
                    parameters(shapesGraph, source, prefix, entityTypes, attributes, relationTypes));
        }
        return new ConstraintViolation(severity, entityId, pathKind, pathName, value, valueEntityId, kind, constraintName,
                message);
    }

    /**
     * 제약에 적은 문구 (sh:message). 여럿이면 한국어로 표시한 것(@ko), 언어 표시가 없는 것, 그 밖의 것 차례로 고르고,
     * 그 안에서는 글자 코드 순으로 처음 것이다. 없으면 null.
     */
    private static String authorMessage(Graph shapesGraph, Node source) {
        if (source == null) {
            return null;
        }
        TreeSet<String> messages = new TreeSet<>(PackOrder.STRINGS);
        shapesGraph.find(source, NodeFactory.createURI(SH + "message"), Node.ANY).forEachRemaining(triple -> {
            if (triple.getObject().isLiteral() && !triple.getObject().getLiteralLexicalForm().isBlank()) {
                String language = triple.getObject().getLiteralLanguage();
                String rank = language.equalsIgnoreCase("ko") || language.toLowerCase(java.util.Locale.ROOT).startsWith("ko-")
                        ? "0"
                        : language.isEmpty() ? "1" : "2";
                messages.add(rank + triple.getObject().getLiteralLexicalForm());
            }
        });
        return messages.isEmpty() ? null : messages.first().substring(1);
    }

    /**
     * 제약에 적은 값들 (sh:minCount 3 이면 minCount → 3). 문구에 쓴다. 이름 없는 정의는 넣지 않는다. 같은 항목에 값을
     * 여럿 적었으면 글자 코드 순으로 모두 잇는다 (어느 값을 어겼는지는 가리지 못한다). 유형을 가리키면 그 유형의 이름표로 바꾼다.
     */
    private static Map<String, String> parameters(Graph shapesGraph, Node source, String prefix,
            Map<String, EntityTypeDefinition> entityTypes, Map<String, AttributeDefinition> attributes,
            Map<String, RelationTypeDefinition> relationTypes) {
        Map<String, TreeSet<String>> collected = new TreeMap<>();
        if (source != null) {
            shapesGraph.find(source, Node.ANY, Node.ANY).forEachRemaining(triple -> {
                if (!triple.getPredicate().isURI() || !triple.getPredicate().getURI().startsWith(SH)) {
                    return;
                }
                Node object = triple.getObject();
                String shown;
                if (object.isLiteral()) {
                    shown = object.getLiteralLexicalForm();
                }
                else if (object.isURI()) {
                    String named = name(object, prefix);
                    EntityTypeDefinition type = named == null ? null : entityTypes.get(named);
                    // 유형, 속성, 관계 유형을 가리키면 그 이름표로 적는다.
                    shown = type != null ? type.label()
                            : named != null && attributes.containsKey(named) ? attributes.get(named).label()
                            : named != null && relationTypes.containsKey(named) ? relationTypes.get(named).label()
                            : named != null ? named
                            : object.getURI().startsWith(XSD) ? object.getURI().substring(XSD.length()) : object.getURI();
                }
                else {
                    return;
                }
                collected.computeIfAbsent(triple.getPredicate().getURI().substring(SH.length()),
                        k -> new TreeSet<>(PackOrder.STRINGS)).add(shown);
            });
        }
        Map<String, String> parameters = new TreeMap<>();
        collected.forEach((name, values) -> parameters.put(name, String.join(", ", values)));
        return parameters;
    }

    /** 경로의 식을 글자로 적는다. 이 Pack 의 이름은 이름만, 거꾸로는 ^, 이어 따라가기는 /, 갈래는 | 로 적는다. */
    private static String show(Path path, String prefix) {
        if (path instanceof P_Link link) {
            String name = name(link.getNode(), prefix);
            return name != null ? name : link.getNode().isURI() ? link.getNode().getURI() : "(이름 없음)";
        }
        if (path instanceof P_Inverse inverse) {
            return "^" + show(inverse.getSubPath(), prefix);
        }
        if (path instanceof P_Seq sequence) {
            return show(sequence.getLeft(), prefix) + "/" + show(sequence.getRight(), prefix);
        }
        if (path instanceof P_Alt alternative) {
            return "(" + show(alternative.getLeft(), prefix) + "|" + show(alternative.getRight(), prefix) + ")";
        }
        if (path instanceof P_Path1 repeated) {
            // 되풀이해 따라가는 경로: 0 번 이상은 *, 1 번 이상은 +, 0 번이나 1 번은 ?.
            String mark = path instanceof org.apache.jena.sparql.path.P_OneOrMore1
                    || path instanceof org.apache.jena.sparql.path.P_OneOrMoreN ? "+"
                            : path instanceof org.apache.jena.sparql.path.P_ZeroOrOne ? "?" : "*";
            return "(" + show(repeated.getSubPath(), prefix) + ")" + mark;
        }
        return "(경로의 식)";
    }

    private static String name(Node node, String prefix) {
        if (node == null || !node.isURI() || !node.getURI().startsWith(prefix) || node.getURI().length() == prefix.length()) {
            return null;
        }
        return node.getURI().substring(prefix.length());
    }
}
