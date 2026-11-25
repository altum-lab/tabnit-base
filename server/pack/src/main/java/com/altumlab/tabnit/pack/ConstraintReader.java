package com.altumlab.tabnit.pack;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.shacl.ShaclException;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.sparql.graph.GraphFactory;

/**
 * 제약 파일(constraints.ttl)을 읽고 검증한다 (D6 6절, D2 AD-389, AD-393). 제약은 SHACL 로 적는다 (D2 AD-83).
 *
 * <p>여기서는 파일이 받을 수 있는 것인지만 본다: Turtle 로 읽히는가, 받지 않는 것(SPARQL, JavaScript, 규칙,
 * 맞춤 제약 구성요소, 들여오기)이 없는가, 가리키는 유형·속성·관계가 온톨로지에 있는가, SHACL 로 읽히는가.
 * 제약을 자료에 거는 일은 하지 않는다. 받지 않는 것이 아니면, 모르는 표현이라고 거부하지 않는다.
 * 경로에는 이 Pack 의 속성과 관계 유형만 쓴다 (rdf:type 같은 다른 어휘의 주소는 온톨로지에 없으므로 거부된다).
 *
 * <p>돌려주는 것은 파일의 글 그대로다. 이름 없는 정의(빈 노드)의 이름은 읽을 때마다 달라지므로, 읽은 그림을 다시
 * 적어 저장하지 않는다: 같은 파일은 같은 내용이다.
 */
final class ConstraintReader {

    static final String FILE = "constraints.ttl";

    private static final String SH = "http://www.w3.org/ns/shacl#";

    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private static final String OWL_IMPORTS = "http://www.w3.org/2002/07/owl#imports";

    /** 가리키는 것이 개체 유형이어야 하는 항목. */
    private static final Set<String> TYPE_REFERENCES = Set.of(SH + "targetClass", SH + "class");

    /** 가리키는 것이 속성이나 관계 유형이어야 하는 항목. 경로(sh:path)는 따로 본다: 식일 수 있다. */
    private static final Set<String> PROPERTY_REFERENCES = Set.of(SH + "targetSubjectsOf", SH + "targetObjectsOf",
            SH + "equals", SH + "disjoint", SH + "lessThan", SH + "lessThanOrEquals");

    /** 가리키는 것이 주소이면 이 파일 안의 제약이어야 하는 항목. 없는 제약은 조용히 빈 제약으로 읽힌다. */
    private static final Set<String> SHAPE_REFERENCES = Set.of(SH + "node", SH + "property", SH + "qualifiedValueShape",
            SH + "not");

    /** 경로의 식을 이루는 항목. 이것을 따라가며 그 안의 주소를 모은다. */
    private static final Set<String> PATH_STEPS = Set.of(SH + "inversePath", SH + "alternativePath",
            SH + "zeroOrMorePath", SH + "oneOrMorePath", SH + "zeroOrOnePath", RDF + "first", RDF + "rest");

    /**
     * 받지 않는 것 (D2 AD-389). 서술어로든 값으로든 나오면 거부한다. SPARQL 은 외부 주소를 부를 수 있고 걸리는 시간을
     * 가늠할 수 없다. JavaScript 는 코드를 돌린다. 규칙과 함수와 맞춤 구성요소는 검사가 아니거나 검사기를 스스로 적는다.
     */
    private static final Set<String> REFUSED = Set.of("sparql", "select", "ask", "construct", "update", "prefixes",
            "declare", "SPARQLConstraint", "SPARQLConstraintComponent", "SPARQLTarget", "SPARQLTargetType",
            "SPARQLExecutable", "SPARQLSelectExecutable", "SPARQLAskExecutable", "SPARQLConstructExecutable",
            "SPARQLUpdateExecutable", "SPARQLSelectValidator", "SPARQLAskValidator", "SPARQLFunction", "SPARQLRule",
            "js", "jsFunctionName", "jsLibrary", "jsLibraryURL", "JSConstraint", "JSConstraintComponent", "JSTarget",
            "JSTargetType", "JSValidator", "JSFunction", "JSRule", "JSLibrary", "JSExecutable", "target", "Target",
            "TargetType", "rule", "Rule", "TripleRule", "condition", "expression", "Function", "ConstraintComponent",
            "validator", "nodeValidator", "propertyValidator", "parameter", "Parameter",
            // 개체 하나를 주소로 가리키는 대상. 개체의 주소는 검사할 때마다 제품이 매기므로 제약 파일에 적을 수 없다.
            // 받으면 자료에 없는 것을 대상으로 삼아 개체 없는 위반을 만든다 (D2 AD-395).
            "targetNode");

    private static final String UNREADABLE = "SHACL 로 읽을 수 없거나 자료에 걸 수 없는 제약입니다. 흔한 까닭: 개체 자신에 건 제약에 개수(sh:minCount,"
            + " sh:maxCount)를 적었다 (개수는 sh:property 안에 적는다), 속성의 제약(sh:property)에 경로(sh:path)가"
            + " 없거나 둘 이상이다, 개수나 길이의 값이 정수가 아니다, 값이 하나여야 하는 항목에 여럿을 적었다, 목록( … )으로"
            + " 적어야 하는 곳에 값을 그냥 적었다. 이 제약의 적는 법을 확인해 고치세요.";

    private static final String TOO_DEEP = "정의가 너무 깊게 겹쳐 있어 읽을 수 없습니다. 대괄호 [ … ] 안에 대괄호를 수천 겹으로 겹쳐 적지"
            + " 마세요. 제약을 이름 있는 제약 여럿으로 나눠 적으세요.";

    private final String prefix;

    private final Set<String> entityTypes;

    private final Set<String> properties;

    private final List<PackProblem> problems;

    private ConstraintReader(String prefix, Set<String> entityTypes, Set<String> properties, List<PackProblem> problems) {
        this.prefix = prefix;
        this.entityTypes = entityTypes;
        this.properties = properties;
        this.problems = problems;
    }

    /**
     * @param entityTypes 온톨로지의 개체 유형의 이름
     * @param properties 온톨로지의 속성과 관계 유형의 이름
     * @return 파일의 글. 문제가 있으면 문제를 남기고 null
     */
    static String read(Path directory, String prefix, Set<String> entityTypes, Set<String> properties,
            List<PackProblem> problems) {
        return new ConstraintReader(prefix, entityTypes, properties, problems).read(directory.resolve(FILE));
    }

    private String read(Path path) {
        if (!Files.isRegularFile(path)) {
            problem("", "파일이 없습니다. Pack 폴더에 " + FILE + " 을(를) 두세요. 제약이 없는 Pack 은 빈 파일을 둡니다.");
            return null;
        }
        String text;
        try {
            // 틀린 바이트를 다른 글자로 바꿔 읽지 않는다: 저장한 글이 파일과 달라진다.
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path)))
                .toString();
        }
        catch (CharacterCodingException e) {
            problem("", "파일이 UTF-8 이 아닙니다. UTF-8 로 저장해 다시 적재하세요.");
            return null;
        }
        catch (IOException e) {
            problem("", "파일을 읽지 못했습니다: " + e.getMessage() + ". 파일의 권한과 상태를 확인하세요.");
            return null;
        }
        if (text.startsWith("\uFEFF")) {
            problem("", "파일의 맨 앞에 BOM (바이트 순서 표시)이 있습니다. BOM 없는 UTF-8 로 저장해 다시 적재하세요.");
            return null;
        }
        if (text.indexOf('\u0000') >= 0) {
            problem("", "파일에 저장할 수 없는 글자(NUL)가 들어 있습니다. 그 글자를 빼고 다시 적재하세요.");
            return null;
        }
        Graph graph = GraphFactory.createDefaultGraph();
        try {
            RDFParser.fromString(text, Lang.TURTLE).errorHandler(new PackReader.StopAtFirst()).parse(graph);
        }
        catch (RiotException e) {
            problem("", "Turtle 문법이 틀렸습니다: " + e.getMessage() + ". 그 줄을 고치세요.");
            return null;
        }
        catch (StackOverflowError e) {
            // 수천 겹으로 겹친 정의를 읽다가 넘친 것이다. 예기치 않은 오류로 끝내지 않고 문제로 알린다.
            problem("", TOO_DEEP);
            return null;
        }
        int before = problems.size();
        checkRefused(graph);
        checkReferences(graph);
        if (problems.size() > before) {
            return null;
        }
        // 받지 않는 것이 없을 때만 SHACL 로 읽어 본다. 읽는 것까지만 한다: 자료에 걸지 않는다.
        if (!readable(graph)) {
            checkShapes(graph);
            return null;
        }
        return text;
    }

    /**
     * SHACL 로 읽히는가. 읽는 라이브러리의 문구는 쓰지 않는다: 이름 없는 정의의 이름이 섞여 읽을 때마다 달라진다.
     * 값의 꼴이 틀리면 SHACL 의 예외가 아닌 것도 나오므로 모두 잡는다.
     */
    private boolean readable(Graph graph) {
        try {
            Shapes shapes = Shapes.parse(graph);
            // 읽히더라도 걸 때 터지는 제약이 있다 (개체 자신에 건 개수의 제약 따위). 유형마다 개체 하나씩 둔
            // 지어낸 자료에 한 번 걸어 본다: 적재는 통과하고 물을 때 오류가 되는 것을 여기서 막는다.
            ShaclValidator.get().validate(shapes, sample());
            return true;
        }
        catch (ShaclException e) {
            return false;
        }
        catch (RuntimeException e) {
            return false;
        }
        catch (StackOverflowError e) {
            // 너무 깊게 겹쳤거나 제 자신을 가리키는 정의를 따라가다 넘친 것이다. 읽을 수 없는 제약으로 알린다.
            return false;
        }
    }

    /** 유형마다 개체 하나씩. 걸어 보는 데만 쓴다. */
    private Graph sample() {
        Graph sample = GraphFactory.createDefaultGraph();
        Node type = org.apache.jena.graph.NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        int next = 0;
        for (String entityType : new TreeSet<>(entityTypes)) {
            sample.add(org.apache.jena.graph.Triple.create(
                    org.apache.jena.graph.NodeFactory.createURI("urn:tabnit:sample:" + next++), type,
                    org.apache.jena.graph.NodeFactory.createURI(prefix + entityType)));
        }
        return sample;
    }

    /** 어느 제약이 읽히지 않는지 찾아 알린다. 이름 있는 제약마다 그 제약에 딸린 것만 떼어 읽어 본다. */
    private void checkShapes(Graph graph) {
        TreeMap<String, Node> named = named(graph);
        int before = problems.size();
        named.forEach((uri, subject) -> {
            Graph part = GraphFactory.createDefaultGraph();
            ArrayList<Node> open = new ArrayList<>(List.of(subject));
            Set<Node> seen = new java.util.HashSet<>();
            while (!open.isEmpty()) {
                Node node = open.remove(open.size() - 1);
                if (!seen.add(node)) {
                    continue;
                }
                graph.find(node, Node.ANY, Node.ANY).forEachRemaining(triple -> {
                    part.add(triple);
                    if (triple.getObject().isBlank()) {
                        open.add(triple.getObject());
                    }
                });
            }
            if (!readable(part)) {
                problem(itemOf(subject), UNREADABLE);
            }
        });
        if (problems.size() == before) {
            // 제약 하나씩은 읽히는데 함께는 읽히지 않는다. 어느 것인지 짚지 못한다.
            problem("", UNREADABLE);
        }
    }

    /** 받지 않는 것을 찾는다. 같은 제약의 같은 것은 한 번만 알린다. */
    private void checkRefused(Graph graph) {
        TreeMap<String, TreeSet<String>> found = new TreeMap<>(PackOrder.STRINGS);
        Map<Node, String> owners = owners(graph);
        graph.find().forEachRemaining(triple -> {
            String item = owners.getOrDefault(triple.getSubject(), PackReader.UNNAMED);
            if (triple.getPredicate().isURI() && triple.getPredicate().getURI().equals(OWL_IMPORTS)) {
                found.computeIfAbsent(item, k -> new TreeSet<>(PackOrder.STRINGS)).add("owl:imports");
            }
            for (Node node : List.of(triple.getPredicate(), triple.getObject())) {
                if (node.isURI() && node.getURI().startsWith(SH) && REFUSED.contains(node.getURI().substring(SH.length()))) {
                    found.computeIfAbsent(item, k -> new TreeSet<>(PackOrder.STRINGS))
                        .add("sh:" + node.getURI().substring(SH.length()));
                }
            }
        });
        found.forEach((item, terms) -> problem(item, String.join(", ", terms) + " 은(는) 받지 않습니다. SPARQL 이나 JavaScript 로"
                + " 적는 제약과 대상, 규칙, 맞춤 제약 구성요소, 함수, 다른 문서를 들여오는 것은 쓸 수 없습니다 (D6 6절)."
                + " 이 부분을 빼거나 SHACL 의 다른 제약으로 바꿔 적으세요."));
    }

    /** 제약이 가리키는 유형, 속성, 관계 유형이 온톨로지에 있는지 본다. */
    private void checkReferences(Graph graph) {
        TreeSet<PackProblem> found = new TreeSet<>(PackOrder.PROBLEMS);
        Map<Node, String> owners = owners(graph);
        graph.find().forEachRemaining(triple -> {
            if (!triple.getPredicate().isURI()) {
                return;
            }
            String predicate = triple.getPredicate().getURI();
            String item = owners.getOrDefault(triple.getSubject(), PackReader.UNNAMED);
            String shown = "sh:" + (predicate.startsWith(SH) ? predicate.substring(SH.length()) : predicate);
            if (TYPE_REFERENCES.contains(predicate)) {
                String name = name(triple.getObject());
                if (name == null || !entityTypes.contains(name)) {
                    found.add(new PackProblem(FILE, item, shown + " 이(가) 가리키는 " + PackReader.show(triple.getObject())
                            + " 이(가) 온톨로지의 개체 유형이 아닙니다. 이름과 주소를 확인하세요. 이 Pack 의 주소는 <" + prefix
                            + "이름> 의 꼴입니다."));
                }
            }
            else if (PROPERTY_REFERENCES.contains(predicate)) {
                requireProperty(triple.getObject(), shown, item, found);
            }
            else if (SHAPE_REFERENCES.contains(predicate) && triple.getObject().isURI()
                    && !graph.contains(triple.getObject(), Node.ANY, Node.ANY)) {
                found.add(new PackProblem(FILE, item, shown + " 이(가) 가리키는 " + PackReader.show(triple.getObject())
                        + " 이(가) 이 파일에 없는 제약입니다. 이름을 확인하거나 그 제약을 적으세요."));
            }
            else if (predicate.equals(SH + "path")) {
                for (Node step : pathAddresses(graph, triple.getObject())) {
                    requireProperty(step, shown, item, found);
                }
            }
        });
        problems.addAll(found);
    }

    private void requireProperty(Node node, String shown, String item, TreeSet<PackProblem> found) {
        String name = name(node);
        if (name == null || !properties.contains(name)) {
            found.add(new PackProblem(FILE, item, shown + " 이(가) 가리키는 " + PackReader.show(node)
                    + " 이(가) 온톨로지의 속성도 관계 유형도 아닙니다. 이름과 주소를 확인하세요. 이 Pack 의 주소는 <" + prefix
                    + "이름> 의 꼴입니다."));
        }
    }

    /** 경로에 든 주소. 경로가 주소 하나면 그것이고, 식이면 그 안을 따라가며 모은다. 글자 값은 경로가 아니다. */
    private static List<Node> pathAddresses(Graph graph, Node start) {
        List<Node> addresses = new ArrayList<>();
        ArrayList<Node> open = new ArrayList<>(List.of(start));
        Set<Node> seen = new java.util.HashSet<>();
        while (!open.isEmpty()) {
            Node node = open.remove(open.size() - 1);
            if (node.isURI()) {
                if (!node.getURI().equals(RDF + "nil")) {
                    addresses.add(node);
                }
                continue;
            }
            if (node.isLiteral()) {
                // 글자 값은 속성도 관계 유형도 아니다. 그대로 넘겨 문제로 알리게 한다.
                addresses.add(node);
                continue;
            }
            if (!seen.add(node)) {
                continue;
            }
            graph.find(node, Node.ANY, Node.ANY).forEachRemaining(triple -> {
                if (triple.getPredicate().isURI() && PATH_STEPS.contains(triple.getPredicate().getURI())) {
                    open.add(triple.getObject());
                }
            });
        }
        // 그림을 읽는 차례에 기대지 않는다: 문제는 모은 뒤 정해진 차례로 놓인다.
        return addresses;
    }

    /**
     * 이름 없는 정의(빈 노드)가 어느 이름 있는 제약에 딸렸는지. 문제를 알릴 때 그 제약의 이름을 항목으로 쓴다.
     * 여러 제약에 딸렸으면 이름의 글자 코드 순으로 처음 것이다.
     */
    private Map<Node, String> owners(Graph graph) {
        return owners(graph, prefix);
    }

    /** 같은 꾸러미의 검사기도 쓴다 ({@link ConstraintChecker}): 위반이 어느 이름 있는 제약의 것인지 알린다. */
    static Map<Node, String> owners(Graph graph, String prefix) {
        Map<Node, String> owners = new java.util.HashMap<>();
        named(graph).forEach((uri, subject) -> {
            String item = uri.startsWith(prefix) && uri.length() > prefix.length() ? uri.substring(prefix.length()) : uri;
            ArrayList<Node> open = new ArrayList<>(List.of(subject));
            while (!open.isEmpty()) {
                Node node = open.remove(open.size() - 1);
                if (owners.putIfAbsent(node, item) != null) {
                    continue;
                }
                graph.find(node, Node.ANY, Node.ANY).forEachRemaining(triple -> {
                    if (triple.getObject().isBlank()) {
                        open.add(triple.getObject());
                    }
                });
            }
        });
        return owners;
    }

    /** 이름 있는 정의. 주소의 글자 코드 순이다 (주소는 서로 겹치지 않는다). */
    private static TreeMap<String, Node> named(Graph graph) {
        TreeMap<String, Node> named = new TreeMap<>(PackOrder.STRINGS);
        graph.find().forEachRemaining(triple -> {
            if (triple.getSubject().isURI()) {
                named.put(triple.getSubject().getURI(), triple.getSubject());
            }
        });
        return named;
    }

    /** 이 Pack 의 주소면 이름만, 아니면 주소 전체. */
    private String itemOf(Node subject) {
        String name = name(subject);
        return name != null ? name : subject.getURI();
    }

    /** 이 Pack 의 주소 뒤에 오는 이름 (D2 AD-89). 이 Pack 의 주소가 아니면 null. */
    private String name(Node node) {
        if (!node.isURI() || !node.getURI().startsWith(prefix) || node.getURI().length() == prefix.length()) {
            return null;
        }
        return node.getURI().substring(prefix.length());
    }

    private void problem(String item, String message) {
        problems.add(new PackProblem(FILE, item, message));
    }
}
