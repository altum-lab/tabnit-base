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
import com.altumlab.tabnit.link.EditionPlan.PreviousItem;
import com.altumlab.tabnit.pack.MappedAttribute;
import com.altumlab.tabnit.pack.MappedEntity;
import com.altumlab.tabnit.pack.MappedRelation;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.MappingFileFormat;
import com.altumlab.tabnit.pack.PackDefinition;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 매핑 실행: 입력 파일의 행을 Pack 의 매핑 정의에 따라 개체, 속성 값, 관계로 바꿔 core 에 넣는다 (보드 C-03, D6 7절).
 *
 * <p>유효기간과 출처를 붙인다. 판 번호와 적용일은 부르는 쪽이 넘긴다 (D2 AD-173). 이 클래스는 현재 시각을 읽지 않는다.
 * 저장은 core 의 공개 API 로만 한다. 무엇을 넣고 끝낼지는 {@link EditionPlanner} 가 정하고, 여기서는 그것을
 * 정해진 차례로 core 에 적용한다.
 */
public class MappingExecutor {

    /** 키 열의 값 하나와, 이은 항목의 키가 가질 수 있는 가장 긴 길이. 글자(코드 포인트)로 센다 (D2 AD-177, AD-187). */
    static final int MAX_KEY_LENGTH = EditionPlanner.MAX_KEY_LENGTH;

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
     * 파일 하나를 앞 판 없이 매핑에 따라 넣는다. 모두 새로 넣는다. 실행 하나는 트랜잭션 하나다.
     * 판으로 남기고 앞 판과 비교하는 투입은 {@link EditionIngest} 가 한다.
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
        Objects.requireNonNull(recordedAt, "기록한 때는 반드시 있어야 합니다");
        EditionPlan plan = plan(pack, inputSourceId, content, editionNo, effectiveDate, null);
        try {
            transaction.executeWithoutResult(status -> apply(plan, inputSourceId, editionNo, recordedAt));
        }
        catch (TransactionException e) {
            throw new MappingExecutionException("입력 자료 " + inputSourceId + " 의 " + editionNo
                    + "번째 판을 저장하지 못했습니다. 이 실행에서 넣은 것은 남지 않았습니다. 까닭: " + e.getMessage(), e);
        }
        return plan.outcome;
    }

    /**
     * 파일을 읽어 계획을 세운다. 저장소에 닿지 않는다.
     *
     * @param previousItems 바로 앞 판의 항목. 앞 판이 없으면 null
     * @throws TableFileRejectedException 파일 수준의 문제이거나, 앞 판의 것을 끝낼 수 없어 판을 거부할 때
     */
    EditionPlan plan(PackDefinition pack, String inputSourceId, byte[] content, int editionNo, LocalDate effectiveDate,
            List<PreviousItem> previousItems) {
        Objects.requireNonNull(pack, "Pack 의 정의는 반드시 있어야 합니다");
        Objects.requireNonNull(inputSourceId, "입력 자료의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(content, "파일의 내용은 반드시 있어야 합니다");
        Objects.requireNonNull(effectiveDate, "적용일은 반드시 있어야 합니다");
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
        TableFile file = CsvTableReader.read(content);
        requireColumns(mapping, file);
        return new EditionPlanner(pack, mapping, file, new Provenance(inputSourceId, editionNo), effectiveDate,
                previousItems).plan();
    }

    /**
     * 계획을 정해진 차례로 core 에 적용한다: 끝낼 관계, 끝낼 속성 값, 개체, 속성 값, 관계. 각각 식별자 순이다.
     * 앞 값을 먼저 끝내야 새 값이 겹치지 않는다 (D2 AD-163). 부르는 쪽의 트랜잭션 안에서 부른다.
     *
     * @throws MappingExecutionException 저장소가 받지 않았을 때
     */
    void apply(EditionPlan plan, String inputSourceId, int editionNo, Instant recordedAt) {
        try {
            for (String relationId : plan.relationsToEnd) {
                relationStore.endRelation(relationId, plan.effectiveDate);
            }
            for (String attributeValueId : plan.attributeValuesToEnd) {
                attributeValueStore.endAttributeValue(attributeValueId, plan.effectiveDate);
            }
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
        catch (RelationRejectedException | AttributeValueRejectedException | CoreStorageException
                | IllegalArgumentException e) {
            throw new MappingExecutionException("입력 자료 " + inputSourceId + " 의 " + editionNo
                    + "번째 판을 저장하지 못했습니다. 이 실행에서 넣은 것은 남지 않았습니다. 까닭: " + e.getMessage(), e);
        }
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
            throw new TableFileRejectedException("MISSING_COLUMN", String.join(", ", missing), "파일에 있어야 하는 열이 없습니다: "
                    + String.join(", ", missing) + ". 파일의 열 이름을 확인하거나, 열 이름이 바뀌었으면 매핑을 고친 뒤 다시 넣으세요.");
        }
    }

    private static void addIfPresent(TreeSet<String> columns, String column) {
        if (column != null) {
            columns.add(column);
        }
    }
}
