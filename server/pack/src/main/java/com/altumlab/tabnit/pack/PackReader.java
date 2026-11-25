package com.altumlab.tabnit.pack;

import java.nio.file.Files;
import java.text.Normalizer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ErrorHandler;
import org.apache.jena.sparql.graph.GraphFactory;

/**
 * Pack 폴더의 정의 파일을 읽어 내부 모델로 만들고, 정의가 서로 맞는지 확인한다 (D6 4절, 5절, 9절).
 *
 * <p>읽는 것은 D6 에 적힌 항목뿐이다. D6 에 없는 문장은 무시하지 않고 문제로 알린다 (D2 AD-88).
 * 문제는 찾는 대로 모아 한 번에 모두 알린다. RDF 를 읽는 라이브러리는 이 클래스 안에서만 쓴다 (D2 AD-15).
 *
 * <p>읽는 파일은 pack.ttl, ontology.ttl, constraints.ttl, mappings 폴더의 매핑 정의, rules 폴더의 규칙 정의다
 * (제약은 {@link ConstraintReader}, 매핑은 {@link MappingReader}, 규칙은 {@link RuleReader}).
 */
final class PackReader {

    static final String PACK_FILE = "pack.ttl";

    static final String ONTOLOGY_FILE = "ontology.ttl";

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";

    private static final String OWL = "http://www.w3.org/2002/07/owl#";

    static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    /** Pack 이 적을 수 있는 최대 깊이의 상한 (D6 4절, D2 AD-333). */
    static final int MAX_DEPTH_LIMIT = 20;

    static final String VOCAB = "urn:tabnit:vocab:";

    static final String PACK_ADDRESS = "urn:tabnit:pack:";

    /** 이름 없는 정의나 값을 문구에 적을 때 쓰는 글자. 주소(urn:…)와 겹치지 않는다. */
    static final String UNNAMED = "(이름 없음)";

    private static final String LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString";

    private static final String LABEL = RDFS + "label";

    private static final String OWL_CLASS = OWL + "Class";

    private static final String OWL_OBJECT_PROPERTY = OWL + "ObjectProperty";

    private static final String OWL_DATATYPE_PROPERTY = OWL + "DatatypeProperty";

    private static final Map<String, PackImpactDirection> DIRECTIONS = Map.of(VOCAB + "SourceToTarget",
            PackImpactDirection.SOURCE_TO_TARGET, VOCAB + "TargetToSource", PackImpactDirection.TARGET_TO_SOURCE,
            VOCAB + "Both", PackImpactDirection.BOTH, VOCAB + "None", PackImpactDirection.NONE);

    private static final Map<String, ValueFormat> FORMATS = Map.of(XSD + "string", ValueFormat.STRING, XSD + "integer",
            ValueFormat.INTEGER, XSD + "decimal", ValueFormat.DECIMAL, XSD + "date", ValueFormat.DATE, XSD + "boolean",
            ValueFormat.BOOLEAN);

    /** 읽은 결과. 문제가 하나라도 있으면 정의는 null 이다. */
    record Result(PackDefinition definition, List<PackProblem> problems, List<PackWarning> warnings) {

        /** 문제가 있어 정의를 만들지 못했다. 경고는 내지 않는다. */
        Result(PackDefinition definition, List<PackProblem> problems) {
            this(definition, problems, List.of());
        }
    }

    private final Path directory;

    private final String packId;

    /** 이 Pack 의 정의가 쓰는 주소의 앞부분 (D2 AD-89). */
    private final String prefix;

    /** ontology.ttl 을 문법 오류 없이 읽었는가. */
    private boolean ontologyRead;

    private final List<PackProblem> problems = new ArrayList<>();

    /** 언어별 이름표. 정의를 읽으면서 모은다. */
    private final List<LocalizedLabel> localizedLabels = new ArrayList<>();

    private PackReader(Path directory) {
        this.directory = directory;
        // Pack 식별자는 폴더 이름과 같다 (D6 4절).
        this.packId = directory.getFileName().toString();
        this.prefix = PACK_ADDRESS + packId + ":";
    }

    static Result read(Path directory, Instant loadedAt) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized) || normalized.getFileName() == null) {
            return new Result(null, List.of(new PackProblem("", "",
                    "Pack 폴더가 없거나 이름이 없습니다: " + normalized + ". 폴더의 경로를 확인하세요. 폴더 이름이 Pack 식별자입니다.")));
        }
        String packId = normalized.getFileName().toString();
        if (!packId.matches("[a-z][a-z0-9-]*")) {
            return new Result(null, List.of(new PackProblem("", packId, "Pack 식별자(폴더 이름) " + packId
                    + " 을(를) 쓸 수 없습니다. 영어 소문자로 시작하고 영어 소문자, 숫자, 붙임표(-)만 쓰세요.")));
        }
        return new PackReader(normalized).read(loadedAt);
    }

    private Result read(Instant loadedAt) {
        String[] label = new String[1];
        int[] maxDepth = new int[1];
        readPackFile(label, maxDepth);

        TreeMap<String, EntityTypeDefinition> entityTypes = new TreeMap<>(PackOrder.STRINGS);
        TreeMap<String, RelationTypeDefinition> relationTypes = new TreeMap<>(PackOrder.STRINGS);
        TreeMap<String, AttributeDefinition> attributes = new TreeMap<>(PackOrder.STRINGS);
        readOntology(entityTypes, relationTypes, attributes);
        if (ontologyRead && entityTypes.isEmpty() && problems.stream().noneMatch(p -> p.file().equals(ONTOLOGY_FILE))) {
            problem(ONTOLOGY_FILE, "", "개체 유형이 하나도 없습니다. owl:Class 를 하나 이상 정의하세요. 정의의 주소가 <" + prefix
                    + "이름> 의 꼴인지도 확인하세요.");
        }
        checkReferences(entityTypes, relationTypes, attributes);

        // 매핑은 온톨로지를 읽었을 때만 본다. 온톨로지를 읽지 못했으면 가리키는 곳마다 "정의되지 않았습니다"가 따라붙는다.
        List<MappingDefinition> mappings = ontologyRead
                ? MappingReader.read(directory, prefix, entityTypes, relationTypes, attributes, problems)
                : List.of();

        // 규칙도 온톨로지를 읽었을 때만 본다 (D8 2절).
        List<RuleDefinition> rules = ontologyRead ? RuleReader.read(directory, prefix, entityTypes, attributes,
                relationTypes, inputSourceIds(mappings), problems) : List.of();

        // 제약도 온톨로지를 읽었을 때만 본다 (D6 6절). 가리키는 속성은 속성이거나 관계 유형이다.
        String constraints = null;
        if (ontologyRead) {
            TreeSet<String> properties = new TreeSet<>(PackOrder.STRINGS);
            properties.addAll(attributes.keySet());
            properties.addAll(relationTypes.keySet());
            constraints = ConstraintReader.read(directory, prefix, entityTypes.keySet(), properties, problems);
        }

        if (!problems.isEmpty()) {
            // 같은 문제를 두 번 알리지 않고, 차례를 정해 둔다.
            TreeSet<PackProblem> ordered = new TreeSet<>(PackOrder.PROBLEMS);
            ordered.addAll(problems);
            return new Result(null, List.copyOf(ordered), List.of());
        }
        return new Result(new PackDefinition(packId, label[0], maxDepth[0], loadedAt, List.copyOf(entityTypes.values()),
                List.copyOf(relationTypes.values()), List.copyOf(attributes.values()),
                localizedLabels.stream().sorted(PackOrder.LABELS).toList(), mappings, rules, constraints), List.of(),
                RuleWarnings.find(mappings, rules));
    }

    // ── pack.ttl ────────────────────────────────────────────────

    private void readPackFile(String[] label, int[] maxDepth) {
        TreeMap<String, Statements> subjects = parse(PACK_FILE);
        if (subjects == null) {
            return;
        }
        String packType = VOCAB + "Pack";
        int found = 0;
        for (Statements s : subjects.values()) {
            if (!s.uris(RDF_TYPE).contains(packType)) {
                problem(PACK_FILE, s.subject, "D6 에 없는 정의입니다. 이 파일에는 tabnit:Pack 하나만 적습니다. 이 정의를 빼세요.");
                continue;
            }
            found++;
            if (!s.subject.equals(prefix)) {
                problem(PACK_FILE, packId, "Pack 의 주소가 <" + s.subject + "> 입니다. 폴더 이름이 " + packId
                        + " 이므로 <" + prefix + "> 로 적으세요.");
            }
            for (String type : s.uris(RDF_TYPE)) {
                if (!type.equals(packType)) {
                    problem(PACK_FILE, packId, "D6 에 없는 문장입니다: a " + shortenIfUri(type) + ". 이 문장을 빼세요.");
                }
            }
            String id = s.oneText(VOCAB + "packId", "tabnit:packId", PACK_FILE, packId);
            if (id != null && !id.equals(packId)) {
                problem(PACK_FILE, packId, "tabnit:packId 가 \"" + id + "\" 인데 폴더 이름은 " + packId
                        + " 입니다. 둘을 같게 맞추세요.");
            }
            label[0] = s.label(PACK_FILE, packId, "");
            maxDepth[0] = s.maxDepth(packId);
            s.rejectOthers(PACK_FILE, packId, RDF_TYPE, VOCAB + "packId", LABEL, VOCAB + "defaultMaxDepth");
        }
        if (found == 0) {
            problem(PACK_FILE, packId, "tabnit:Pack 정의가 없습니다. <" + prefix + "> a tabnit:Pack 을 적으세요.");
        }
        else if (found > 1) {
            problem(PACK_FILE, packId, "tabnit:Pack 정의가 " + found + "개입니다. 하나만 적으세요.");
        }
    }

    // ── ontology.ttl ────────────────────────────────────────────

    /** 매핑이 받는 입력 자료의 식별자. */
    private static TreeSet<String> inputSourceIds(List<MappingDefinition> mappings) {
        TreeSet<String> ids = new TreeSet<>(PackOrder.STRINGS);
        mappings.forEach(m -> ids.add(m.inputSourceId()));
        return ids;
    }

    private void readOntology(Map<String, EntityTypeDefinition> entityTypes,
            Map<String, RelationTypeDefinition> relationTypes, Map<String, AttributeDefinition> attributes) {
        TreeMap<String, Statements> subjects = parse(ONTOLOGY_FILE);
        if (subjects == null) {
            return;
        }
        ontologyRead = true;
        for (Statements s : subjects.values()) {
            if (s.subject.equals(prefix)) {
                problem(ONTOLOGY_FILE, packId, "D6 에 없는 정의입니다: Pack 자신에 대한 문장(" + String.join(", ", s.predicates())
                        + "). Pack 자신의 정보는 pack.ttl 에만 적습니다. 이 문장을 빼세요.");
                continue;
            }
            String name = localName(s.subject);
            if (name == null) {
                problem(ONTOLOGY_FILE, s.subject, "이 Pack 의 주소로 시작하지 않는 정의입니다: <" + s.subject + ">. <" + prefix
                        + "이름> 의 꼴로 적으세요.");
                continue;
            }
            List<String> types = s.uris(RDF_TYPE);
            if (types.size() != 1 || !(types.get(0).equals(OWL_CLASS) || types.get(0).equals(OWL_OBJECT_PROPERTY)
                    || types.get(0).equals(OWL_DATATYPE_PROPERTY))) {
                problem(ONTOLOGY_FILE, name, "D6 에 없는 정의입니다 (a " + types.stream().map(PackReader::shortenIfUri).toList()
                        + "). owl:Class, owl:ObjectProperty, owl:DatatypeProperty 가운데 하나만 적으세요.");
                continue;
            }
            if (!Normalizer.isNormalized(name, Normalizer.Form.NFC)) {
                // 알리고 계속 읽는다. 여기서 건너뛰면 이 정의를 가리키는 곳마다 "정의되지 않았습니다"가 따라붙는다.
                problem(ONTOLOGY_FILE, name, "이름이 조합형(유니코드 NFC)이 아닙니다. 눈에 같은 이름이 서로 다른 정의가 되지 않도록 조합형으로 다시 적으세요.");
            }
            String label = s.label(ONTOLOGY_FILE, name, name);
            switch (types.get(0)) {
                case OWL_CLASS -> {
                    List<String> parents = s.names(RDFS + "subClassOf", "rdfs:subClassOf", name);
                    List<String> display = s.names(VOCAB + "displayNameProperty", "tabnit:displayNameProperty", name);
                    if (display.size() > 1) {
                        problem(ONTOLOGY_FILE, name, "tabnit:displayNameProperty 가 " + display.size()
                                + "개입니다. 하나만 적으세요.");
                    }
                    s.rejectOthers(ONTOLOGY_FILE, name, RDF_TYPE, LABEL, RDFS + "subClassOf",
                            VOCAB + "displayNameProperty");
                    entityTypes.put(name,
                            new EntityTypeDefinition(name, label, parents, display.size() == 1 ? display.get(0) : null));
                }
                case OWL_OBJECT_PROPERTY -> {
                    String source = s.oneName(RDFS + "domain", "rdfs:domain", name);
                    String target = s.oneName(RDFS + "range", "rdfs:range", name);
                    PackImpactDirection direction = s.oneOf(VOCAB + "impactDirection", "tabnit:impactDirection",
                            DIRECTIONS, "tabnit:SourceToTarget, tabnit:TargetToSource, tabnit:Both, tabnit:None", name);
                    s.rejectOthers(ONTOLOGY_FILE, name, RDF_TYPE, LABEL, RDFS + "domain", RDFS + "range",
                            VOCAB + "impactDirection");
                    relationTypes.put(name, new RelationTypeDefinition(name, label, source, target, direction));
                }
                default -> {
                    String owner = s.oneName(RDFS + "domain", "rdfs:domain", name);
                    ValueFormat format = s.oneOf(RDFS + "range", "rdfs:range", FORMATS,
                            "xsd:string, xsd:integer, xsd:decimal, xsd:date, xsd:boolean", name);
                    s.rejectOthers(ONTOLOGY_FILE, name, RDF_TYPE, LABEL, RDFS + "domain", RDFS + "range");
                    attributes.put(name, new AttributeDefinition(name, label, owner, format));
                }
            }
        }
    }

    /** 정의가 서로 맞는지 본다: 가리키는 유형과 속성이 있는가, 상위 유형이 돌아오는가, 내려받는 값이 충돌하는가. */
    private void checkReferences(Map<String, EntityTypeDefinition> entityTypes,
            Map<String, RelationTypeDefinition> relationTypes, Map<String, AttributeDefinition> attributes) {
        for (EntityTypeDefinition type : entityTypes.values()) {
            for (String parent : type.parents()) {
                if (!entityTypes.containsKey(parent)) {
                    problem(ONTOLOGY_FILE, type.name(), "상위 유형 " + parent + " 이(가) 정의되지 않았습니다. 그 유형을 정의하거나 이름을 고치세요.");
                }
            }
            String display = type.displayNameAttribute();
            if (display != null && !attributes.containsKey(display)) {
                problem(ONTOLOGY_FILE, type.name(), "표시 이름으로 쓸 속성 " + display + " 이(가) 정의되지 않았습니다. 그 속성을 정의하거나 이름을 고치세요.");
            }
            else if (display != null) {
                // 표시 이름 속성은 그 유형이나 그 상위 유형의 속성이어야 한다 (D2 AD-132).
                String owner = attributes.get(display).entityType();
                if (owner != null && entityTypes.containsKey(owner) && !isSameOrAncestor(owner, type.name(), entityTypes)) {
                    problem(ONTOLOGY_FILE, type.name(), "표시 이름으로 쓸 속성 " + display + " 은(는) 유형 " + owner
                            + " 의 속성입니다. 이 유형이나 그 상위 유형의 속성을 적으세요.");
                }
            }
        }
        for (RelationTypeDefinition relation : relationTypes.values()) {
            requireType(entityTypes, relation.name(), "출발 유형(rdfs:domain)", relation.sourceType());
            requireType(entityTypes, relation.name(), "도착 유형(rdfs:range)", relation.targetType());
        }
        for (AttributeDefinition attribute : attributes.values()) {
            requireType(entityTypes, attribute.name(), "개체 유형(rdfs:domain)", attribute.entityType());
        }

        // 상위 유형이 없는 유형부터 차례로 걷어 낸다. 끝까지 남는 유형은 돌아오는 정의에 걸린 것이다 (D2 AD-88).
        // 걷어 내는 차례대로 내려받는 값을 정하므로 상위 유형의 값이 언제나 먼저 정해진다.
        // 사슬이 아주 길어도 되도록 재귀로 따라가지 않는다.
        Map<String, Integer> waiting = new TreeMap<>(PackOrder.STRINGS);
        Map<String, List<String>> children = new TreeMap<>(PackOrder.STRINGS);
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (EntityTypeDefinition type : entityTypes.values()) {
            int defined = 0;
            for (String parent : type.parents()) {
                if (entityTypes.containsKey(parent)) {
                    defined++;
                    children.computeIfAbsent(parent, k -> new ArrayList<>()).add(type.name());
                }
            }
            waiting.put(type.name(), defined);
            if (defined == 0) {
                ready.add(type.name());
            }
        }
        Map<String, TreeSet<String>> displayNames = new TreeMap<>(PackOrder.STRINGS);
        while (!ready.isEmpty()) {
            String name = ready.remove();
            waiting.remove(name);
            EntityTypeDefinition type = entityTypes.get(name);
            // 유형이 쓰게 되는 표시 이름 속성: 스스로 적었으면 그것 하나, 아니면 상위 유형들이 내려주는 값 전부.
            TreeSet<String> values = new TreeSet<>(PackOrder.STRINGS);
            if (type.displayNameAttribute() != null) {
                values.add(type.displayNameAttribute());
            }
            else {
                for (String parent : type.parents()) {
                    values.addAll(displayNames.getOrDefault(parent, new TreeSet<>(PackOrder.STRINGS)));
                }
            }
            displayNames.put(name, values);
            for (String child : children.getOrDefault(name, List.of())) {
                if (waiting.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }
        if (waiting.isEmpty()) {
            // 여러 상위 유형이 서로 다른 값을 내려주는 경우 (D2 AD-88).
            for (Map.Entry<String, TreeSet<String>> entry : displayNames.entrySet()) {
                if (entry.getValue().size() > 1) {
                    problem(ONTOLOGY_FILE, entry.getKey(), "상위 유형들이 서로 다른 표시 이름 속성을 내려줍니다: "
                            + String.join(", ", entry.getValue()) + ". 이 유형에 tabnit:displayNameProperty 를 직접 적으세요.");
                }
            }
            return;
        }
        // 돌아오는 정의가 있다. 내려받는 값의 충돌은 이것부터 고친 뒤에 본다.
        // 남은 유형에서, 남은 하위 유형이 없는 것을 아래쪽부터 걷어 낸다. 그러면 고리 위에 있거나 고리 사이에 낀 유형만 남는다.
        Map<String, Integer> below = new TreeMap<>(PackOrder.STRINGS);
        for (String name : waiting.keySet()) {
            int count = 0;
            for (String child : children.getOrDefault(name, List.of())) {
                if (waiting.containsKey(child)) {
                    count++;
                }
            }
            below.put(name, count);
            if (count == 0) {
                ready.add(name);
            }
        }
        while (!ready.isEmpty()) {
            String name = ready.remove();
            below.remove(name);
            for (String parent : entityTypes.get(name).parents()) {
                if (below.containsKey(parent) && below.merge(parent, -1, Integer::sum) == 0) {
                    ready.add(parent);
                }
            }
        }
        // 고리 하나에 한 건을 알린다. 항목은 그 고리에서 이름이 가장 앞선 유형이다.
        // 이미 알린 고리의 유형만으로 이루어진 다른 고리는 앞의 것을 고친 뒤 다시 적재할 때 드러난다.
        TreeSet<String> reported = new TreeSet<>(PackOrder.STRINGS);
        for (String name : below.keySet()) {
            if (reported.contains(name)) {
                continue;
            }
            List<String> path = pathBackTo(name, entityTypes);
            if (path != null) {
                reported.addAll(path);
                problem(ONTOLOGY_FILE, name, "상위 유형이 돌아와 자기 자신에 닿습니다: " + name + " → " + shortPath(path)
                        + ". rdfs:subClassOf 가운데 하나를 빼세요.");
            }
        }
    }

    /** 경로를 문구에 적는다. 길면 앞과 뒤만 적고 가운데는 수로 줄인다. */
    private static String shortPath(List<String> path) {
        int head = 5;
        int tail = 2;
        if (path.size() <= head + tail) {
            return String.join(" → ", path);
        }
        return String.join(" → ", path.subList(0, head)) + " → … (" + (path.size() - head - tail) + "개 더) … → "
                + String.join(" → ", path.subList(path.size() - tail, path.size()));
    }

    private void requireType(Map<String, EntityTypeDefinition> entityTypes, String item, String what, String type) {
        if (type != null && !entityTypes.containsKey(type)) {
            problem(ONTOLOGY_FILE, item, what + " " + type + " 이(가) 정의되지 않았습니다. 그 유형을 정의하거나 이름을 고치세요.");
        }
    }

    /** ancestor 가 type 자신이거나 그 상위 유형(여러 단계 위 포함)인가. 돌아오는 정의가 있어도 끝난다. */
    static boolean isSameOrAncestor(String ancestor, String type, Map<String, EntityTypeDefinition> entityTypes) {
        TreeSet<String> seen = new TreeSet<>(PackOrder.STRINGS);
        ArrayDeque<String> open = new ArrayDeque<>();
        open.add(type);
        while (!open.isEmpty()) {
            String current = open.remove();
            if (current.equals(ancestor)) {
                return true;
            }
            EntityTypeDefinition definition = entityTypes.get(current);
            if (definition != null && seen.add(current)) {
                open.addAll(definition.parents());
            }
        }
        return false;
    }

    /**
     * 상위 유형을 따라가 자기 자신에 닿는 길을 찾는다. 없으면 null.
     * 상위 유형을 이름 순으로, 깊이부터 따라간다. 그래서 언제나 같은 길이지만 가장 짧은 길은 아닐 수 있다.
     */
    private static List<String> pathBackTo(String start, Map<String, EntityTypeDefinition> entityTypes) {
        TreeSet<String> seen = new TreeSet<>(PackOrder.STRINGS);
        ArrayDeque<String> path = new ArrayDeque<>();
        ArrayDeque<Iterator<String>> open = new ArrayDeque<>();
        open.push(entityTypes.get(start).parents().iterator());
        while (!open.isEmpty()) {
            Iterator<String> parents = open.peek();
            if (!parents.hasNext()) {
                open.pop();
                path.pollLast();
                continue;
            }
            String parent = parents.next();
            if (parent.equals(start)) {
                path.addLast(parent);
                return List.copyOf(path);
            }
            EntityTypeDefinition type = entityTypes.get(parent);
            if (type != null && seen.add(parent)) {
                path.addLast(parent);
                open.push(type.parents().iterator());
            }
        }
        return null;
    }

    // ── 읽기 ────────────────────────────────────────────────────

    /** 파일을 읽어 주어마다 문장을 모은다. 읽지 못하면 문제를 남기고 null 을 돌려준다. */
    private TreeMap<String, Statements> parse(String file) {
        Path path = directory.resolve(file);
        if (!Files.isRegularFile(path)) {
            problem(file, "", "파일이 없습니다. Pack 폴더에 " + file + " 을(를) 두세요.");
            return null;
        }
        Graph graph = GraphFactory.createDefaultGraph();
        try {
            RDFParser.source(path).lang(Lang.TURTLE).errorHandler(new StopAtFirst()).parse(graph);
        }
        catch (RiotException e) {
            problem(file, "", "Turtle 문법이 틀렸습니다: " + e.getMessage() + ". 그 줄을 고치세요.");
            return null;
        }
        TreeMap<String, Statements> subjects = new TreeMap<>(PackOrder.STRINGS);
        graph.find().forEachRemaining(triple -> {
            Node subject = triple.getSubject();
            // 이름 없는 정의(빈 노드)의 라벨은 읽을 때마다 달라진다. 라벨을 쓰지 않고 한 항목으로 묶는다.
            String key = subject.isURI() ? subject.getURI() : UNNAMED;
            subjects.computeIfAbsent(key, k -> new Statements(k)).triples.add(triple);
        });
        if (subjects.remove(UNNAMED) != null) {
            problem(file, UNNAMED, "이름 없는 정의(빈 노드)는 D6 에 없습니다. 주소를 붙여 적거나 빼세요.");
        }
        return subjects;
    }

    /** 이 Pack 의 주소 뒤에 오는 부분이 이름이다 (D2 AD-89). 이 Pack 의 주소가 아니거나 이름이 비었으면 null. */
    private String localName(String uri) {
        if (!uri.startsWith(prefix) || uri.length() == prefix.length()) {
            return null;
        }
        return uri.substring(prefix.length());
    }

    private void problem(String file, String item, String message) {
        problems.add(new PackProblem(file, item, message));
    }

    /** 값을 문구에 적는다. 이름 없는 값(빈 노드)의 라벨은 읽을 때마다 달라지므로 적지 않는다. */
    static String show(Node node) {
        if (node.isURI()) {
            return shorten(node.getURI());
        }
        if (node.isLiteral()) {
            return "\"" + node.getLiteralLexicalForm() + "\"";
        }
        return UNNAMED;
    }

    private static String shorten(String uri) {
        if (uri.startsWith(RDFS)) {
            return "rdfs:" + uri.substring(RDFS.length());
        }
        if (uri.startsWith(OWL)) {
            return "owl:" + uri.substring(OWL.length());
        }
        if (uri.startsWith(XSD)) {
            return "xsd:" + uri.substring(XSD.length());
        }
        if (uri.startsWith(VOCAB)) {
            return "tabnit:" + uri.substring(VOCAB.length());
        }
        return "<" + uri + ">";
    }

    private static String shortenIfUri(String value) {
        return value.equals(UNNAMED) || value.startsWith("\"") ? value : shorten(value);
    }

    /** 경고도 오류로 본다. 처음 만난 곳에서 멈추고 줄과 칸을 알린다. 기록은 남기지 않는다. */
    static final class StopAtFirst implements ErrorHandler {

        @Override
        public void warning(String message, long line, long col) {
            throw new RiotException(line + "행 " + col + "열, " + message);
        }

        @Override
        public void error(String message, long line, long col) {
            throw new RiotException(line + "행 " + col + "열, " + message);
        }

        @Override
        public void fatal(String message, long line, long col) {
            throw new RiotException(line + "행 " + col + "열, " + message);
        }
    }

    /** 주어 하나의 문장들. */
    private final class Statements {

        final String subject;

        final List<Triple> triples = new ArrayList<>();

        Statements(String subject) {
            this.subject = subject;
        }

        private List<Node> objects(String predicate) {
            List<Node> objects = new ArrayList<>();
            for (Triple triple : triples) {
                if (triple.getPredicate().isURI() && triple.getPredicate().getURI().equals(predicate)) {
                    objects.add(triple.getObject());
                }
            }
            return objects;
        }

        /** 그 술어의 값 가운데 주소인 것. 글자 코드 순. 주소가 아닌 값은 여기서 문제로 알리지 않는다 (rdf:type 용). */
        List<String> uris(String predicate) {
            TreeSet<String> uris = new TreeSet<>(PackOrder.STRINGS);
            for (Node object : objects(predicate)) {
                uris.add(object.isURI() ? object.getURI() : show(object));
            }
            return List.copyOf(uris);
        }

        /** 글자 값이 꼭 하나여야 하는 항목. 아니면 문제를 남기고 null. */
        String oneText(String predicate, String shown, String file, String item) {
            List<Node> objects = objects(predicate);
            if (objects.size() != 1) {
                problem(file, item, shown + " 은(는) 글자 값 하나여야 합니다 (지금 " + objects.size() + "개). 하나만 적으세요.");
                return null;
            }
            Node value = objects.get(0);
            boolean text = value.isLiteral() && (XSD + "string").equals(value.getLiteralDatatypeURI());
            if (!text || value.getLiteralLexicalForm().isEmpty() || value.getLiteralLexicalForm().indexOf('\u0000') >= 0) {
                problem(file, item, shown + " 은(는) 비어 있지 않은 글자 값이어야 합니다 (지금 " + show(value)
                        + "). 따옴표로 감싼 글자를 적으세요. NUL 글자는 쓸 수 없습니다.");
                return null;
            }
            return value.getLiteralLexicalForm();
        }

        /**
         * 이름표를 읽는다 (D2 AD-131). 언어 표시 없는 것이 꼭 하나 있어야 하고 그것을 돌려준다.
         * 언어 표시가 붙은 것은 언어마다 하나씩 더 적을 수 있고, 언어별 이름표로 모은다. 틀리면 문제를 남기고 null.
         */
        String label(String file, String item, String name) {
            // 문장을 읽는 차례는 정해져 있지 않다. 처음 만난 틀린 값에서 멈추면 알리는 문제가 그 차례에 달라지므로,
            // 값을 모두 보고 틀린 것을 모두 알린다.
            String base = null;
            int bases = 0;
            boolean wrong = false;
            TreeMap<String, String> byLanguage = new TreeMap<>(PackOrder.STRINGS);
            TreeSet<String> repeated = new TreeSet<>(PackOrder.STRINGS);
            for (Node value : objects(LABEL)) {
                boolean plain = value.isLiteral() && (XSD + "string").equals(value.getLiteralDatatypeURI());
                boolean tagged = value.isLiteral() && LANG_STRING.equals(value.getLiteralDatatypeURI());
                if (!(plain || tagged) || value.getLiteralLexicalForm().isEmpty()
                        || value.getLiteralLexicalForm().indexOf('\u0000') >= 0) {
                    problem(file, item, "rdfs:label 은(는) 비어 있지 않은 글자 값이어야 합니다 (지금 " + show(value)
                            + (tagged ? "@" + value.getLiteralLanguage().toLowerCase(Locale.ROOT) : "")
                            + "). 따옴표로 감싼 글자를 적으세요. NUL 글자는 쓸 수 없습니다.");
                    wrong = true;
                    continue;
                }
                if (plain) {
                    bases++;
                    base = value.getLiteralLexicalForm();
                    continue;
                }
                String language = value.getLiteralLanguage().toLowerCase(Locale.ROOT);
                if (!language.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
                    problem(file, item, "rdfs:label 의 언어 표시 @" + language + " 을(를) 읽을 수 없습니다. ko, en, ko-kr 처럼 적으세요.");
                    wrong = true;
                }
                else if (byLanguage.put(language, value.getLiteralLexicalForm()) != null) {
                    repeated.add(language);
                }
            }
            for (String language : repeated) {
                problem(file, item, "rdfs:label 에 언어 @" + language + " 의 이름표가 둘 이상입니다. 언어마다 하나만 적으세요.");
                wrong = true;
            }
            // 틀린 값이 있으면 그것부터 고치게 한다. 기본 이름표의 수는 값이 모두 맞을 때 본다.
            if (!wrong && bases != 1) {
                problem(file, item, "rdfs:label 은(는) 언어 표시 없는 것을 꼭 하나 적습니다 (지금 " + bases
                        + "개). 기본 이름표를 하나 적고, 다른 언어의 이름표에는 언어 표시(@en)를 붙이세요.");
                wrong = true;
            }
            if (wrong) {
                return null;
            }
            byLanguage.forEach((language, text) -> localizedLabels.add(new LocalizedLabel(name, language, text)));
            return base;
        }

        int maxDepth(String item) {
            List<Node> objects = objects(VOCAB + "defaultMaxDepth");
            if (objects.size() == 1 && objects.get(0).isLiteral()
                    && (XSD + "integer").equals(objects.get(0).getLiteralDatatypeURI())) {
                try {
                    int value = Integer.parseInt(objects.get(0).getLiteralLexicalForm());
                    if (value > MAX_DEPTH_LIMIT) {
                        // 잘못 적은 값 하나로 영향 범위의 계산과 저장이 오래 멈추지 않게 한다 (D2 AD-333).
                        problem(PACK_FILE, item, "tabnit:defaultMaxDepth " + value + " 은(는) 너무 큽니다. " + MAX_DEPTH_LIMIT
                                + " 까지 적을 수 있습니다. 영향을 몇 단계까지 따라갈지 다시 정해 적으세요.");
                        return 0;
                    }
                    if (value >= 1) {
                        return value;
                    }
                }
                catch (NumberFormatException e) {
                    // int 에 담기지 않는 큰 수도 상한을 넘은 것이다. 상한을 알린다.
                    String lexical = objects.get(0).getLiteralLexicalForm();
                    if (lexical.matches("\\+?[0-9]+")) {
                        problem(PACK_FILE, item, "tabnit:defaultMaxDepth 가 너무 큽니다. " + MAX_DEPTH_LIMIT
                                + " 까지 적을 수 있습니다. 영향을 몇 단계까지 따라갈지 다시 정해 적으세요.");
                        return 0;
                    }
                    // 그 밖은 아래에서 문제로 알린다.
                }
            }
            problem(PACK_FILE, item, "tabnit:defaultMaxDepth 는 1 이상 " + MAX_DEPTH_LIMIT + " 이하의 정수 하나여야 합니다. 예: tabnit:defaultMaxDepth 3");
            return 0;
        }

        /** 이 Pack 의 정의를 가리키는 값들의 이름. 글자 코드 순. 이 Pack 의 주소가 아니면 문제를 남긴다. */
        List<String> names(String predicate, String shown, String item) {
            TreeSet<String> names = new TreeSet<>(PackOrder.STRINGS);
            for (Node object : objects(predicate)) {
                String name = object.isURI() ? localName(object.getURI()) : null;
                if (name == null) {
                    problem(ONTOLOGY_FILE, item, shown + " 의 값 " + show(object) + " 이(가) 이 Pack 의 정의가 아닙니다. <" + prefix
                            + "이름> 의 꼴로 적으세요.");
                }
                else {
                    if (!Normalizer.isNormalized(name, Normalizer.Form.NFC)) {
                        problem(ONTOLOGY_FILE, item, shown + " 의 값 " + name
                                + " 이(가) 조합형(유니코드 NFC)이 아닙니다. 눈에 같아도 다른 이름입니다. 조합형으로 다시 적으세요.");
                    }
                    names.add(name);
                }
            }
            return List.copyOf(names);
        }

        /** 이 Pack 의 정의를 가리키는 값이 꼭 하나여야 하는 항목. 아니면 문제를 남기고 null. */
        String oneName(String predicate, String shown, String item) {
            List<Node> objects = objects(predicate);
            if (objects.size() != 1) {
                problem(ONTOLOGY_FILE, item, shown + " 은(는) 하나만 적습니다 (지금 " + objects.size() + "개). 여러 유형은 공통 상위 유형으로 묶으세요.");
                return null;
            }
            List<String> names = names(predicate, shown, item);
            return names.isEmpty() ? null : names.get(0);
        }

        /** 정해진 값 가운데 하나여야 하는 항목. 아니면 문제를 남기고 null. */
        <T> T oneOf(String predicate, String shown, Map<String, T> allowed, String allowedShown, String item) {
            List<Node> objects = objects(predicate);
            if (objects.size() != 1) {
                problem(ONTOLOGY_FILE, item, shown + " 은(는) 반드시 하나 적습니다 (지금 " + objects.size() + "개). " + allowedShown
                        + " 가운데 하나를 적으세요.");
                return null;
            }
            Node object = objects.get(0);
            T value = object.isURI() ? allowed.get(object.getURI()) : null;
            if (value == null) {
                problem(ONTOLOGY_FILE, item, shown + " 의 값 " + show(object)
                        + " 을(를) 모릅니다. " + allowedShown + " 가운데 하나를 적으세요.");
            }
            return value;
        }

        /** 이 주어에 쓰인 술어. 줄인 표기로, 글자 코드 순. */
        List<String> predicates() {
            TreeSet<String> predicates = new TreeSet<>(PackOrder.STRINGS);
            for (Triple triple : triples) {
                predicates.add(show(triple.getPredicate()));
            }
            return List.copyOf(predicates);
        }

        /** D6 에 없는 문장을 문제로 알린다. */
        void rejectOthers(String file, String item, String... allowedPredicates) {
            TreeSet<String> allowed = new TreeSet<>(PackOrder.STRINGS);
            allowed.addAll(List.of(allowedPredicates));
            TreeSet<String> others = new TreeSet<>(PackOrder.STRINGS);
            for (Triple triple : triples) {
                String predicate = triple.getPredicate().isURI() ? triple.getPredicate().getURI()
                        : triple.getPredicate().toString();
                if (!allowed.contains(predicate)) {
                    others.add(predicate);
                }
            }
            for (String predicate : others) {
                problem(file, item, "D6 에 없는 문장입니다: " + shorten(predicate) + ". 엔진은 이 문장을 읽지 않습니다. 빼세요.");
            }
        }
    }
}
