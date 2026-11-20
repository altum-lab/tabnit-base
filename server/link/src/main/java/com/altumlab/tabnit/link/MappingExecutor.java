package com.altumlab.tabnit.link;

import com.altumlab.tabnit.core.AttributeValueRejectedException;
import com.altumlab.tabnit.core.AttributeValueStore;
import com.altumlab.tabnit.core.CoreStorageException;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.NewAttributeValue;
import com.altumlab.tabnit.core.NewRelation;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.RelationRejectedException;
import com.altumlab.tabnit.core.RelationStore;
import com.altumlab.tabnit.core.ValidityPeriod;
import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.MappedAttribute;
import com.altumlab.tabnit.pack.MappedEntity;
import com.altumlab.tabnit.pack.MappedEntityRef;
import com.altumlab.tabnit.pack.MappedRelation;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.MappingFileFormat;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.ValueFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 매핑 실행: 입력 파일의 행을 Pack 의 매핑 정의에 따라 개체, 속성 값, 관계로 바꿔 core 에 넣는다 (보드 C-03, D6 7절).
 *
 * <p>유효기간과 출처를 붙인다. 판 번호와 적용일은 부르는 쪽이 넘긴다 (D2 AD-173). 이 클래스는 현재 시각을 읽지 않는다.
 * 저장은 core 의 공개 API 로만 한다. 넣기만 한다. 앞 판과 비교해 끝내는 일은 하지 않는다 (C-05).
 *
 * <p>실행 하나는 트랜잭션 하나다. 전부 들어가거나 하나도 안 들어간다. 열 구성과 유형은 매핑에서만 온다.
 * 특정 파일의 열 이름을 알지 못한다.
 */
public class MappingExecutor {

    private static final Comparator<IngestNotice> NOTICE_ORDER = Comparator.comparingInt(IngestNotice::rowNo)
        .thenComparing(IngestNotice::kind, LinkOrder.STRINGS)
        .thenComparing(IngestNotice::code, LinkOrder.STRINGS)
        .thenComparing(IngestNotice::subject, LinkOrder.NULLS_FIRST)
        .thenComparing(IngestNotice::detail, LinkOrder.NULLS_FIRST);

    private static final Comparator<ItemOutput> OUTPUT_ORDER = Comparator.comparing(ItemOutput::kind)
        .thenComparing(ItemOutput::objectId, LinkOrder.STRINGS);

    private final RelationStore relationStore;

    private final AttributeValueStore attributeValueStore;

    private final TransactionTemplate transaction;

    public MappingExecutor(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
        this.relationStore = new RelationStore(dataSource);
        this.attributeValueStore = new AttributeValueStore(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * 파일 하나를 매핑에 따라 넣는다.
     *
     * @param pack Pack 의 정의. 매핑과 속성의 형식을 여기서 읽는다
     * @param inputSourceId 입력 자료의 식별자. 이 Pack 의 매핑 가운데 하나여야 한다
     * @param content 파일의 바이트
     * @param editionNo 판 번호. 출처에 들어간다. 1 부터다
     * @param effectiveDate 판의 적용일. 유효기간의 열이 없거나 비어 있으면 이 날이 시작일이다 (D2 AD-42)
     * @param recordedAt 기록한 때. 밖에서 받아 넘긴다
     * @return 받아들인 항목과 알림
     * @throws TableFileRejectedException 파일 수준의 문제로 파일 전체를 거부했을 때
     * @throws MappingExecutionException 저장소가 받지 않았을 때. 이 실행에서 넣은 것은 남지 않는다
     */
    public MappingOutcome execute(PackDefinition pack, String inputSourceId, byte[] content, int editionNo,
            LocalDate effectiveDate, Instant recordedAt) {
        Objects.requireNonNull(pack, "Pack 의 정의는 반드시 있어야 합니다");
        Objects.requireNonNull(inputSourceId, "입력 자료의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(content, "파일의 내용은 반드시 있어야 합니다");
        Objects.requireNonNull(effectiveDate, "적용일은 반드시 있어야 합니다");
        Objects.requireNonNull(recordedAt, "기록한 때는 반드시 있어야 합니다");
        MappingDefinition mapping = pack.mappings()
            .stream()
            .filter(m -> m.inputSourceId().equals(inputSourceId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Pack " + pack.packId() + " 에 입력 자료 " + inputSourceId
                    + " 의 매핑이 없습니다. 입력 자료의 식별자를 확인하세요."));
        if (mapping.fileFormat() != MappingFileFormat.CSV) {
            throw new TableFileRejectedException("UNSUPPORTED_FORMAT",
                    "입력 자료 " + inputSourceId + " 은(는) 엑셀 파일로 받게 되어 있습니다. 엑셀 파일은 아직 읽지 못합니다. CSV 로 받는 매핑을 쓰세요.");
        }
        Provenance provenance = new Provenance(inputSourceId, editionNo);
        TableFile file = CsvTableReader.read(content);
        requireColumns(mapping, file);

        Plan plan = new Planner(pack, mapping, file, provenance, effectiveDate).plan();
        try {
            transaction.executeWithoutResult(status -> write(plan, recordedAt));
        }
        catch (RelationRejectedException | AttributeValueRejectedException | CoreStorageException
                | TransactionException | IllegalArgumentException e) {
            throw new MappingExecutionException("입력 자료 " + inputSourceId + " 의 " + editionNo
                    + "번째 판을 저장하지 못했습니다. 이 실행에서 넣은 것은 남지 않았습니다. 까닭: " + e.getMessage(), e);
        }
        return plan.outcome;
    }

    /** 매핑이 쓰는 열과 반드시 있어야 하는 열이 모두 파일에 있는지 본다 (D2 AD-174). */
    private static void requireColumns(MappingDefinition mapping, TableFile file) {
        TreeSet<String> needed = new TreeSet<>(LinkOrder.STRINGS);
        needed.addAll(mapping.requiredColumns());
        needed.addAll(mapping.itemKeyColumns());
        needed.add(mapping.primaryEntity().keyColumn());
        for (MappedEntity entity : mapping.entities()) {
            needed.add(entity.keyColumn());
            for (MappedAttribute attribute : entity.attributes()) {
                needed.add(attribute.column());
                addIfPresent(needed, attribute.validFromColumn());
                addIfPresent(needed, attribute.validToColumn());
            }
        }
        for (MappedRelation relation : mapping.relations()) {
            needed.add(relation.source().keyColumn());
            needed.add(relation.target().keyColumn());
            addIfPresent(needed, relation.validFromColumn());
            addIfPresent(needed, relation.validToColumn());
        }
        List<String> missing = needed.stream().filter(column -> !file.hasColumn(column)).toList();
        if (!missing.isEmpty()) {
            throw new TableFileRejectedException("MISSING_COLUMN", "파일에 있어야 하는 열이 없습니다: "
                    + String.join(", ", missing) + ". 파일의 열 이름을 확인하거나, 열 이름이 바뀌었으면 매핑을 고친 뒤 다시 넣으세요.");
        }
    }

    private static void addIfPresent(TreeSet<String> columns, String column) {
        if (column != null) {
            columns.add(column);
        }
    }

    /** 정해진 차례로 넣는다: 개체, 속성 값, 관계. 각각 식별자 순이다. */
    private void write(Plan plan, Instant recordedAt) {
        for (EntityRef entity : plan.entities.values()) {
            relationStore.saveEntity(entity, recordedAt);
        }
        for (NewAttributeValue value : plan.attributeValues.values()) {
            attributeValueStore.saveAttributeValue(value, recordedAt);
        }
        for (NewRelation relation : plan.relations.values()) {
            relationStore.saveRelation(relation, recordedAt);
        }
    }

    /** 넣을 것과 결과. 저장소에 닿기 전에 모두 정해진다. */
    private static final class Plan {

        final TreeMap<String, EntityRef> entities = new TreeMap<>(LinkOrder.STRINGS);

        final TreeMap<String, NewAttributeValue> attributeValues = new TreeMap<>(LinkOrder.STRINGS);

        final TreeMap<String, NewRelation> relations = new TreeMap<>(LinkOrder.STRINGS);

        MappingOutcome outcome;
    }

    private record Period(LocalDate from, LocalDate to) {

        boolean overlaps(Period other) {
            return (other.to == null || from.isBefore(other.to)) && (to == null || other.from.isBefore(to));
        }

        ValidityPeriod toValidity() {
            return new ValidityPeriod(from, to);
        }

        String shown() {
            return from + "~" + (to == null ? "" : to.toString());
        }
    }

    private record AttributeStatement(int rowNo, EntityRef entity, String attribute, String value, Period period) {
    }

    private record RelationStatement(int rowNo, EntityRef source, EntityRef target, String type, Period period) {
    }

    /** 행 하나에서 읽은 것. */
    private static final class RowPlan {

        final int rowNo;

        final String itemKey;

        final TreeMap<String, EntityRef> entities = new TreeMap<>(LinkOrder.STRINGS);

        final List<AttributeStatement> attributes = new ArrayList<>();

        final List<RelationStatement> relations = new ArrayList<>();

        final TreeSet<ItemOutput> outputs = new TreeSet<>(OUTPUT_ORDER);

        RowPlan(int rowNo, String itemKey) {
            this.rowNo = rowNo;
            this.itemKey = itemKey;
        }
    }

    /** 이 행을 건너뛴다. 흐름을 끊는 데만 쓰고 밖으로 나가지 않는다. */
    private static final class RowSkipped extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final transient IngestNotice notice;

        RowSkipped(int rowNo, String code, String subject, String detail) {
            super(null, null, false, false);
            this.notice = new IngestNotice("SKIPPED_ROW", rowNo, code, subject, detail);
        }
    }

    /** 파일을 읽어 넣을 것을 정한다. 저장소에 닿지 않는다. 같은 입력에는 같은 계획이 나온다. */
    private static final class Planner {

        private final PackDefinition pack;

        private final MappingDefinition mapping;

        private final TableFile file;

        private final Provenance provenance;

        private final LocalDate effectiveDate;

        private final ValueParser parser = new ValueParser();

        private final Map<String, ValueFormat> formats = new TreeMap<>(LinkOrder.STRINGS);

        private final TreeSet<IngestNotice> notices = new TreeSet<>(NOTICE_ORDER);

        Planner(PackDefinition pack, MappingDefinition mapping, TableFile file, Provenance provenance,
                LocalDate effectiveDate) {
            this.pack = pack;
            this.mapping = mapping;
            this.file = file;
            this.provenance = provenance;
            this.effectiveDate = effectiveDate;
            for (AttributeDefinition attribute : pack.attributes()) {
                formats.put(attribute.name(), attribute.valueFormat());
            }
        }

        Plan plan() {
            for (SkippedRow skipped : file.skipped()) {
                notices.add(new IngestNotice("SKIPPED_ROW", skipped.rowNo(), skipped.code(), null, skipped.detail()));
            }
            // 행마다 읽는다. 틀린 행은 건너뛴다.
            TreeMap<String, List<RowPlan>> byItemKey = new TreeMap<>(LinkOrder.STRINGS);
            for (TableRow row : file.rows()) {
                try {
                    RowPlan read = read(row);
                    byItemKey.computeIfAbsent(read.itemKey, k -> new ArrayList<>()).add(read);
                }
                catch (RowSkipped skipped) {
                    notices.add(skipped.notice);
                }
            }
            // 항목의 키가 겹치는 행은 모두 건너뛴다. 앞에 있는 행이 이기지 않는다 (D2 AD-157).
            List<RowPlan> rows = new ArrayList<>();
            byItemKey.forEach((itemKey, sameKey) -> {
                if (sameKey.size() == 1) {
                    rows.add(sameKey.get(0));
                }
                else {
                    for (RowPlan duplicate : sameKey) {
                        notices.add(new IngestNotice("SKIPPED_ROW", duplicate.rowNo, "DUPLICATE_ITEM_KEY", null, itemKey));
                    }
                }
            });

            Plan plan = new Plan();
            for (RowPlan row : rows) {
                plan.entities.putAll(row.entities);
                row.entities.keySet().forEach(id -> row.outputs.add(new ItemOutput(ItemOutput.Kind.ENTITY, id)));
            }
            settleAttributes(rows, plan);
            settleRelations(rows, plan);

            List<IngestedItem> items = new ArrayList<>();
            for (RowPlan row : rows) {
                items.add(new IngestedItem(row.itemKey, row.rowNo, List.copyOf(row.outputs)));
            }
            plan.outcome = new MappingOutcome(items, List.copyOf(notices));
            return plan;
        }

        private RowPlan read(TableRow row) {
            int rowNo = row.rowNo();
            // 항목의 키와 주 개체의 키가 비면 그 행을 건너뛴다 (D2 AD-94).
            List<String> keyValues = new ArrayList<>();
            for (String column : mapping.itemKeyColumns()) {
                String value = file.value(row, column);
                if (value.isEmpty()) {
                    throw new RowSkipped(rowNo, "BLANK_KEY", column, null);
                }
                keyValues.add(value);
            }
            if (file.value(row, mapping.primaryEntity().keyColumn()).isEmpty()) {
                throw new RowSkipped(rowNo, "BLANK_KEY", mapping.primaryEntity().keyColumn(), null);
            }
            RowPlan plan = new RowPlan(rowNo, LinkOrder.join(keyValues));
            // 주 개체는 행이 나타내는 개체다 (D2 AD-120). 개체 정의가 없고 관계의 끝으로만 적혀 있어도, 관계의 다른 끝이
            // 비어 있어도 만든다.
            EntityRef primary = ref(mapping.primaryEntity(), file.value(row, mapping.primaryEntity().keyColumn()));
            plan.entities.put(primary.id(), primary);

            for (MappedEntity mapped : mapping.entities()) {
                String key = file.value(row, mapped.keyColumn());
                if (key.isEmpty()) {
                    // 주 개체가 아닌 개체의 키가 비면 그 개체만 만들지 않는다. 그런데 속성 칸에 값이 있으면 자료가 어긋난 것이다
                    // (D2 AD-175).
                    for (MappedAttribute attribute : mapped.attributes()) {
                        if (!file.value(row, attribute.column()).isEmpty()) {
                            throw new RowSkipped(rowNo, "VALUE_WITHOUT_KEY", mapped.keyColumn(), null);
                        }
                    }
                    continue;
                }
                EntityRef entity = new EntityRef(pack.packId(), mapped.entityType(), key);
                plan.entities.put(entity.id(), entity);
                for (MappedAttribute attribute : mapped.attributes()) {
                    String raw = file.value(row, attribute.column());
                    if (raw.isEmpty()) {
                        // 빈 칸은 값이 없는 것이다 (D2 AD-94).
                        continue;
                    }
                    ValueFormat format = formats.get(attribute.attributeName());
                    String pattern = attribute.datePattern() != null ? attribute.datePattern()
                            : mapping.defaultDatePattern();
                    String value = format == null ? null
                            : parser.canonical(format, raw, pattern, attribute.groupingSeparator(),
                                    attribute.trueValue(), attribute.falseValue());
                    if (value == null) {
                        throw new RowSkipped(rowNo, "INVALID_VALUE", attribute.column(), raw);
                    }
                    Period period = period(row, attribute.validFromColumn(), attribute.validToColumn());
                    plan.attributes.add(new AttributeStatement(rowNo, entity, attribute.attributeName(), value, period));
                }
            }
            for (MappedRelation mapped : mapping.relations()) {
                String sourceKey = file.value(row, mapped.source().keyColumn());
                String targetKey = file.value(row, mapped.target().keyColumn());
                if (sourceKey.isEmpty() || targetKey.isEmpty()) {
                    // 관계의 끝을 가리키는 열이 빈 칸이면 그 관계를 만들지 않는다 (D2 AD-94).
                    continue;
                }
                EntityRef source = ref(mapped.source(), sourceKey);
                EntityRef target = ref(mapped.target(), targetKey);
                if (source.id().equals(target.id())) {
                    throw new RowSkipped(rowNo, "SELF_RELATION", mapped.target().keyColumn(), targetKey);
                }
                Period period = period(row, mapped.validFromColumn(), mapped.validToColumn());
                // 관계의 끝에 적힌 개체는 없으면 만든다 (D2 AD-59).
                plan.entities.put(source.id(), source);
                plan.entities.put(target.id(), target);
                plan.relations.add(new RelationStatement(rowNo, source, target, mapped.relationType(), period));
            }
            return plan;
        }

        private EntityRef ref(MappedEntityRef mapped, String key) {
            return new EntityRef(pack.packId(), mapped.entityType(), key);
        }

        /** 유효기간을 읽는다. 열이 없거나 빈 칸이면 시작일은 판의 적용일이고 종료일은 없다 (D2 AD-42). */
        private Period period(TableRow row, String fromColumn, String toColumn) {
            LocalDate from = date(row, fromColumn);
            LocalDate to = date(row, toColumn);
            Period period = new Period(from == null ? effectiveDate : from, to);
            if (to != null && !to.isAfter(period.from())) {
                throw new RowSkipped(row.rowNo(), "INVALID_PERIOD", toColumn, period.shown());
            }
            return period;
        }

        private LocalDate date(TableRow row, String column) {
            if (column == null) {
                return null;
            }
            String raw = file.value(row, column);
            if (raw.isEmpty()) {
                return null;
            }
            LocalDate date = parser.date(raw, mapping.defaultDatePattern());
            if (date == null) {
                throw new RowSkipped(row.rowNo(), "INVALID_VALUE", column, raw);
            }
            return date;
        }

        /**
         * 여러 행이 같은 개체의 같은 속성을 적은 것을 가린다 (D2 AD-136).
         * 값과 기간이 같으면 한 번만 넣는다. 기간이 겹치는데 값이나 기간이 다르면 그 속성의 값은 어느 것도 넣지 않고 알린다.
         * 기간이 서로 겹치지 않는 값들은 모두 넣는다.
         */
        private void settleAttributes(List<RowPlan> rows, Plan plan) {
            TreeMap<String, List<AttributeStatement>> groups = new TreeMap<>(LinkOrder.STRINGS);
            for (RowPlan row : rows) {
                for (AttributeStatement statement : row.attributes) {
                    groups.computeIfAbsent(attributeKey(statement), k -> new ArrayList<>()).add(statement);
                }
            }
            TreeMap<Integer, RowPlan> byRowNo = new TreeMap<>();
            rows.forEach(row -> byRowNo.put(row.rowNo, row));
            groups.forEach((key, statements) -> {
                if (conflicting(statements.stream().map(s -> new Stated(s.value(), s.period())).toList())) {
                    for (AttributeStatement statement : statements) {
                        notices.add(new IngestNotice("ATTRIBUTE_CONFLICT", statement.rowNo(), "CONFLICTING_VALUES", key,
                                statement.value()));
                    }
                    return;
                }
                for (AttributeStatement statement : statements) {
                    NewAttributeValue value = new NewAttributeValue(statement.entity(), statement.attribute(),
                            statement.value(), statement.period().toValidity(), provenance);
                    plan.attributeValues.put(value.id(), value);
                    byRowNo.get(statement.rowNo()).outputs.add(new ItemOutput(ItemOutput.Kind.ATTRIBUTE_VALUE, value.id()));
                }
            });
        }

        /** 여러 행이 같은 관계를 적은 것을 가린다. 속성 값과 같은 방식이다. */
        private void settleRelations(List<RowPlan> rows, Plan plan) {
            TreeMap<String, List<RelationStatement>> groups = new TreeMap<>(LinkOrder.STRINGS);
            for (RowPlan row : rows) {
                for (RelationStatement statement : row.relations) {
                    groups.computeIfAbsent(relationKey(statement), k -> new ArrayList<>()).add(statement);
                }
            }
            TreeMap<Integer, RowPlan> byRowNo = new TreeMap<>();
            rows.forEach(row -> byRowNo.put(row.rowNo, row));
            groups.forEach((key, statements) -> {
                if (conflicting(statements.stream().map(s -> new Stated("", s.period())).toList())) {
                    for (RelationStatement statement : statements) {
                        notices.add(new IngestNotice("RELATION_CONFLICT", statement.rowNo(), "CONFLICTING_PERIODS", key,
                                statement.period().shown()));
                    }
                    return;
                }
                for (RelationStatement statement : statements) {
                    NewRelation relation = new NewRelation(statement.source(), statement.target(), statement.type(),
                            statement.period().toValidity(), provenance);
                    plan.relations.put(relation.id(), relation);
                    byRowNo.get(statement.rowNo()).outputs.add(new ItemOutput(ItemOutput.Kind.RELATION, relation.id()));
                }
            });
        }

        private record Stated(String value, Period period) {
        }

        /** 서로 다른 것(값이나 기간이 다른 것) 가운데 기간이 겹치는 쌍이 있는가. 차례와 무관하다. */
        private static boolean conflicting(List<Stated> stated) {
            List<Stated> distinct = stated.stream().distinct().toList();
            for (int i = 0; i < distinct.size(); i++) {
                for (int j = i + 1; j < distinct.size(); j++) {
                    if (distinct.get(i).period().overlaps(distinct.get(j).period())) {
                        return true;
                    }
                }
            }
            return false;
        }

        private String attributeKey(AttributeStatement statement) {
            EntityRef entity = statement.entity();
            return LinkOrder.join(List.of(entity.packId(), entity.entityType(), entity.entityKey(), statement.attribute()));
        }

        private String relationKey(RelationStatement statement) {
            EntityRef source = statement.source();
            EntityRef target = statement.target();
            return LinkOrder.join(List.of(source.packId(), source.entityType(), source.entityKey(), target.entityType(),
                    target.entityKey(), statement.type()));
        }
    }
}
