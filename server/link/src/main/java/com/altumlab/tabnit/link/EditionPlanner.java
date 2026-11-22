package com.altumlab.tabnit.link;

import com.altumlab.tabnit.core.AttributeValue;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.NewAttributeValue;
import com.altumlab.tabnit.core.NewRelation;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.ValidityPeriod;
import com.altumlab.tabnit.link.EditionPlan.OutputDetail;
import com.altumlab.tabnit.link.EditionPlan.PlannedItem;
import com.altumlab.tabnit.link.EditionPlan.PreviousItem;
import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.MappedAttribute;
import com.altumlab.tabnit.pack.MappedEntity;
import com.altumlab.tabnit.pack.MappedEntityRef;
import com.altumlab.tabnit.pack.MappedRelation;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.ValueFormat;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 파일을 읽어 판 하나를 넣는 계획을 세운다. 저장소에 닿지 않는다. 같은 입력에는 같은 계획이 나온다.
 *
 * <p>하는 일은 둘이다.
 * <ol>
 * <li>매핑 실행: 행을 Pack 의 매핑 정의에 따라 개체, 속성 값, 관계로 읽는다 (D6 7절).</li>
 * <li>판 비교: 앞 판의 항목이 만든 것과 견줘, 이을 것과 끝낼 것과 새로 넣을 것을 가리고 바뀐 항목을 낸다
 * (D3 6절, D5 5절). 앞 판이 없으면 모두 새로 넣고 바뀐 항목을 내지 않는다 (D2 AD-148).</li>
 * </ol>
 *
 * <p>견주는 단위는 행이 적은 것 하나(속성 값 하나, 관계 하나)다. 바뀐 것만 끝내고 다시 넣는다 (D2 AD-195).
 * 열 구성과 유형은 매핑에서만 온다. 특정 파일의 열 이름을 알지 못한다.
 */
final class EditionPlanner {

    /** 키 열의 값 하나와, 이은 항목의 키가 가질 수 있는 가장 긴 길이. 글자(코드 포인트)로 센다 (D2 AD-177, AD-187). */
    static final int MAX_KEY_LENGTH = 200;

    static final Comparator<IngestNotice> NOTICE_ORDER = Comparator.comparingInt(IngestNotice::rowNo)
        .thenComparing(IngestNotice::kind, LinkOrder.STRINGS)
        .thenComparing(IngestNotice::code, LinkOrder.STRINGS)
        .thenComparing(IngestNotice::subject, LinkOrder.NULLS_FIRST)
        .thenComparing(IngestNotice::detail, LinkOrder.NULLS_FIRST);

    static final Comparator<ItemOutput> OUTPUT_ORDER = Comparator.comparing(ItemOutput::kind)
        .thenComparing(ItemOutput::objectId, LinkOrder.STRINGS);

    private static final Comparator<OutputDetail> DETAIL_ORDER = Comparator.comparing(OutputDetail::kind)
        .thenComparing(OutputDetail::objectId, LinkOrder.STRINGS);

    private final PackDefinition pack;

    private final MappingDefinition mapping;

    private final TableFile file;

    private final Provenance provenance;

    private final LocalDate effectiveDate;

    /** 앞 판의 항목. 항목의 키 순. 앞 판이 없으면 null 이다 (항목이 없는 앞 판은 빈 모음이다). */
    private final TreeMap<String, PreviousItem> previous;

    private final ValueParser parser = new ValueParser();

    private final Map<String, ValueFormat> formats = new TreeMap<>(LinkOrder.STRINGS);

    private final TreeSet<IngestNotice> notices = new TreeSet<>(NOTICE_ORDER);

    private final EditionPlan plan;

    /** 저장소에 이미 있는 속성 값을 읽는 길. 계획기는 저장소를 직접 알지 않는다. */
    @FunctionalInterface
    interface StoredValues {

        /** 개체들의 속성 값 가운데 그날부터 하루라도 유효한 것. 식별자 순이다. */
        List<AttributeValue> inEffectFrom(Collection<String> entityIds, LocalDate from);
    }

    private final StoredValues storedValues;

    /** 끝내야 하는데 끝낼 수 없는 것의 식별자. 하나라도 있으면 판을 거부한다 (D2 AD-198). */
    private final TreeSet<String> cannotEnd = new TreeSet<>(LinkOrder.STRINGS);

    EditionPlanner(PackDefinition pack, MappingDefinition mapping, TableFile file, Provenance provenance,
            LocalDate effectiveDate, List<PreviousItem> previousItems, StoredValues storedValues) {
        this.pack = pack;
        this.storedValues = storedValues;
        this.mapping = mapping;
        this.file = file;
        this.provenance = provenance;
        this.effectiveDate = effectiveDate;
        this.plan = new EditionPlan(effectiveDate);
        if (previousItems == null) {
            this.previous = null;
        }
        else {
            this.previous = new TreeMap<>(LinkOrder.STRINGS);
            previousItems.forEach(item -> this.previous.put(item.itemKey(), item));
        }
        for (AttributeDefinition attribute : pack.attributes()) {
            formats.put(attribute.name(), attribute.valueFormat());
        }
    }

    // ── 읽은 것 ────────────────────────────────────────────────

    private record Period(LocalDate from, LocalDate to) {

        boolean overlaps(Period other) {
            return (other.to == null || from.isBefore(other.to)) && (to == null || other.from.isBefore(to));
        }

        String shown() {
            return from + "~" + (to == null ? "" : to.toString());
        }
    }

    /**
     * 행이 적은 것 하나: 속성 값 하나나 관계 하나.
     *
     * @param subject 무엇에 대한 것인가. 기록에 남기는 글자
     * @param noticeSubject 알림에 적는 글자. 식별자의 모양이다 (Pack, 유형, 키, …)
     * @param factHash 이 줄의 해시
     * @param value 속성 값이면 읽은 값. 관계면 빈 글자
     * @param stated 유효기간의 칸에 적힌 것 (시작일~종료일). 빈 칸은 빈 글자
     * @param period 적힌 유효기간. 시작일이 빈 칸이면 판의 적용일이다 (D2 AD-42)
     */
    private record Statement(RowPlan row, ItemOutput.Kind kind, String subject, String noticeSubject, String factHash,
            String value, String stated, Period period, EntityRef first, EntityRef second, String name) {
    }

    /** 행 하나에서 읽은 것. */
    private static final class RowPlan {

        final int rowNo;

        final String itemKey;

        EntityRef primary;

        final TreeMap<String, EntityRef> entities = new TreeMap<>(LinkOrder.STRINGS);

        final List<Statement> statements = new ArrayList<>();

        /** 이 행이 적은 것의 줄. 항목의 내용 해시에 쓴다. */
        final List<String> facts = new ArrayList<>();

        /** 이 행이 만들었거나 이어 받은 것. 식별자마다 하나다. */
        final TreeMap<String, OutputDetail> outputs = new TreeMap<>(LinkOrder.STRINGS);

        RowPlan(int rowNo, String itemKey) {
            this.rowNo = rowNo;
            this.itemKey = itemKey;
        }

        void made(OutputDetail detail) {
            outputs.putIfAbsent(detail.kind().name() + "|" + detail.objectId(), detail);
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

    // ── 계획 ───────────────────────────────────────────────────

    EditionPlan plan() {
        for (SkippedRow skipped : file.skipped()) {
            notices.add(new IngestNotice("SKIPPED_ROW", skipped.rowNo(), skipped.code(), null, skipped.detail()));
        }
        // 행마다 읽는다. 틀린 행은 건너뛴다. 항목의 키를 읽을 수 있었던 행은 뒤에서 건너뛰어도 키가 겹치는지 볼 때 센다
        // (D2 AD-181).
        TreeMap<String, List<Integer>> rowNosByItemKey = new TreeMap<>(LinkOrder.STRINGS);
        TreeMap<String, RowPlan> readByItemKey = new TreeMap<>(LinkOrder.STRINGS);
        for (TableRow row : file.rows()) {
            try {
                String itemKey = itemKey(row);
                rowNosByItemKey.computeIfAbsent(itemKey, k -> new ArrayList<>()).add(row.rowNo());
                readByItemKey.put(itemKey, read(row, itemKey));
            }
            catch (RowSkipped skipped) {
                notices.add(skipped.notice);
            }
        }
        // 항목의 키가 겹치는 행은 모두 건너뛴다. 앞에 있는 행이 이기지 않는다 (D2 AD-157).
        // 키를 읽었지만 건너뛴 행은, 앞 판에 그 키의 항목이 있었으면 그 항목을 그대로 이어 둔다 (D2 AD-157, AD-196).
        TreeMap<String, RowPlan> rows = new TreeMap<>(LinkOrder.STRINGS);
        TreeMap<String, Integer> carriedRowNo = new TreeMap<>(LinkOrder.STRINGS);
        rowNosByItemKey.forEach((itemKey, rowNos) -> {
            if (rowNos.size() > 1) {
                for (int rowNo : rowNos) {
                    notices.add(new IngestNotice("SKIPPED_ROW", rowNo, "DUPLICATE_ITEM_KEY", null, itemKey));
                }
            }
            else if (readByItemKey.containsKey(itemKey)) {
                rows.put(itemKey, readByItemKey.get(itemKey));
                return;
            }
            if (previous != null && previous.containsKey(itemKey)) {
                carriedRowNo.put(itemKey, rowNos.stream().min(Integer::compare).orElseThrow());
            }
        });

        settle(rows, carriedRowNo);
        if (!cannotEnd.isEmpty()) {
            throw new TableFileRejectedException("CANNOT_END", cannotEnd.first(), "앞 판이 넣은 것 가운데 이 판의 적용일("
                    + effectiveDate + ")로 끝낼 수 없는 것이 " + cannotEnd.size() + "개 있습니다. 시작일이 적용일과 같거나 뒤이거나, "
                    + "적힌 종료일이 적용일보다 뒤입니다. 처음 걸린 것: " + cannotEnd.first()
                    + ". 적용일을 그 날짜 뒤로 잡아 다시 넣으세요.");
        }

        // 항목: 받아들인 행과, 앞 판의 것을 이어 둔 항목. 항목의 키 순이다.
        TreeMap<String, PlannedItem> items = new TreeMap<>(LinkOrder.STRINGS);
        for (RowPlan row : rows.values()) {
            plan.entities.putAll(row.entities);
            List<OutputDetail> details = new ArrayList<>(row.outputs.values());
            details.sort(DETAIL_ORDER);
            items.put(row.itemKey, new PlannedItem(new IngestedItem(row.itemKey, row.rowNo, contentHash(row),
                    row.primary.id(), details.stream().map(d -> new ItemOutput(d.kind(), d.objectId())).toList()),
                    details));
        }
        carriedRowNo.forEach((itemKey, rowNo) -> {
            PreviousItem before = previous.get(itemKey);
            // 앞 판의 내용 해시와 만든 것을 그대로 옮겨 적는다. 행 번호는 이 파일에서 그 키가 처음 나온 행이다.
            items.put(itemKey,
                    new PlannedItem(new IngestedItem(itemKey, rowNo, before.contentHash(), before.primaryEntityId(),
                            before.outputs().stream().map(d -> new ItemOutput(d.kind(), d.objectId())).toList()),
                            before.outputs()));
        });
        plan.items.addAll(items.values());
        plan.outcome = new MappingOutcome(plan.items.stream().map(PlannedItem::item).toList(), List.copyOf(notices),
                changes(items));
        return plan;
    }

    // ── 판 비교 ─────────────────────────────────────────────────

    /**
     * 행이 적은 것과 앞 판이 넣어 둔 것을 대상(개체의 속성 하나, 관계 하나)마다 견준다.
     * 정해진 것은 계획(끝낼 것, 넣을 것)과 행마다의 "만든 것"에 담는다.
     */
    private void settle(TreeMap<String, RowPlan> rows, TreeMap<String, Integer> carriedRowNo) {
        // 개체는 견줄 것이 없다. 행이 적은 개체는 그대로 그 행이 만든 것이다.
        for (RowPlan row : rows.values()) {
            for (EntityRef entity : row.entities.values()) {
                String subject = LinkOrder.join(List.of("E", entity.entityType(), entity.entityKey()));
                row.made(new OutputDetail(ItemOutput.Kind.ENTITY, entity.id(), subject, hash(subject), null, null));
            }
        }
        // 앞 판이 넣어 둔 것 (속성 값과 관계). 대상마다, 식별자 순.
        TreeMap<String, TreeMap<String, OutputDetail>> liveBySubject = new TreeMap<>(LinkOrder.STRINGS);
        // 앞 판의 행들이 적은 줄마다 그것이 가리키는 것. 같은 것을 여러 행이 서로 다른 줄로 적었을 수 있다
        // (유효기간의 칸을 비운 행과 같은 날짜를 적은 행). 그 줄들을 모두 알아야 다음 판에서 이을 수 있다.
        TreeMap<String, TreeMap<String, String>> factsBySubject = new TreeMap<>(LinkOrder.STRINGS);
        // 이어 둔 항목이 쥐고 있는 것. 다른 행이 적지 않아도 끝내지 않는다.
        TreeSet<String> held = new TreeSet<>(LinkOrder.STRINGS);
        if (previous != null) {
            for (PreviousItem item : previous.values()) {
                for (OutputDetail detail : item.outputs()) {
                    if (detail.kind() == ItemOutput.Kind.ENTITY) {
                        continue;
                    }
                    liveBySubject.computeIfAbsent(detail.subject(), k -> new TreeMap<>(LinkOrder.STRINGS))
                        .putIfAbsent(detail.objectId(), detail);
                    // 같은 줄이 두 가지를 가리키는 일은 없다. 있더라도 식별자가 앞선 것으로 정한다.
                    factsBySubject.computeIfAbsent(detail.subject(), k -> new TreeMap<>(LinkOrder.STRINGS))
                        .merge(detail.factHash(), detail.objectId(),
                                (a, b) -> LinkOrder.compare(a, b) <= 0 ? a : b);
                    if (carriedRowNo.containsKey(item.itemKey())) {
                        held.add(detail.objectId());
                    }
                }
            }
        }
        TreeMap<String, List<Statement>> statedBySubject = new TreeMap<>(LinkOrder.STRINGS);
        for (RowPlan row : rows.values()) {
            for (Statement statement : row.statements) {
                statedBySubject.computeIfAbsent(statement.subject(), k -> new ArrayList<>()).add(statement);
            }
        }
        TreeMap<String, List<AttributeValue>> storedBySubject = storedBySubject(statedBySubject);
        TreeSet<String> subjects = new TreeSet<>(LinkOrder.STRINGS);
        subjects.addAll(liveBySubject.keySet());
        subjects.addAll(statedBySubject.keySet());
        for (String subject : subjects) {
            settleSubject(statedBySubject.getOrDefault(subject, List.of()),
                    liveBySubject.getOrDefault(subject, new TreeMap<>(LinkOrder.STRINGS)),
                    factsBySubject.getOrDefault(subject, new TreeMap<>(LinkOrder.STRINGS)), held,
                    storedBySubject.getOrDefault(subject, List.of()));
        }
    }

    /**
     * 행이 적은 속성 값의 대상마다, 저장소에 이미 있는 그 개체의 그 속성의 값. 식별자 순이다.
     * 겹침은 출처를 보지 않으므로 (D2 AD-53) 다른 입력 자료가 넣은 값도 든다. 한 번만 읽는다.
     */
    private TreeMap<String, List<AttributeValue>> storedBySubject(TreeMap<String, List<Statement>> statedBySubject) {
        TreeMap<String, List<AttributeValue>> bySubject = new TreeMap<>(LinkOrder.STRINGS);
        // 개체와 속성에서 대상으로 가는 길, 물을 개체, 넣을 기간 가운데 가장 이른 시작일.
        TreeMap<String, String> subjectByOwner = new TreeMap<>(LinkOrder.STRINGS);
        TreeSet<String> entityIds = new TreeSet<>(LinkOrder.STRINGS);
        LocalDate earliest = null;
        for (List<Statement> statements : statedBySubject.values()) {
            for (Statement statement : statements) {
                if (statement.kind() != ItemOutput.Kind.ATTRIBUTE_VALUE || alreadyPast(statement)) {
                    continue;
                }
                subjectByOwner.put(LinkOrder.join(List.of(statement.first().id(), statement.name())),
                        statement.subject());
                entityIds.add(statement.first().id());
                LocalDate from = fresh(statement).from();
                earliest = earliest == null || from.isBefore(earliest) ? from : earliest;
            }
        }
        if (entityIds.isEmpty()) {
            return bySubject;
        }
        for (AttributeValue value : storedValues.inEffectFrom(entityIds, earliest)) {
            String subject = subjectByOwner.get(LinkOrder.join(List.of(value.entityId(), value.attributeName())));
            if (subject != null) {
                bySubject.computeIfAbsent(subject, k -> new ArrayList<>()).add(value);
            }
        }
        return bySubject;
    }

    /** 대상 하나: 한 개체의 한 속성, 또는 한 관계(출발, 도착, 유형). */
    private void settleSubject(List<Statement> stated, TreeMap<String, OutputDetail> live,
            TreeMap<String, String> liveByFact, TreeSet<String> held, List<AttributeValue> stored) {
        // 행이 적은 것마다: 앞 판의 어느 행이 적은 줄과 같으면 그 줄이 가리키던 것을 잇고, 아니면 새로 넣을 것이다.
        // 적은 것마다 이을 것. 적힌 차례 그대로 둔다 (행의 키 순, 행 안에서는 매핑의 차례). 새것이면 null 이다.
        List<OutputDetail> continued = new ArrayList<>();
        // 유효한 것의 모음: 열쇠가 다른 둘의 기간이 겹치면 부딪힘이다. 값과 기간이 같은 새것들은 열쇠가 같다.
        TreeMap<String, Period> inEffect = new TreeMap<>(LinkOrder.STRINGS);
        for (Statement statement : stated) {
            String sameId = liveByFact.get(statement.factHash());
            OutputDetail same = sameId == null ? null : live.get(sameId);
            continued.add(same);
            if (same != null) {
                inEffect.put("0|" + same.objectId(), new Period(same.validFrom(), same.validTo()));
            }
            else if (!alreadyPast(statement)) {
                inEffect.put(newKey(statement), fresh(statement));
            }
        }
        for (OutputDetail detail : live.values()) {
            if (held.contains(detail.objectId())) {
                inEffect.put("0|" + detail.objectId(), new Period(detail.validFrom(), detail.validTo()));
            }
        }
        if (conflicting(new ArrayList<>(inEffect.values()))) {
            // 부딪혔다. 어느 것이 맞는지 고르지 않는다. 새것은 넣지 않고 앞 판의 것은 그대로 둔다: 끝내지 않고,
            // 적은 행마다 앞 판의 것을 이어 적어 다음 판이 다시 가릴 수 있게 한다 (D2 AD-136, AD-146, AD-179, AD-180).
            for (int i = 0; i < stated.size(); i++) {
                Statement statement = stated.get(i);
                boolean attribute = statement.kind() == ItemOutput.Kind.ATTRIBUTE_VALUE;
                OutputDetail same = continued.get(i);
                notices.add(new IngestNotice(attribute ? "ATTRIBUTE_CONFLICT" : "RELATION_CONFLICT",
                        statement.row().rowNo, attribute ? "CONFLICTING_VALUES" : "CONFLICTING_PERIODS",
                        statement.noticeSubject(), attribute ? statement.value()
                                : (same == null ? statement.period()
                                        : new Period(same.validFrom(), same.validTo())).shown()));
            }
            // 앞 판의 것은 그대로 둔다. 다음 판이 다시 가릴 수 있게 누군가의 항목에 적혀 있어야 한다:
            // 앞 판에서 그것을 적었던 행에 이어 적고, 그런 행이 이 대상을 적은 행 가운데 없으면 적은 행 모두에 적는다.
            for (OutputDetail detail : live.values()) {
                List<RowPlan> owners = stated.stream()
                    .map(Statement::row)
                    .filter(row -> previous.containsKey(row.itemKey) && previous.get(row.itemKey)
                        .outputs()
                        .stream()
                        .anyMatch(o -> o.kind() == detail.kind() && o.objectId().equals(detail.objectId())))
                    .toList();
                (owners.isEmpty() ? stated.stream().map(Statement::row).toList() : owners)
                    .forEach(row -> row.made(detail));
            }
            return;
        }
        // 앞 판이 넣어 둔 것 가운데 아무도 적지 않은 것: 열려 있으면 이 판의 적용일로 끝낸다 (D2 AD-42, AD-121).
        TreeSet<String> kept = new TreeSet<>(LinkOrder.STRINGS);
        kept.addAll(held);
        continued.stream().filter(java.util.Objects::nonNull).forEach(detail -> kept.add(detail.objectId()));
        for (OutputDetail detail : live.values()) {
            if (kept.contains(detail.objectId())) {
                continue;
            }
            if (detail.validTo() == null) {
                if (!detail.validFrom().isBefore(effectiveDate)) {
                    // 아직 시작하지 않은 것은 끝낼 수 없다. 종료일은 시작일보다 뒤여야 한다 (D2 AD-162, AD-198).
                    cannotEnd.add(detail.objectId());
                    continue;
                }
                (detail.kind() == ItemOutput.Kind.RELATION ? plan.relationsToEnd : plan.attributeValuesToEnd)
                    .add(detail.objectId());
            }
            else if (detail.validTo().isAfter(effectiveDate)) {
                // 적힌 종료일이 적용일보다 뒤다. 한 번 넣은 종료일은 바꾸지 않으므로 당겨 끝낼 수 없다 (D2 AD-162, AD-198).
                cannotEnd.add(detail.objectId());
            }
            // 아니면 이미 지난 기간의 것이다. 그대로 남는다.
        }
        // 이은 것은 그 행이 만든 것으로 다시 적는다. 줄의 해시는 이 행이 적은 것이다.
        for (int i = 0; i < stated.size(); i++) {
            OutputDetail same = continued.get(i);
            if (same != null) {
                stated.get(i)
                    .row()
                    .made(new OutputDetail(same.kind(), same.objectId(), same.subject(), stated.get(i).factHash(),
                            same.validFrom(), same.validTo()));
            }
        }
        // 새로 넣을 것. 값과 기간이 같은 것은 하나다.
        TreeMap<String, List<Statement>> fresh = new TreeMap<>(LinkOrder.STRINGS);
        for (int i = 0; i < stated.size(); i++) {
            if (continued.get(i) == null) {
                fresh.computeIfAbsent(newKey(stated.get(i)), k -> new ArrayList<>()).add(stated.get(i));
            }
        }
        for (List<Statement> same : fresh.values()) {
            Statement head = same.get(0);
            Period period = fresh(head);
            // 둘째 판부터는 그 판의 적용일 앞으로 넣지 않는다. 과거는 고치지 않는다 (D2 AD-199, AD-200).
            // 적힌 시작일이 더 앞이면 적용일부터 넣고, 적힌 종료일이 적용일과 같거나 앞이면 넣지 않는다. 둘 다 알린다.
            // 함께 묶인 행들은 넣을 기간이 같을 뿐 적힌 기간은 다를 수 있으므로, 알림은 행마다 따로 가린다.
            boolean past = alreadyPast(head);
            for (Statement statement : same) {
                if (past || !period.equals(statement.period())) {
                    notices.add(new IngestNotice("PERIOD_ADJUSTED", statement.row().rowNo,
                            past ? "ALREADY_PAST" : "STARTS_AT_EFFECTIVE_DATE", statement.noticeSubject(),
                            statement.stated()));
                }
            }
            if (past) {
                continue;
            }
            // 저장소에 이미 있는 값 가운데 이 입력 자료의 앞 판이 넣은 것이 아닌 것 (다른 입력 자료가 넣었거나 앞앞 판에서
            // 끝난 것)과 기간이 겹치면 넣지 않고 알린다. 판의 나머지는 받는다. 이 행이 만든 것으로 적지 않으므로
            // 다음 판이 다시 가린다 (D2 AD-322, AD-344).
            // 넣으려는 것과 값, 기간, 출처가 모두 같은 것은 겹침이 아니다: 같은 판을 다시 넣은 것이고 core 가 그대로 둔다.
            List<String> overlapping = new ArrayList<>();
            for (AttributeValue value : stored) {
                Period there = new Period(value.period().from(), value.period().to());
                boolean sameAsNew = head.kind() == ItemOutput.Kind.ATTRIBUTE_VALUE && value.value().equals(head.value())
                        && there.equals(period) && value.provenance().equals(provenance);
                if (!live.containsKey(value.id()) && !sameAsNew && period.overlaps(there)) {
                    overlapping.add(value.value() + " (" + there.shown() + ", 입력 자료 "
                            + value.provenance().inputSourceId() + " 의 " + value.provenance().editionNo() + "번째 판)");
                }
            }
            if (!overlapping.isEmpty()) {
                for (Statement statement : same) {
                    notices.add(new IngestNotice("ATTRIBUTE_CONFLICT", statement.row().rowNo, "OVERLAPS_STORED_VALUE",
                            statement.noticeSubject(), statement.value() + " (" + period.shown() + "). 이미 있는 값: "
                                    + String.join(", ", overlapping)));
                }
                continue;
            }
            ValidityPeriod validity = new ValidityPeriod(period.from(), period.to());
            String objectId;
            if (head.kind() == ItemOutput.Kind.ATTRIBUTE_VALUE) {
                NewAttributeValue value = new NewAttributeValue(head.first(), head.name(), head.value(), validity,
                        provenance);
                plan.attributeValues.put(value.id(), value);
                objectId = value.id();
            }
            else {
                NewRelation relation = new NewRelation(head.first(), head.second(), head.name(), validity, provenance);
                plan.relations.put(relation.id(), relation);
                objectId = relation.id();
            }
            for (Statement statement : same) {
                statement.row()
                    .made(new OutputDetail(statement.kind(), objectId, statement.subject(), statement.factHash(),
                            period.from(), period.to()));
            }
        }
    }

    /** 새로 넣을 것의 열쇠: 값과 넣을 기간. 같은 값을 같은 기간으로 넣게 되는 행들은 하나를 함께 만든다. */
    private String newKey(Statement statement) {
        return "1|" + LinkOrder.join(List.of(statement.value(), fresh(statement).shown()));
    }

    /**
     * 새로 넣을 때의 기간. 첫 판은 적힌 그대로다. 둘째 판부터는 시작일이 그 판의 적용일보다 앞설 수 없다 (D2 AD-200).
     * link 는 바로 앞 판의 기록만 보므로 그보다 먼저 끝난 것의 기간을 모른다. 과거로 넣으면 그것과 겹칠 수 있다.
     */
    private Period fresh(Statement statement) {
        Period stated = statement.period();
        return previous != null && stated.from().isBefore(effectiveDate) ? new Period(effectiveDate, stated.to())
                : stated;
    }

    /** 둘째 판부터: 적힌 종료일이 그 판의 적용일과 같거나 앞이면 넣을 기간이 없다. */
    private boolean alreadyPast(Statement statement) {
        Period stated = statement.period();
        return previous != null && stated.to() != null && !stated.to().isAfter(effectiveDate);
    }

    /** 기간이 겹치는 쌍이 있는가. 차례와 무관하다. */
    private static boolean conflicting(List<Period> periods) {
        for (int i = 0; i < periods.size(); i++) {
            for (int j = i + 1; j < periods.size(); j++) {
                if (periods.get(i).overlaps(periods.get(j))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 바뀐 항목. 첫 판이면 없다 (D2 AD-148).
     * 변경인지는 그 항목이 만든 것(이어 받은 것 포함)이 앞 판과 다른가로 가린다. 행이 적은 것이 달라져도 부딪혀서
     * 앞 판의 것이 그대로 남았으면 바뀐 것이 없다 (D2 AD-146).
     */
    private List<ItemChange> changes(TreeMap<String, PlannedItem> items) {
        if (previous == null) {
            return List.of();
        }
        TreeSet<String> keys = new TreeSet<>(LinkOrder.STRINGS);
        keys.addAll(previous.keySet());
        keys.addAll(items.keySet());
        List<ItemChange> changes = new ArrayList<>();
        for (String itemKey : keys) {
            PreviousItem before = previous.get(itemKey);
            PlannedItem after = items.get(itemKey);
            if (before == null) {
                changes.add(new ItemChange(itemKey, ChangeKind.ADDED, after.item().primaryEntityId(),
                        List.copyOf(entities(after.outputs())),
                        names(after.outputs(), null, ItemOutput.Kind.ATTRIBUTE_VALUE),
                        names(after.outputs(), null, ItemOutput.Kind.RELATION)));
            }
            else if (after == null) {
                changes.add(new ItemChange(itemKey, ChangeKind.REMOVED, before.primaryEntityId(),
                        List.copyOf(entities(before.outputs())),
                        names(before.outputs(), null, ItemOutput.Kind.ATTRIBUTE_VALUE),
                        names(before.outputs(), null, ItemOutput.Kind.RELATION)));
            }
            else if (!made(before.outputs()).equals(made(after.outputs()))) {
                TreeSet<String> targets = entities(before.outputs());
                targets.addAll(entities(after.outputs()));
                changes.add(new ItemChange(itemKey, ChangeKind.CHANGED, after.item().primaryEntityId(),
                        List.copyOf(targets),
                        changedNames(before.outputs(), after.outputs(), ItemOutput.Kind.ATTRIBUTE_VALUE),
                        changedNames(before.outputs(), after.outputs(), ItemOutput.Kind.RELATION)));
            }
        }
        return changes;
    }

    private static TreeSet<String> entities(List<OutputDetail> outputs) {
        TreeSet<String> ids = new TreeSet<>(LinkOrder.STRINGS);
        outputs.stream().filter(d -> d.kind() == ItemOutput.Kind.ENTITY).forEach(d -> ids.add(d.objectId()));
        return ids;
    }

    /**
     * 두 판에서 그 행이 만든 것이 다른 속성(또는 관계 유형)의 이름 (D2 AD-291). 한쪽에만 있는 것의 이름이다:
     * 값이 바뀌면 앞 판의 것이 끝나고 새것이 생기므로 두 쪽 모두에서 나온다. 이어 받은 것은 두 쪽에 같이 있어 빠진다.
     */
    private static List<String> changedNames(List<OutputDetail> before, List<OutputDetail> after, ItemOutput.Kind kind) {
        TreeSet<String> names = new TreeSet<>(LinkOrder.STRINGS);
        names.addAll(names(before, made(after), kind));
        names.addAll(names(after, made(before), kind));
        return List.copyOf(names);
    }

    /** 그 종류의 것 가운데 except 에 없는 것의 이름. 글자 코드 순. 이름은 "무엇에 대한 것인가"의 마지막 값이다. */
    private static List<String> names(List<OutputDetail> outputs, TreeSet<String> except, ItemOutput.Kind kind) {
        TreeSet<String> names = new TreeSet<>(LinkOrder.STRINGS);
        for (OutputDetail detail : outputs) {
            if (detail.kind() == kind && (except == null || !except.contains(detail.kind().name() + "|" + detail.objectId()))) {
                names.add(LinkOrder.lastPart(detail.subject()));
            }
        }
        return List.copyOf(names);
    }

    private static TreeSet<String> made(List<OutputDetail> outputs) {
        TreeSet<String> ids = new TreeSet<>(LinkOrder.STRINGS);
        outputs.forEach(d -> ids.add(d.kind().name() + "|" + d.objectId()));
        return ids;
    }

    // ── 행 읽기 ─────────────────────────────────────────────────

    /** 항목의 키를 읽는다. 키 열이 비면 그 행을 건너뛴다 (D2 AD-94). */
    private String itemKey(TableRow row) {
        List<String> keyValues = new ArrayList<>();
        for (String column : mapping.itemKeyColumns()) {
            String value = key(row, column);
            if (value.isEmpty()) {
                throw new RowSkipped(row.rowNo(), "BLANK_KEY", column, null);
            }
            keyValues.add(value);
        }
        // 이은 항목의 키도 같은 길이까지다. 키 열이 여럿이면 값마다는 짧아도 이은 것이 길 수 있다 (D2 AD-187).
        String itemKey = LinkOrder.join(keyValues);
        int length = itemKey.codePointCount(0, itemKey.length());
        if (length > MAX_KEY_LENGTH) {
            throw new RowSkipped(row.rowNo(), "KEY_TOO_LONG", null, Integer.toString(length));
        }
        return itemKey;
    }

    /** 키 열의 값을 읽는다. 너무 길면 그 행을 건너뛴다. 저장소가 받지 못해 실행 전체가 실패하지 않게 한다 (D2 AD-177). */
    private String key(TableRow row, String column) {
        String value = file.value(row, column);
        int length = value.codePointCount(0, value.length());
        if (length > MAX_KEY_LENGTH) {
            throw new RowSkipped(row.rowNo(), "KEY_TOO_LONG", column, Integer.toString(length));
        }
        return value;
    }

    private RowPlan read(TableRow row, String itemKey) {
        int rowNo = row.rowNo();
        // 주 개체의 키가 비면 그 행을 건너뛴다 (D2 AD-94).
        if (key(row, mapping.primaryEntity().keyColumn()).isEmpty()) {
            throw new RowSkipped(rowNo, "BLANK_KEY", mapping.primaryEntity().keyColumn(), null);
        }
        RowPlan plan = new RowPlan(rowNo, itemKey);
        // 주 개체는 행이 나타내는 개체다 (D2 AD-120). 개체 정의가 없고 관계의 끝으로만 적혀 있어도, 관계의 다른 끝이
        // 비어 있어도 만든다.
        plan.primary = ref(mapping.primaryEntity(), file.value(row, mapping.primaryEntity().keyColumn()));
        plan.entities.put(plan.primary.id(), plan.primary);

        for (MappedEntity mapped : mapping.entities()) {
            String key = key(row, mapped.keyColumn());
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
                        : parser.canonical(format, raw, pattern, attribute.groupingSeparator(), attribute.trueValue(),
                                attribute.falseValue());
                if (value == null) {
                    throw new RowSkipped(rowNo, "INVALID_VALUE", attribute.column(), raw);
                }
                Period period = period(row, attribute.validFromColumn(), attribute.validToColumn());
                String stated = stated(row, attribute.validFromColumn(), attribute.validToColumn());
                List<String> about = List.of(entity.entityType(), entity.entityKey(), attribute.attributeName());
                String fact = LinkOrder.join(concat("A", about, value, stated));
                plan.facts.add(fact);
                plan.statements
                    .add(new Statement(plan, ItemOutput.Kind.ATTRIBUTE_VALUE, LinkOrder.join(concat("A", about)),
                            LinkOrder.join(concat(entity.packId(), about)), hash(fact), value, stated, period, entity,
                            null, attribute.attributeName()));
            }
        }
        for (MappedRelation mapped : mapping.relations()) {
            String sourceKey = key(row, mapped.source().keyColumn());
            String targetKey = key(row, mapped.target().keyColumn());
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
            String stated = stated(row, mapped.validFromColumn(), mapped.validToColumn());
            // 관계의 끝에 적힌 개체는 없으면 만든다 (D2 AD-59).
            plan.entities.put(source.id(), source);
            plan.entities.put(target.id(), target);
            List<String> about = List.of(source.entityType(), source.entityKey(), target.entityType(),
                    target.entityKey(), mapped.relationType());
            String fact = LinkOrder.join(concat("R", about, stated));
            plan.facts.add(fact);
            plan.statements.add(new Statement(plan, ItemOutput.Kind.RELATION, LinkOrder.join(concat("R", about)),
                    LinkOrder.join(concat(source.packId(), about)), hash(fact), "", stated, period, source, target,
                    mapped.relationType()));
        }
        return plan;
    }

    private static List<String> concat(String head, List<String> middle, String... tail) {
        List<String> all = new ArrayList<>();
        all.add(head);
        all.addAll(middle);
        all.addAll(List.of(tail));
        return all;
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

    /**
     * 유효기간의 칸에 적힌 날짜를 읽은 표기로. 빈 칸은 빈 글자다. 판의 적용일로 채우지 않는다: 같은 행이 다음 판에서
     * 적용일만 달라졌다고 바뀐 것으로 잡히면 안 된다 (D2 AD-156).
     */
    private String stated(TableRow row, String fromColumn, String toColumn) {
        LocalDate from = date(row, fromColumn);
        LocalDate to = date(row, toColumn);
        return (from == null ? "" : from.toString()) + "~" + (to == null ? "" : to.toString());
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
     * 항목의 내용 해시: 그 행이 적은 것(개체, 속성 값, 관계)을 읽은 뒤의 표기로 모아 글자 코드 순으로 늘어놓은 것의
     * SHA-256 이다 (D2 AD-188). 매핑이 쓰지 않는 열, 행의 차례, 같은 값의 다른 표기에는 달라지지 않는다.
     * 다른 행과 부딪혀 넣지 못한 값도 그 행이 적은 것이므로 든다. Pack 의 식별자는 넣지 않는다 (유형과 키만).
     */
    private static String contentHash(RowPlan row) {
        TreeSet<String> facts = new TreeSet<>(LinkOrder.STRINGS);
        // 줄은 줄바꿈으로 잇는다. 값 안의 줄바꿈은 역빗금과 n 으로 바꿔, 값 하나가 여러 줄로 읽히지 않게 한다.
        // 이은 글자에서 역빗금 뒤에는 세로 막대나 역빗금만 오므로 헷갈리지 않는다.
        row.facts.forEach(fact -> facts.add(fact.replace("\n", "\\n")));
        row.entities.values()
            .forEach(entity -> facts
                .add(LinkOrder.join(List.of("E", entity.entityType(), entity.entityKey())).replace("\n", "\\n")));
        return hash(String.join("\n", facts));
    }

    private static String hash(String text) {
        return LinkHash.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
