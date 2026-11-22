package com.altumlab.tabnit.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시점 조회: 기준 날짜에 유효한 관계만 돌려준다 (보드 CORE-03, D3 5절).
 *
 * <p>기준 날짜는 반드시 받는다. 이 클래스에는 "오늘"이라는 기본값이 없다.
 */
public class RelationQuery {

    /*
     * 유효기간: 시작일 포함, 종료일 제외. 종료일이 없으면 계속 유효하다 (D3 4절).
     * 순서: 관계 유형, 상대 개체의 식별자, 관계의 식별자. 모두 글자 코드 순이다 (D2 AD-38).
     * 개체가 출발 쪽이든 도착 쪽이든 돌려준다 (D2 AD-29).
     */
    private static final String RELATIONS_AS_OF = """
            select id, pack_id, source_entity_id, target_entity_id, relation_type,
                   valid_from, valid_to, input_source_id, edition_no, recorded_at
            from relation
            where (source_entity_id = ? or target_entity_id = ?)
              and valid_from <= ?
              and (valid_to is null or ? < valid_to)
            order by relation_type collate "C",
                     (case when source_entity_id = ? then target_entity_id else source_entity_id end) collate "C",
                     id collate "C\"""";

    /** 기준 날짜에 유효한 관계의 수. 조건은 {@link #RELATIONS_AS_OF} 와 같다. */
    private static final String COUNT_AS_OF = """
            select count(*)
            from relation
            where (source_entity_id = ? or target_entity_id = ?)
              and valid_from <= ?
              and (valid_to is null or ? < valid_to)""";

    private final JdbcTemplate jdbc;

    /**
     * 나눠 읽을 때 전체 수와 그 쪽을 한 시점의 것으로 읽는다 (읽기 전용, REPEATABLE READ). 부르는 쪽이 이미 트랜잭션
     * 안이면 그 트랜잭션에 들어 그 격리 수준을 따른다: 한 시점을 보장받으려면 트랜잭션 밖에서 부른다.
     */
    private final org.springframework.transaction.support.TransactionTemplate snapshot;

    public RelationQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
        this.snapshot = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot
            .setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 개체 하나의 관계 가운데 기준 날짜에 유효한 것을 돌려준다.
     *
     * @param entityId 개체의 식별자
     * @param asOf 기준 날짜
     * @return 유효한 관계. 없으면 빈 목록
     * @throws EntityNotFoundException 그런 개체가 없을 때
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     */
    public List<Relation> relationsAsOf(String entityId, LocalDate asOf) {
        Objects.requireNonNull(entityId, "개체의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(asOf, "기준 날짜는 반드시 있어야 합니다");
        try {
            Integer found = jdbc.queryForObject("select count(*) from entity where id = ?", Integer.class, entityId);
            if (found == null || found == 0) {
                throw new EntityNotFoundException(entityId);
            }
            return jdbc.query(RELATIONS_AS_OF, RelationQuery::toRelation, entityId, entityId, asOf, asOf, entityId);
        }
        catch (DataAccessException e) {
            throw new CoreStorageException("관계를 조회하지 못했습니다. 개체: " + entityId + ", 기준 날짜: " + asOf
                    + ". 값을 확인하고, 문제가 없으면 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /**
     * 개체 하나의 관계 가운데 기준 날짜에 유효한 것을 나눠 돌려준다 (D2 AD-347).
     * 순서는 {@link #relationsAsOf(String, LocalDate)} 와 같다: 나눠 받은 것을 이으면 한 번에 받은 것과 순서까지 같다.
     *
     * @param asOf 기준 날짜
     * @param offset 건너뛸 수. 0 이상. 전체 수와 같거나 크면 빈 목록이다
     * @param limit 한 번에 받을 수. 1 이상. 위쪽 한도는 부르는 쪽이 정한다
     * @return 그 쪽의 관계와, 나누기 전의 전체 수
     * @throws EntityNotFoundException 그런 개체가 없을 때
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     */
    public RelationPage relationsAsOf(String entityId, LocalDate asOf, int offset, int limit) {
        Objects.requireNonNull(entityId, "개체의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(asOf, "기준 날짜는 반드시 있어야 합니다");
        if (offset < 0) {
            throw new IllegalArgumentException("건너뛸 수는 0 이상이어야 합니다. 받은 값: " + offset + ".");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("한 번에 받을 수는 1 이상이어야 합니다. 받은 값: " + limit + ".");
        }
        try {
            // 전체 수와 그 쪽은 한 시점의 것으로 읽는다. 따로 읽으면 그 사이에 관계가 늘어 수와 목록이 어긋날 수 있다.
            return snapshot.execute(status -> {
                Integer found = jdbc.queryForObject("select count(*) from entity where id = ?", Integer.class, entityId);
                if (found == null || found == 0) {
                    throw new EntityNotFoundException(entityId);
                }
                Integer total = jdbc.queryForObject(COUNT_AS_OF, Integer.class, entityId, entityId, asOf, asOf);
                List<Relation> relations = jdbc.query(RELATIONS_AS_OF + "\nlimit ? offset ?", RelationQuery::toRelation,
                        entityId, entityId, asOf, asOf, entityId, limit, offset);
                return new RelationPage(relations, total == null ? 0 : total);
            });
        }
        catch (DataAccessException | org.springframework.transaction.TransactionException e) {
            throw new CoreStorageException("관계를 조회하지 못했습니다. 개체: " + entityId + ", 기준 날짜: " + asOf
                    + ". 값을 확인하고, 문제가 없으면 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    private static Relation toRelation(ResultSet rs, int rowNum) throws SQLException {
        return new Relation(rs.getString("id"), rs.getString("pack_id"), rs.getString("source_entity_id"),
                rs.getString("target_entity_id"), rs.getString("relation_type"),
                new ValidityPeriod(rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class)),
                new Provenance(rs.getString("input_source_id"), rs.getInt("edition_no")),
                rs.getTimestamp("recorded_at").toInstant());
    }
}
