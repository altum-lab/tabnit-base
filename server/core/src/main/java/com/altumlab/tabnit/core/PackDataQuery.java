package com.altumlab.tabnit.core;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pack 하나의 자료를 기준 날짜로 통째로 읽는다 (D2 AD-391). 읽기만 한다.
 *
 * <p>개체는 Pack 의 것 전부다: 개체에는 유효기간이 없다 (D2 AD-218). 관계와 속성 값은 기준 날짜에 유효한 것만이다
 * (시작일 포함, 종료일 제외. 종료일이 없으면 계속 유효하다). 이 모듈은 읽은 것이 어디에 쓰이는지 모른다.
 *
 * <p>세 가지를 한 시점의 것으로 읽는다 (읽기 전용, REPEATABLE READ). 부르는 쪽이 이미 트랜잭션 안이면 그 트랜잭션에
 * 들어 그 격리 수준을 따른다: 한 시점을 보장받으려면 트랜잭션 밖에서 부른다.
 */
public class PackDataQuery {

    private static final String AS_OF = " and valid_from <= ? and (valid_to is null or ? < valid_to)";

    private static final String ENTITIES = """
            select id, pack_id, entity_type, entity_key
            from entity
            where pack_id = ?
            order by id collate "C\"""";

    private static final String RELATIONS = """
            select id, pack_id, source_entity_id, target_entity_id, relation_type,
                   valid_from, valid_to, input_source_id, edition_no, recorded_at
            from relation
            where pack_id = ?""" + AS_OF + """

            order by id collate "C\"""";

    private static final String VALUES = """
            select id, pack_id, entity_id, attribute_name, value,
                   valid_from, valid_to, input_source_id, edition_no, recorded_at
            from attribute_value
            where pack_id = ?""" + AS_OF + """

            order by entity_id collate "C", attribute_name collate "C", valid_from, id collate "C\"""";

    private final JdbcTemplate jdbc;

    private final TransactionTemplate snapshot;

    public PackDataQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * Pack 의 개체 전부와, 기준 날짜에 유효한 관계와 속성 값을 읽는다.
     *
     * @param packId Pack 의 식별자. 없는 Pack 이면 빈 자료다 (이 모듈은 Pack 의 정의를 모른다)
     * @param asOf 기준 날짜
     * @param maxRows 한 번에 읽을 행의 한도 (개체, 관계, 속성 값의 수를 더한 것). 1 이상. 넘으면 자료를 주지 않고 수만 준다
     * @return 읽은 자료. 한도를 넘었으면 {@link PackData#complete()} 가 거짓이고 목록은 비어 있다
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     * @throws IllegalArgumentException 기준 날짜가 받는 범위(0001-01-01 ~ 9999-12-31) 밖이거나, Pack 의 식별자가 비었거나
     *         쓸 수 없는 글자(NUL, 짝 없는 대리 문자)를 담았거나, 한도가 1 보다 작을 때
     */
    public PackData packDataAsOf(String packId, LocalDate asOf, int maxRows) {
        Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(asOf, "기준 날짜는 반드시 있어야 합니다");
        Identifiers.requireNotBlank(packId, "Pack 의 식별자");
        StorableDates.requireAsOf(asOf);
        if (maxRows < 1) {
            throw new IllegalArgumentException("한 번에 읽을 행의 한도는 1 이상이어야 합니다. 받은 값: " + maxRows + ".");
        }
        try {
            return snapshot.execute(status -> read(packId, asOf, maxRows));
        }
        catch (DataAccessException | TransactionException e) {
            throw new CoreStorageException("Pack " + packId + " 의 자료를 기준 날짜 " + asOf
                    + " 로 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    private PackData read(String packId, LocalDate asOf, int maxRows) {
        // 먼저 센다. 한도를 넘으면 읽지 않는다.
        int entityCount = count("select count(*) from entity where pack_id = ?", packId);
        int relationCount = count("select count(*) from relation where pack_id = ?" + AS_OF, packId, asOf, asOf);
        int valueCount = count("select count(*) from attribute_value where pack_id = ?" + AS_OF, packId, asOf, asOf);
        if ((long) entityCount + relationCount + valueCount > maxRows) {
            return new PackData(packId, asOf, List.of(), List.of(), List.of(), entityCount, relationCount, valueCount, false);
        }
        List<EntityRef> entities = jdbc.query(ENTITIES, EntityQuery::toEntity, packId);
        List<Relation> relations = jdbc.query(RELATIONS, RelationQuery::toRelation, packId, asOf, asOf);
        List<AttributeValue> values = jdbc.query(VALUES, AttributeValueQuery::toValue, packId, asOf, asOf);
        return new PackData(packId, asOf, entities, relations, values, entityCount, relationCount, valueCount, true);
    }

    private int count(String sql, Object... arguments) {
        Integer found = jdbc.queryForObject(sql, Integer.class, arguments);
        return found == null ? 0 : found;
    }
}
