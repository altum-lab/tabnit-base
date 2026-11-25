package com.altumlab.tabnit.pack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
 * Pack 폴더의 매핑 정의(mappings/*.ttl)를 읽어 내부 모델로 만들고, 온톨로지와 맞는지 확인한다 (D6 7절, D2 AD-140).
 *
 * <p>읽는 것은 D6 에 적힌 낱말뿐이다. 그 밖의 문장은 매핑 안쪽에 있어도 문제로 알린다.
 * 매핑은 안쪽 정의(이름 없는 정의)를 겹쳐 적는다. 그 라벨은 읽을 때마다 달라지므로 문구와 차례에 쓰지 않는다.
 */
final class MappingReader {

    static final String FOLDER = "mappings";

    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";

    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private static final String V = PackReader.VOCAB;

    private static final Comparator<MappedEntityRef> REFS = Comparator
        .comparing(MappedEntityRef::entityType, PackOrder.STRINGS)
        .thenComparing(MappedEntityRef::keyColumn, PackOrder.STRINGS);

    private static final Comparator<MappedRelation> RELATIONS = Comparator
        .comparing(MappedRelation::relationType, PackOrder.STRINGS)
        .thenComparing(MappedRelation::source, REFS)
        .thenComparing(MappedRelation::target, REFS);

    private final Path directory;

    private final String prefix;

    private final Map<String, EntityTypeDefinition> entityTypes;

    private final Map<String, RelationTypeDefinition> relationTypes;

    private final Map<String, AttributeDefinition> attributes;

    private final List<PackProblem> problems;

    // 파일 하나를 읽는 동안의 상태.
    private String file;

    private Map<Node, List<Triple>> bySubject;

    /** 읽은 문장. 들어 있는지만 본다. 순회하지 않는다. */
    private Set<Triple> consumed;

    /** 이미 연 안쪽 정의. 들어 있는지만 본다. */
    private Set<Node> opened;

    private MappingReader(Path directory, String prefix, Map<String, EntityTypeDefinition> entityTypes,
            Map<String, RelationTypeDefinition> relationTypes, Map<String, AttributeDefinition> attributes,
            List<PackProblem> problems) {
        this.directory = directory;
        this.prefix = prefix;
        this.entityTypes = entityTypes;
        this.relationTypes = relationTypes;
        this.attributes = attributes;
        this.problems = problems;
    }

    /** 매핑을 모두 읽는다. 문제는 problems 에 더한다. 돌려주는 목록은 입력 자료의 식별자 순이다. */
    static List<MappingDefinition> read(Path directory, String prefix, Map<String, EntityTypeDefinition> entityTypes,
            Map<String, RelationTypeDefinition> relationTypes, Map<String, AttributeDefinition> attributes,
            List<PackProblem> problems) {
        return new MappingReader(directory, prefix, entityTypes, relationTypes, attributes, problems).readAll();
    }

    private List<MappingDefinition> readAll() {
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
            // 목록을 여는 때의 실패와, 읽어 나가는 도중의 실패를 모두 문제로 알린다.
            problems.add(new PackProblem(FOLDER + "/", "", "매핑 폴더를 읽지 못했습니다: " + e.getMessage()
                    + ". 폴더의 권한을 확인하세요."));
            return List.of();
        }
        TreeMap<String, MappingDefinition> bySource = new TreeMap<>(PackOrder.STRINGS);
        for (String name : names) {
            for (MappingDefinition mapping : readFile(name)) {
                bySource.putIfAbsent(mapping.inputSourceId(), mapping);
            }
        }
        // 입력 자료 하나에 매핑 하나다 (D2 AD-41).
        seenSources.forEach((source, count) -> {
            if (count > 1) {
                problems.add(new PackProblem(FOLDER + "/" + sourceFiles.get(source).first(), source, "입력 자료의 식별자 " + source + " 이(가) 매핑 " + count
                        + "개에 적혀 있습니다 (" + String.join(", ", sourceFiles.get(source))
                        + "). 입력 자료 하나에 매핑은 하나입니다. 식별자를 다르게 적으세요."));
            }
        });
        // 개체 유형의 속성 하나는 매핑 하나만 넣는다 (D2 AD-137).
        TreeMap<String, TreeSet<String>> writers = new TreeMap<>(PackOrder.STRINGS);
        TreeMap<String, String[]> what = new TreeMap<>(PackOrder.STRINGS);
        for (MappingDefinition mapping : bySource.values()) {
            for (MappedEntity entity : mapping.entities()) {
                for (MappedAttribute attribute : entity.attributes()) {
                    String key = entity.entityType().length() + ":" + entity.entityType() + attribute.attributeName();
                    writers.computeIfAbsent(key, k -> new TreeSet<>(PackOrder.STRINGS)).add(mapping.inputSourceId());
                    what.put(key, new String[] { entity.entityType(), attribute.attributeName() });
                }
            }
        }
        writers.forEach((key, sources) -> {
            if (sources.size() > 1) {
                String[] pair = what.get(key);
                problems.add(new PackProblem(FOLDER + "/" + sourceFiles.get(sources.first()).first(), pair[1],
                        "개체 유형 " + pair[0] + " 의 속성 " + pair[1]
                        + " 을(를) 입력 자료 여럿이 넣습니다 (" + String.join(", ", sources)
                        + "). 속성 하나는 입력 자료 하나만 넣습니다. 한 자료만 넣고 나머지는 키로 가리키기만 하세요."));
            }
        });
        return List.copyOf(bySource.values());
    }

    /** 입력 자료의 식별자마다 그것을 적은 매핑의 수와 파일. 문제가 있는 매핑도 센다. */
    private final TreeMap<String, Integer> seenSources = new TreeMap<>(PackOrder.STRINGS);

    private final TreeMap<String, TreeSet<String>> sourceFiles = new TreeMap<>(PackOrder.STRINGS);

    private List<MappingDefinition> readFile(String name) {
        file = FOLDER + "/" + name;
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
        TreeMap<String, Node> mappingSubjects = new TreeMap<>(PackOrder.STRINGS);
        graph.find().forEachRemaining(triple -> {
            all.add(triple);
            bySubject.computeIfAbsent(triple.getSubject(), k -> new ArrayList<>()).add(triple);
            if (triple.getSubject().isURI() && isUri(triple.getPredicate(), RDF + "type")
                    && isUri(triple.getObject(), V + "Mapping")) {
                mappingSubjects.put(triple.getSubject().getURI(), triple.getSubject());
            }
        });
        // 이름 없이 적은 매핑.
        TreeSet<String> unnamed = new TreeSet<>(PackOrder.STRINGS);
        for (Triple triple : all) {
            if (triple.getSubject().isBlank() && isUri(triple.getPredicate(), RDF + "type")
                    && isUri(triple.getObject(), V + "Mapping")) {
                discard(triple.getSubject());
                unnamed.add("매핑에는 이름(주소)을 붙여 적습니다. <" + prefix + "이름> a tabnit:Mapping 의 꼴로 적으세요.");
            }
        }
        unnamed.forEach(message -> problem(PackReader.UNNAMED, message));
        // 입력 자료 하나에 매핑 정의 파일 하나다 (D6 2절).
        if (mappingSubjects.size() != 1 && unnamed.isEmpty()) {
            problem("", "이 파일에 tabnit:Mapping 이 " + mappingSubjects.size()
                    + "개입니다. 파일 하나에 매핑 하나를 적으세요.");
        }
        List<MappingDefinition> mappings = new ArrayList<>();
        for (Node subject : mappingSubjects.values()) {
            MappingDefinition mapping = readMapping(subject);
            if (mapping != null) {
                mappings.add(mapping);
            }
        }
        // 어느 매핑에도 속하지 않은 문장. 문제는 끝에서 정렬하고 같은 것은 한 번만 남으므로 읽는 차례와 무관하다.
        for (Triple triple : all) {
            if (!consumed.contains(triple)) {
                Node subject = triple.getSubject();
                String item = subject.isURI() ? itemOf(subject.getURI()) : PackReader.UNNAMED;
                problem(item, "D6 에 없는 문장입니다: " + PackReader.show(triple.getPredicate())
                        + ". 이 파일에는 tabnit:Mapping 과 그 안쪽의 항목만 적습니다. 빼세요.");
            }
        }
        return mappings;
    }

    /** 매핑 하나를 읽는다. 문제가 하나라도 있으면 null. */
    private MappingDefinition readMapping(Node subject) {
        int before = problems.size();
        String item = itemOf(subject.getURI());
        if (!subject.getURI().startsWith(prefix) || subject.getURI().length() == prefix.length()) {
            problem(item, "이 Pack 의 주소로 시작하지 않는 매핑입니다. <" + prefix + "이름> 의 꼴로 적으세요.");
        }
        Block top = new Block(subject);
        for (Node type : top.take(RDF + "type")) {
            if (!isUri(type, V + "Mapping")) {
                problem(item, "D6 에 없는 문장입니다: a " + PackReader.show(type) + ". 빼세요.");
            }
        }
        String source = text(top, V + "inputSourceId", "tabnit:inputSourceId", item, "", true);
        if (source != null && !source.matches("[a-z][a-z0-9-]*")) {
            // 입력 자료의 식별자는 Pack 식별자와 같은 규칙이다 (D2 AD-145).
            problem(item, "tabnit:inputSourceId \"" + source
                    + "\" 을(를) 쓸 수 없습니다. 영어 소문자로 시작하고 영어 소문자, 숫자, 붙임표(-)만 쓰세요.");
            source = null;
        }
        if (source != null) {
            seenSources.merge(source, 1, Integer::sum);
            sourceFiles.computeIfAbsent(source, k -> new TreeSet<>(PackOrder.STRINGS)).add(file.substring(FOLDER.length() + 1));
        }

        MappingFileFormat format = null;
        List<Node> formats = top.take(V + "fileFormat");
        if (formats.size() != 1) {
            problem(item, "tabnit:fileFormat 은(는) 반드시 하나 적습니다 (지금 " + formats.size()
                    + "개). tabnit:Csv 또는 tabnit:Xlsx 를 적으세요.");
        }
        else if (isUri(formats.get(0), V + "Csv")) {
            format = MappingFileFormat.CSV;
        }
        else if (isUri(formats.get(0), V + "Xlsx")) {
            format = MappingFileFormat.XLSX;
        }
        else {
            problem(item, "tabnit:fileFormat 의 값 " + PackReader.show(formats.get(0))
                    + " 을(를) 모릅니다. tabnit:Csv 또는 tabnit:Xlsx 를 적으세요.");
        }
        String sheet = text(top, V + "sheetName", "tabnit:sheetName", item, "", false);
        if (format == MappingFileFormat.XLSX && sheet == null && top.count(V + "sheetName") == 0) {
            problem(item, "엑셀 매핑에는 tabnit:sheetName 으로 읽을 시트를 적습니다. 시트 이름을 적으세요.");
        }
        else if (format == MappingFileFormat.CSV && top.count(V + "sheetName") > 0) {
            problem(item, "tabnit:sheetName 은(는) 엑셀 매핑에만 적습니다. CSV 매핑에서는 빼세요.");
        }

        // 날짜의 기본 모양 (D2 AD-143).
        String defaultDatePattern = text(top, V + "defaultDatePattern", "tabnit:defaultDatePattern", item, "", false);
        if (defaultDatePattern != null) {
            String fault = datePatternFault(defaultDatePattern);
            if (fault != null) {
                problem(item, "tabnit:defaultDatePattern \"" + defaultDatePattern + "\" 을(를) 쓸 수 없습니다: " + fault);
            }
        }
        List<String> required = columns(top, V + "requiredColumns", "tabnit:requiredColumns", item, false);
        List<String> itemKey = columns(top, V + "itemKeyColumns", "tabnit:itemKeyColumns", item, true);

        // 행이 만드는 개체.
        boolean blocksClean = true;
        TreeMap<MappedEntityRef, MappedEntity> entities = new TreeMap<>(REFS);
        TreeSet<MappedEntityRef> made = new TreeSet<>(REFS);
        for (Node node : top.take(V + "entity")) {
            int mark = problems.size();
            MappedEntity entity = entity(node, item);
            if (entity == null || problems.size() > mark) {
                blocksClean = false;
                continue;
            }
            MappedEntityRef ref = new MappedEntityRef(entity.entityType(), entity.keyColumn());
            if (entities.put(ref, entity) != null) {
                problem(item, "개체 (" + ref.entityType() + ", " + ref.keyColumn()
                        + ") 을(를) tabnit:entity 로 두 번 적었습니다. 하나로 합쳐 적으세요.");
                blocksClean = false;
            }
            made.add(ref);
        }
        // 행이 만드는 관계.
        TreeSet<MappedRelation> relations = new TreeSet<>(RELATIONS);
        for (Node node : top.take(V + "relation")) {
            int mark = problems.size();
            MappedRelation relation = relation(node, item);
            if (relation == null || problems.size() > mark) {
                blocksClean = false;
                continue;
            }
            if (!relations.add(relation)) {
                problem(item, "관계 " + relation.relationType() + " (" + relation.source().keyColumn() + " → "
                        + relation.target().keyColumn() + ") 을(를) 두 번 적었습니다. 하나만 적으세요.");
                blocksClean = false;
            }
            made.add(relation.source());
            made.add(relation.target());
        }
        // 행의 주 개체 (D2 AD-120).
        MappedEntityRef primary = null;
        List<Node> primaries = top.take(V + "primaryEntity");
        if (primaries.size() != 1) {
            problem(item, "tabnit:primaryEntity 은(는) 반드시 하나 적습니다 (지금 " + primaries.size()
                    + "개). 이 행이 나타내는 개체 하나를 유형과 키 열로 적으세요.");
            // 여럿 적은 것의 안쪽 문장은 위의 문제 하나로 알렸다. 따로 알리지 않는다.
            primaries.forEach(this::discard);
        }
        else {
            primary = ref(primaries.get(0), "tabnit:primaryEntity", item);
            if (primary != null && blocksClean && !made.contains(primary)) {
                problem(item, "tabnit:primaryEntity (" + primary.entityType() + ", " + primary.keyColumn()
                        + ") 은(는) 이 행이 만드는 개체가 아닙니다. tabnit:entity 나 관계의 끝에 적힌 개체 가운데 하나를 적으세요.");
            }
        }
        // 화면에 보일 이름표. 적지 않아도 된다 (D2 AD-384).
        String label = text(top, RDFS_LABEL, "rdfs:label", item, "", false);
        if (label != null && label.isBlank()) {
            // 빈칸만 든 이름표는 화면에 빈 자리로 보인다.
            problem(item, "rdfs:label 은(는) 빈칸만으로 적을 수 없습니다. 화면에 보일 이름을 적거나 이 줄을 빼세요.");
        }
        top.rejectOthers(item);
        if (problems.size() > before) {
            return null;
        }
        return new MappingDefinition(source, format, sheet, defaultDatePattern, required, itemKey, primary, List.copyOf(entities.values()),
                List.copyOf(relations), label);
    }

    private MappedEntity entity(Node node, String item) {
        Block block = block(node, "tabnit:entity", item);
        if (block == null) {
            return null;
        }
        MappedEntityRef ref = refOf(block, "tabnit:entity", item);
        TreeMap<String, MappedAttribute> found = new TreeMap<>(PackOrder.STRINGS);
        for (Node attributeNode : block.take(V + "attribute")) {
            MappedAttribute attribute = attribute(attributeNode, ref, item);
            if (attribute != null && found.put(attribute.attributeName(), attribute) != null) {
                problem(item, "속성 " + attribute.attributeName() + " 을(를) 한 개체에 두 번 적었습니다. 하나만 적으세요.");
            }
        }
        block.rejectOthers(item);
        return ref == null ? null : new MappedEntity(ref.entityType(), ref.keyColumn(), List.copyOf(found.values()));
    }

    private MappedAttribute attribute(Node node, MappedEntityRef owner, String item) {
        Block block = block(node, "tabnit:attribute", item);
        if (block == null) {
            return null;
        }
        String where = "tabnit:attribute";
        String name = name(block, V + "property", "tabnit:property", item, where);
        String column = text(block, V + "column", "tabnit:column", item, where, true);
        String from = text(block, V + "validFromColumn", "tabnit:validFromColumn", item, where, false);
        String to = text(block, V + "validToColumn", "tabnit:validToColumn", item, where, false);
        String datePattern = text(block, V + "datePattern", "tabnit:datePattern", item, where, false);
        String grouping = text(block, V + "groupingSeparator", "tabnit:groupingSeparator", item, where, false);
        String trueValue = text(block, V + "trueValue", "tabnit:trueValue", item, where, false);
        String falseValue = text(block, V + "falseValue", "tabnit:falseValue", item, where, false);
        block.rejectOthers(item);
        if (name == null) {
            return null;
        }
        AttributeDefinition definition = attributes.get(name);
        if (definition == null) {
            problem(item, "속성 " + name + " 이(가) 온톨로지에 정의되지 않았습니다. 그 속성을 정의하거나 이름을 고치세요.");
            return null;
        }
        // 속성은 그 개체 유형이나 그 상위 유형의 것이어야 한다.
        if (owner != null && definition.entityType() != null
                && !PackReader.isSameOrAncestor(definition.entityType(), owner.entityType(), entityTypes)) {
            problem(item, "속성 " + name + " 은(는) 유형 " + definition.entityType() + " 의 속성입니다. 유형 "
                    + owner.entityType() + " 이나 그 상위 유형의 속성을 적으세요.");
        }
        // 값을 읽는 법은 속성의 형식과 맞아야 한다 (D2 AD-94).
        ValueFormat format = definition.valueFormat();
        if (datePattern != null) {
            if (format != ValueFormat.DATE) {
                problem(item, "tabnit:datePattern 은(는) 날짜 속성에만 적습니다. 속성 " + name + " 은(는) 날짜가 아닙니다. 빼세요.");
            }
            else {
                String fault = datePatternFault(datePattern);
                if (fault != null) {
                    problem(item, "tabnit:datePattern \"" + datePattern + "\" 을(를) 쓸 수 없습니다 (속성 " + name + "): " + fault);
                }
            }
        }
        if (grouping != null && format != ValueFormat.INTEGER && format != ValueFormat.DECIMAL) {
            problem(item, "tabnit:groupingSeparator 은(는) 수 속성에만 적습니다. 속성 " + name + " 은(는) 수가 아닙니다. 빼세요.");
        }
        else if (grouping != null) {
            String fault = groupingSeparatorFault(grouping, format);
            if (fault != null) {
                problem(item, "tabnit:groupingSeparator \"" + grouping + "\" 을(를) 쓸 수 없습니다 (속성 " + name + "): " + fault);
            }
        }
        if ((trueValue == null) != (falseValue == null)) {
            problem(item, "tabnit:trueValue 와 tabnit:falseValue 는 함께 적습니다 (속성 " + name + "). 둘 다 적으세요.");
        }
        else if (trueValue != null && format != ValueFormat.BOOLEAN) {
            problem(item, "tabnit:trueValue 와 tabnit:falseValue 는 참·거짓 속성에만 적습니다. 속성 " + name
                    + " 은(는) 참·거짓이 아닙니다. 빼세요.");
        }
        else if (trueValue != null && trueValue.equals(falseValue)) {
            problem(item, "tabnit:trueValue 와 tabnit:falseValue 가 같은 글자입니다 (속성 " + name + "). 다르게 적으세요.");
        }
        if (column == null) {
            return null;
        }
        return new MappedAttribute(name, column, from, to, datePattern, grouping, trueValue, falseValue);
    }

    private MappedRelation relation(Node node, String item) {
        Block block = block(node, "tabnit:relation", item);
        if (block == null) {
            return null;
        }
        String where = "tabnit:relation";
        String name = name(block, V + "relationType", "tabnit:relationType", item, where);
        MappedEntityRef source = end(block, V + "sourceEntity", "tabnit:sourceEntity", item);
        MappedEntityRef target = end(block, V + "targetEntity", "tabnit:targetEntity", item);
        String from = text(block, V + "validFromColumn", "tabnit:validFromColumn", item, where, false);
        String to = text(block, V + "validToColumn", "tabnit:validToColumn", item, where, false);
        block.rejectOthers(item);
        if (name == null) {
            return null;
        }
        RelationTypeDefinition definition = relationTypes.get(name);
        if (definition == null) {
            problem(item, "관계 유형 " + name + " 이(가) 온톨로지에 정의되지 않았습니다. 그 관계 유형을 정의하거나 이름을 고치세요.");
            return null;
        }
        // 끝의 유형은 관계 유형의 출발·도착 유형이거나 그 하위 유형이어야 한다 (D2 AD-88, AD-91).
        if (source != null && definition.sourceType() != null
                && !PackReader.isSameOrAncestor(definition.sourceType(), source.entityType(), entityTypes)) {
            problem(item, "관계 유형 " + name + " 의 출발 유형은 " + definition.sourceType() + " 인데 "
                    + source.entityType() + " 을(를) 적었습니다. 그 유형이나 하위 유형을 적으세요.");
        }
        if (target != null && definition.targetType() != null
                && !PackReader.isSameOrAncestor(definition.targetType(), target.entityType(), entityTypes)) {
            problem(item, "관계 유형 " + name + " 의 도착 유형은 " + definition.targetType() + " 인데 "
                    + target.entityType() + " 을(를) 적었습니다. 그 유형이나 하위 유형을 적으세요.");
        }
        if (source == null || target == null) {
            return null;
        }
        return new MappedRelation(name, source, target, from, to);
    }

    private MappedEntityRef end(Block block, String predicate, String shown, String item) {
        List<Node> nodes = block.take(predicate);
        if (nodes.size() != 1) {
            problem(item, shown + " 은(는) 반드시 하나 적습니다 (지금 " + nodes.size() + "개). 개체를 유형과 키 열로 적으세요.");
            nodes.forEach(this::discard);
            return null;
        }
        return ref(nodes.get(0), shown, item);
    }

    /** 유형과 키 열로 개체를 가리키는 안쪽 정의를 읽는다. */
    private MappedEntityRef ref(Node node, String shown, String item) {
        Block block = block(node, shown, item);
        if (block == null) {
            return null;
        }
        MappedEntityRef ref = refOf(block, shown, item);
        block.rejectOthers(item);
        return ref;
    }

    private MappedEntityRef refOf(Block block, String where, String item) {
        String type = name(block, V + "entityType", "tabnit:entityType", item, where);
        String key = text(block, V + "keyColumn", "tabnit:keyColumn", item, where, true);
        if (type != null && !entityTypes.containsKey(type)) {
            problem(item, "개체 유형 " + type + " 이(가) 온톨로지에 정의되지 않았습니다 (" + where
                    + "). 그 유형을 정의하거나 이름을 고치세요.");
            return null;
        }
        return type == null || key == null ? null : new MappedEntityRef(type, key);
    }

    /** 안쪽 정의를 연다. 글자 값이거나 내용이 없으면 문제를 남기고 null. */
    private Block block(Node node, String shown, String item) {
        if (!node.isBlank() || !bySubject.containsKey(node)) {
            problem(item, shown + " 은(는) [ … ] 로 감싼 안쪽 정의로 적습니다 (지금 " + PackReader.show(node) + ").");
            discard(node);
            return null;
        }
        if (!opened.add(node)) {
            // 어느 쪽을 먼저 읽는지에 결과가 달라지지 않도록, 함께 가리키는 것 자체를 받지 않는다.
            problem(item, "한 안쪽 정의를 두 곳에서 가리킵니다. 안쪽 정의는 쓰는 곳마다 [ … ] 로 따로 적으세요.");
            return null;
        }
        return new Block(node);
    }

    /** 이 Pack 의 정의를 가리키는 값 하나를 읽어 이름을 돌려준다. */
    private String name(Block block, String predicate, String shown, String item, String where) {
        List<Node> nodes = block.take(predicate);
        if (nodes.size() != 1) {
            problem(item, shown + " 은(는) 반드시 하나 적습니다 (" + where + ", 지금 " + nodes.size() + "개).");
            return null;
        }
        Node node = nodes.get(0);
        if (!node.isURI() || !node.getURI().startsWith(prefix) || node.getURI().length() == prefix.length()) {
            problem(item, shown + " 의 값 " + PackReader.show(node) + " 이(가) 이 Pack 의 정의가 아닙니다 (" + where + "). <"
                    + prefix + "이름> 의 꼴로 적으세요.");
            return null;
        }
        return node.getURI().substring(prefix.length());
    }

    /** 비어 있지 않은 글자 값 하나를 읽는다. 반드시 있어야 하는데 없거나, 여럿이거나, 글자가 아니면 문제를 남기고 null. */
    private String text(Block block, String predicate, String shown, String item, String where, boolean required) {
        List<Node> nodes = block.take(predicate);
        String at = where.isEmpty() ? "" : where + " 의 ";
        if (nodes.isEmpty()) {
            if (required) {
                problem(item, at + shown + " 이(가) 없습니다. 따옴표로 감싼 글자 하나를 적으세요.");
            }
            return null;
        }
        if (nodes.size() > 1) {
            problem(item, at + shown + " 은(는) 하나만 적습니다 (지금 " + nodes.size() + "개).");
            return null;
        }
        Node node = nodes.get(0);
        if (node.isLiteral() && !node.getLiteralLanguage().isEmpty()) {
            // 까닭을 따로 알린다: 글자는 맞게 적었는데 언어 표시 때문에 받지 않는 것이다.
            problem(item, at + shown + " 에는 언어 표시(@" + node.getLiteralLanguage()
                    + ")를 붙이지 않습니다. 따옴표로 감싼 글자만 적으세요.");
            return null;
        }
        if (!isText(node)) {
            problem(item, at + shown + " 은(는) 비어 있지 않은 글자 값이어야 합니다 (지금 " + PackReader.show(node)
                    + "). 따옴표로 감싼 글자를 적으세요. NUL 글자는 쓸 수 없습니다.");
            return null;
        }
        return node.getLiteralLexicalForm();
    }

    /** 차례가 있는 열 목록을 읽는다. Turtle 의 목록 ( … ) 으로 적어야 한다 (D2 AD-139). */
    private List<String> columns(Block block, String predicate, String shown, String item, boolean required) {
        List<Node> nodes = block.take(predicate);
        if (nodes.isEmpty() && !required) {
            return List.of();
        }
        if (nodes.size() != 1 || nodes.get(0).isLiteral()) {
            problem(item, shown + " 은(는) 목록 하나로 적습니다. ( \"열1\" \"열2\" ) 의 꼴로, 차례대로 적으세요.");
            nodes.forEach(this::discard);
            return List.of();
        }
        Node head = nodes.get(0);
        List<String> columns = new ArrayList<>();
        Set<Node> visited = new HashSet<>();
        Node cell = nodes.get(0);
        while (!isUri(cell, RDF + "nil")) {
            if (!visited.add(cell) || !bySubject.containsKey(cell)) {
                problem(item, shown + " 의 목록을 읽을 수 없습니다. ( \"열1\" \"열2\" ) 의 꼴로 적으세요.");
                discard(head);
                return List.of();
            }
            Block link = new Block(cell);
            List<Node> first = link.take(RDF + "first");
            List<Node> rest = link.take(RDF + "rest");
            link.rejectOthers(item);
            if (first.size() != 1 || rest.size() != 1 || !isText(first.get(0))) {
                problem(item, shown + " 의 목록에는 비어 있지 않은 글자 값만 적습니다 (NUL 글자도 안 됩니다). ( \"열1\" \"열2\" ) 의 꼴로 적으세요.");
                discard(head);
                return List.of();
            }
            columns.add(first.get(0).getLiteralLexicalForm());
            cell = rest.get(0);
        }
        if (required && columns.isEmpty()) {
            problem(item, shown + " 이(가) 비어 있습니다. 열을 하나 이상 적으세요.");
        }
        else if (new TreeSet<>(columns).size() != columns.size()) {
            problem(item, shown + " 에 같은 열이 두 번 적혀 있습니다. 한 번만 적으세요.");
        }
        return columns;
    }

    /** 비어 있지 않은 글자 값인가. NUL 글자가 든 값은 저장소가 받지 않으므로 여기서 거른다. */
    private static boolean isText(Node node) {
        return node.isLiteral() && (PackReader.XSD + "string").equals(node.getLiteralDatatypeURI())
                && !node.getLiteralLexicalForm().isEmpty() && node.getLiteralLexicalForm().indexOf('\u0000') < 0;
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
            for (Triple triple : bySubject.getOrDefault(node, List.of())) {
                consumed.add(triple);
                if (triple.getObject().isBlank()) {
                    open.add(triple.getObject());
                }
            }
        }
    }

    /**
     * 자릿수 구분 기호가 수를 틀리게 읽게 만드는지 본다 (D2 AD-178). 값에서 이 글자를 뗀 뒤 수로 읽기 때문이다.
     *
     * @return 쓸 수 없는 까닭. 쓸 수 있으면 null
     */
    static String groupingSeparatorFault(String separator, ValueFormat format) {
        if (separator.codePointCount(0, separator.length()) != 1) {
            return "글자 하나만 적으세요.";
        }
        char c = separator.charAt(0);
        if (c >= '0' && c <= '9') {
            return "숫자는 구분 기호가 될 수 없습니다. 값의 숫자가 사라집니다.";
        }
        // 더하기·빼기로 읽히는 글자는 ASCII 가 아니어도 막는다 (U+2212, 전각 부호, 줄표 등). 그런 글자를 떼면 부호가 사라진다.
        int type = Character.getType(separator.codePointAt(0));
        if (c == '+' || c == '-' || type == Character.DASH_PUNCTUATION || type == Character.MATH_SYMBOL) {
            return "부호는 구분 기호가 될 수 없습니다. 값의 부호가 사라집니다.";
        }
        if (c == '.' && format == ValueFormat.DECIMAL) {
            return "소수 속성에서 마침표는 소수점입니다. 다른 글자를 적으세요.";
        }
        return null;
    }

    /**
     * 날짜의 모양이 받을 수 있는 것인지 본다 (D2 AD-144). 받을 수 있으면 null, 아니면 무엇이 틀렸는지.
     *
     * <p>연(y), 월(M), 일(d)을 숫자로 적는 글자와 구분 글자(마침표, 붙임표, 빗금, 빈칸, 작은따옴표로 감싼 글자)만 받는다.
     * 연, 월, 일이 모두 한 번씩 있어야 한다. 그래야 어느 서버에서 읽어도 같은 날짜가 나온다.
     */
    static String datePatternFault(String pattern) {
        String hint = " yyyy.M.d 나 yyyy'년' M'월' d'일' 처럼 적으세요.";
        int years = 0;
        int months = 0;
        int days = 0;
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                int end = pattern.indexOf('\'', i + 1);
                if (end < 0) {
                    return "작은따옴표가 닫히지 않았습니다." + hint;
                }
                i = end + 1;
            }
            else if (c == 'y' || c == 'M' || c == 'd') {
                int end = i;
                while (end < pattern.length() && pattern.charAt(end) == c) {
                    end++;
                }
                int length = end - i;
                if (c == 'y') {
                    years++;
                    if (length != 4) {
                        // 두 자리 연도(yy)는 어느 세기인지 정해져 있지 않아 받지 않는다.
                        return "연은 네 자리 yyyy 로 적습니다." + hint;
                    }
                }
                else if (length > 2) {
                    return "월과 일은 숫자로만 적습니다 (M, MM, d, dd). 달의 이름은 받지 않습니다." + hint;
                }
                else if (c == 'M') {
                    months++;
                }
                else {
                    days++;
                }
                i = end;
            }
            else if (c == '.' || c == '-' || c == '/' || c == ' ') {
                i++;
            }
            else {
                return "쓸 수 없는 글자 '" + c + "' 이(가) 있습니다. 연(y), 월(M), 일(d)과 구분 글자(. - / 빈칸)만 쓰고, 다른 글자는 작은따옴표로 감싸세요."
                        + hint;
            }
        }
        if (years != 1 || months != 1 || days != 1) {
            return "연(y), 월(M), 일(d)이 모두 한 번씩 있어야 합니다." + hint;
        }
        if (pattern.startsWith(" ") || pattern.endsWith(" ")) {
            return "앞이나 뒤에 빈칸이 있습니다. 빈칸을 빼세요." + hint;
        }
        // 이 모양으로 적은 날짜를 다시 읽을 수 있는지 본다. 구분 글자 없이 이어 붙인 모양(yyyyMd)처럼,
        // 글자는 맞아도 어떤 날짜도 읽지 못하는 모양이 있다. 한 자리와 두 자리의 월·일을 모두 본다.
        // 언어 설정은 고정한다. 날짜는 정해 둔 값이고 현재 시각과 무관하다.
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
            for (LocalDate sample : List.of(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 11, 25))) {
                if (!sample.equals(LocalDate.parse(sample.format(formatter), formatter))) {
                    return "이 모양으로 적은 날짜가 다른 날짜로 읽힙니다." + hint;
                }
            }
        }
        catch (IllegalArgumentException | DateTimeParseException e) {
            return "이 모양으로는 날짜를 읽을 수 없습니다. 연, 월, 일 사이에 구분 글자를 넣거나 월과 일을 두 자리(MM, dd)로 적으세요."
                    + hint;
        }
        return null;
    }

    private static boolean isUri(Node node, String uri) {
        return node.isURI() && node.getURI().equals(uri);
    }

    /** 문제의 항목으로 적을 이름. 이 Pack 의 주소면 그 뒤의 이름, 아니면 주소 그대로. */
    private String itemOf(String uri) {
        return uri.startsWith(prefix) && uri.length() > prefix.length() ? uri.substring(prefix.length()) : uri;
    }

    private void problem(String item, String message) {
        problems.add(new PackProblem(file, item, message));
    }

    /** 주어 하나의 문장들. 읽은 문장을 표시해 두고, 끝에 남은 것을 D6 에 없는 문장으로 알린다. */
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

        int count(String predicate) {
            int count = 0;
            for (Triple triple : triples) {
                if (isUri(triple.getPredicate(), predicate)) {
                    count++;
                }
            }
            return count;
        }

        /**
         * 읽지 않은 문장을 D6 에 없는 문장으로 알린다.
         * 문구에 "어디의 문장인지"를 넣지 않는다. 한 안쪽 정의를 두 곳에서 가리키면 먼저 읽은 쪽이 정해져 있지 않아,
         * 넣으면 문구가 읽는 차례에 달라진다.
         */
        void rejectOthers(String item) {
            TreeSet<String> others = new TreeSet<>(PackOrder.STRINGS);
            for (Triple triple : triples) {
                if (consumed.add(triple)) {
                    others.add(PackReader.show(triple.getPredicate()));
                }
            }
            for (String predicate : others) {
                problem(item, "D6 에 없는 문장입니다: " + predicate + ". 엔진은 이 문장을 읽지 않습니다. 빼세요.");
            }
        }
    }
}
