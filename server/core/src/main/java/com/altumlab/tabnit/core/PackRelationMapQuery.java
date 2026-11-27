package com.altumlab.tabnit.core;

import java.sql.ResultSet;
import java.sql.SQLException;
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
 * Pack 의 전체 지도용 조회: 가운데 개체 없이, 기준 날짜에 유효한 관계와 그 관계에 닿은 개체를 돌려준다
 * (카드 A-20, D3 5절, D2 AD-419).
 *
 * <p>개체 하나의 둘레를 보는 {@link RelationMapQuery} 와 따로 있다. 이어진 개체가 한도 이하이면 개체와 관계를
 * 그대로 주고, 넘으면 유형별 묶음만 준다. 묶음의 수는 언제나 준다: 유형마다의 개체 수를 더한 것이 이어진 개체의
 * 수와 같다.
 *
 * <p>기준 날짜와 한도는 반드시 받는다. 이 클래스에는 기본값이 없다. 이 조회는 Pack 의 정의를 모른다: 묶는 기준은
 * 개체에 적힌 유형이다. 그런 Pack 이 있는지도 모른다: 개체가 하나도 없으면 빈 지도다.
 *
 * <p>여러 번 읽는다. 그 사이에 다른 쪽이 저장해도 수가 서로 어긋나지 않도록 읽기 전용 트랜잭션 하나(같은 상태를
 * 되풀이해 읽는 격리 수준)로 묶는다. 수를 세려면 그 Pack 의 유효한 관계를 모두 읽는다 (D5 3절에 잰 값).
 */
public class PackRelationMapQuery {

    /* 유효기간: 시작일 포함, 종료일 제외. 종료일이 없으면 계속 유효하다 (D3 4절). */
    private static final String VALID = "r.valid_from <= ? and (r.valid_to is null or ? < r.valid_to)";

    /* 유효한 관계의 두 끝. 한 개체는 한 번만 센다 (union). */
    private static final String ENDS = """
            select r.source_entity_id as id from relation r where r.pack_id = ? and %s
            union
            select r.target_entity_id from relation r where r.pack_id = ? and %s""".formatted(VALID, VALID);

    private static final String TYPE_GROUPS = """
            select e.entity_type, count(*)
            from (%s) ends join entity e on e.id = ends.id
            group by e.entity_type
            order by e.entity_type collate "C\"""".formatted(ENDS);

    private static final String RELATION_GROUPS = """
            select s.entity_type, r.relation_type, t.entity_type, count(*)
            from relation r
            join entity s on s.id = r.source_entity_id
            join entity t on t.id = r.target_entity_id
            where r.pack_id = ? and %s
            group by s.entity_type, r.relation_type, t.entity_type
            order by s.entity_type collate "C", r.relation_type collate "C", t.entity_type collate "C\"""".formatted(VALID);

    private static final String ENTITY_COUNT = "select count(*) from entity where pack_id = ?";

    private static final String NODES = """
            select e.id, e.entity_type
            from (%s) ends join entity e on e.id = ends.id
            order by e.entity_type collate "C", e.id collate "C\"""".formatted(ENDS);

    private static final String EDGES = """
            select r.id, r.pack_id, r.source_entity_id, r.target_entity_id, r.relation_type,
                   r.valid_from, r.valid_to, r.input_source_id, r.edition_no, r.recorded_at
            from relation r
            where r.pack_id = ? and %s
            order by r.relation_type collate "C", r.source_entity_id collate "C", r.target_entity_id collate "C",
                     r.id collate "C\"""".formatted(VALID);

    private final JdbcTemplate jdbc;

    private final TransactionTemplate snapshot;

    public PackRelationMapQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * Pack 의 전체 지도를 돌려준다.
     *
     * @param packId Pack 의 식별자
     * @param asOf 기준 날짜
     * @param maxNodes 개체 하나하나를 담는 한도. 1 이상. 이어진 개체가 이 수를 넘으면 유형별 묶음만 준다
     * @return 지도. 그 Pack 에 유효한 관계가 없으면 묶음도 노드도 비어 있다
     * @throws IllegalArgumentException 한도가 1 보다 작거나, Pack 의 식별자가 비었거나 쓸 수 없는 글자를 담았거나,
     *         기준 날짜가 받는 범위 밖일 때
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     */
    public PackRelationMap packRelationMap(String packId, LocalDate asOf, int maxNodes) {
        Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
        Identifiers.requireNotBlank(packId, "Pack 의 식별자");
        StorableDates.requireAsOf(asOf);
        if (maxNodes < 1) {
            throw new IllegalArgumentException("노드의 한도는 1 이상이어야 합니다. 받은 값: " + maxNodes + ".");
        }
        try {
            return snapshot.execute(status -> read(packId, asOf, maxNodes));
        }
        catch (DataAccessException | TransactionException e) {
            throw new CoreStorageException("Pack 의 전체 지도를 읽지 못했습니다. Pack: " + packId + ", 기준 날짜: " + asOf
                    + ". 저장소의 상태를 확인하세요.", e);
        }
    }

    private PackRelationMap read(String packId, LocalDate asOf, int maxNodes) {
        List<PackRelationMap.TypeGroup> typeGroups = jdbc.query(TYPE_GROUPS,
                (rs, n) -> new PackRelationMap.TypeGroup(rs.getString(1), rs.getInt(2)), packId, asOf, asOf, packId, asOf,
                asOf);
        List<PackRelationMap.RelationGroup> relationGroups = jdbc.query(RELATION_GROUPS,
                (rs, n) -> new PackRelationMap.RelationGroup(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                packId, asOf, asOf);
        int connected = typeGroups.stream().mapToInt(PackRelationMap.TypeGroup::entityCount).sum();
        int relations = relationGroups.stream().mapToInt(PackRelationMap.RelationGroup::relationCount).sum();
        Integer entities = jdbc.queryForObject(ENTITY_COUNT, Integer.class, packId);
        int isolated = (entities == null ? 0 : entities) - connected;
        if (connected > maxNodes) {
            return new PackRelationMap(connected, isolated, relations, true, typeGroups, relationGroups, List.of(), List.of());
        }
        List<PackRelationMap.Node> nodes = jdbc.query(NODES,
                (rs, n) -> new PackRelationMap.Node(rs.getString(1), rs.getString(2)), packId, asOf, asOf, packId, asOf, asOf);
        List<Relation> edges = jdbc.query(EDGES, PackRelationMapQuery::toRelation, packId, asOf, asOf);
        return new PackRelationMap(connected, isolated, relations, false, typeGroups, relationGroups, nodes, edges);
    }

    private static Relation toRelation(ResultSet rs, int rowNum) throws SQLException {
        return new Relation(rs.getString("id"), rs.getString("pack_id"), rs.getString("source_entity_id"),
                rs.getString("target_entity_id"), rs.getString("relation_type"),
                new ValidityPeriod(rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class)),
                new Provenance(rs.getString("input_source_id"), rs.getInt("edition_no")),
                rs.getTimestamp("recorded_at").toInstant());
    }
}
