package com.altumlab.tabnit.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 속성 값의 시점 조회: 기준 날짜에 유효한 값만 돌려준다 (D5 3절, D2 AD-39).
 *
 * <p>기준 날짜는 반드시 받는다. 이 클래스에는 "오늘"이라는 기본값이 없다.
 * 기준 날짜에 유효한 값이 없으면 값이 없는 것이다. 앞의 값을 끌어다 쓰지 않는다 (D2 AD-55).
 */
public class AttributeValueQuery {

    /*
     * 유효기간: 시작일 포함, 종료일 제외. 종료일이 없으면 계속 유효하다 (D3 4절).
     * 같은 개체의 같은 속성은 유효기간이 겹치지 않으므로 속성마다 많아야 한 행이다 (D2 AD-53).
     * 순서: 속성의 이름, 식별자. 글자 코드 순이다.
     */
    private static final String VALUES_AS_OF = """
            select id, pack_id, entity_id, attribute_name, value,
                   valid_from, valid_to, input_source_id, edition_no, recorded_at
            from attribute_value
            where entity_id = ?
              and valid_from <= ?
              and (valid_to is null or ? < valid_to)
            order by attribute_name collate "C", id collate "C\"""";

    private static final String VALUE_AS_OF = """
            select id, pack_id, entity_id, attribute_name, value,
                   valid_from, valid_to, input_source_id, edition_no, recorded_at
            from attribute_value
            where entity_id = ? and attribute_name = ?
              and valid_from <= ?
              and (valid_to is null or ? < valid_to)
            order by id collate "C\"""";

    private final JdbcTemplate jdbc;

    public AttributeValueQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
    }

    /**
     * 개체 하나의 속성 값 가운데 기준 날짜에 유효한 것을 돌려준다.
     *
     * @return 유효한 값. 속성의 이름 순. 없으면 빈 목록
     * @throws EntityNotFoundException 그런 개체가 없을 때
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     */
    public List<AttributeValue> valuesAsOf(String entityId, LocalDate asOf) {
        Identifiers.requireNotBlank(entityId, "개체의 식별자");
        requireStorable(asOf);
        try {
            requireEntity(entityId);
            return jdbc.query(VALUES_AS_OF, AttributeValueQuery::toValue, entityId, asOf, asOf);
        }
        catch (DataAccessException e) {
            throw failed(entityId, asOf, e);
        }
    }

    /**
     * 개체 하나의 속성 하나의 값 가운데 기준 날짜에 유효한 것을 돌려준다.
     *
     * @return 유효한 값. 없으면 비어 있다 ("값 없음")
     * @throws EntityNotFoundException 그런 개체가 없을 때
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     */
    public Optional<AttributeValue> valueAsOf(String entityId, String attributeName, LocalDate asOf) {
        Identifiers.requireNotBlank(entityId, "개체의 식별자");
        Identifiers.requireNotBlank(attributeName, "속성의 이름");
        requireStorable(asOf);
        try {
            requireEntity(entityId);
            return jdbc.query(VALUE_AS_OF, AttributeValueQuery::toValue, entityId, attributeName, asOf, asOf)
                .stream()
                .findFirst();
        }
        catch (DataAccessException e) {
            throw failed(entityId, asOf, e);
        }
    }

    /** 기준 날짜는 저장소가 담을 수 있는 날짜여야 한다. 끝없는 날짜로는 묻지 않는다. */
    private static void requireStorable(LocalDate asOf) {
        Objects.requireNonNull(asOf, "기준 날짜는 반드시 있어야 합니다");
        String fault = StorableDates.fault(asOf, "기준 날짜");
        if (fault != null) {
            throw new IllegalArgumentException(fault);
        }
    }

    private void requireEntity(String entityId) {
        Integer found = jdbc.queryForObject("select count(*) from entity where id = ?", Integer.class, entityId);
        if (found == null || found == 0) {
            throw new EntityNotFoundException(entityId);
        }
    }

    private static CoreStorageException failed(String entityId, LocalDate asOf, DataAccessException e) {
        return new CoreStorageException("속성 값을 조회하지 못했습니다. 개체: " + entityId + ", 기준 날짜: " + asOf
                + ". 값을 확인하고, 문제가 없으면 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
    }

    private static AttributeValue toValue(ResultSet rs, int rowNum) throws SQLException {
        return new AttributeValue(rs.getString("id"), rs.getString("pack_id"), rs.getString("entity_id"),
                rs.getString("attribute_name"), rs.getString("value"),
                new ValidityPeriod(rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class)),
                new Provenance(rs.getString("input_source_id"), rs.getInt("edition_no")),
                rs.getTimestamp("recorded_at").toInstant());
    }
}
