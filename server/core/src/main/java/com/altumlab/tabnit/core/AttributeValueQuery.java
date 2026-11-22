package com.altumlab.tabnit.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 속성 값의 시점 조회: 기준 날짜에 유효한 값만 돌려준다 (D5 3절, D2 AD-39).
 *
 * <p>기준 날짜는 반드시 받는다. 이 클래스에는 "오늘"이라는 기본값이 없다.
 * 기준 날짜에 유효한 값이 없으면 값이 없는 것이다. 앞의 값을 끌어다 쓰지 않는다 (D2 AD-55).
 *
 * <p>하나만 다르다: {@link #valuesInEffectFrom} 은 한 날짜에 유효한 값이 아니라 어느 날부터 유효한 값을 모두 준다.
 * 넣기 전에 기간이 겹치는지 가리는 데만 쓴다. 화면, API, 규칙의 조건은 쓰지 않는다 (D2 AD-344).
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

    private static final String VALUES_OF_MANY_AS_OF = """
            select id, pack_id, entity_id, attribute_name, value,
                   valid_from, valid_to, input_source_id, edition_no, recorded_at
            from attribute_value
            where entity_id = any(?)
              and valid_from <= ?
              and (valid_to is null or ? < valid_to)
            order by entity_id collate "C", attribute_name collate "C", id collate "C\"""";

    /** 개체 여럿을 한 번에 묻는 수. */
    private static final int IN_EFFECT_BATCH = 1000;

    private static final String VALUES_IN_EFFECT_FROM = """
            select id, pack_id, entity_id, attribute_name, value,
                   valid_from, valid_to, input_source_id, edition_no, recorded_at
            from attribute_value
            where entity_id = any(?)
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

    /**
     * 개체 여럿의 속성 값 가운데 기준 날짜에 유효한 것을 한 번에 돌려준다 (D2 AD-349). 개체 하나를 읽는
     * {@link #valuesAsOf(String, LocalDate)} 와 같은 시점 조회다: 시작일 포함, 종료일 제외.
     * 같은 개체의 같은 속성은 많아야 한 줄이다 (D2 AD-53).
     *
     * @param entityIds 개체의 식별자. 차례와 겹침은 결과에 닿지 않는다. 값이 없는 개체는 줄이 없다
     * @param asOf 기준 날짜
     * @return 유효한 값. 개체의 식별자, 속성의 이름, 값의 식별자 순 (글자 코드 순). 없으면 빈 목록
     * @throws EntityNotFoundException 그런 개체가 없을 때. 개체 하나를 읽는 조회와 같다 (D3 2절). 없는 것이
     *         여럿이면 식별자의 글자 코드 순으로 첫째를 알린다
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     */
    public List<AttributeValue> valuesAsOf(Collection<String> entityIds, LocalDate asOf) {
        Objects.requireNonNull(entityIds, "개체의 식별자 목록은 반드시 있어야 합니다");
        requireStorable(asOf);
        TreeSet<String> ids = new TreeSet<>(CodePointOrder.STRINGS);
        for (String entityId : entityIds) {
            Identifiers.requireNotBlank(entityId, "개체의 식별자");
            ids.add(entityId);
        }
        List<String> ordered = List.copyOf(ids);
        List<AttributeValue> found = new ArrayList<>();
        try {
            for (int start = 0; start < ordered.size(); start += IN_EFFECT_BATCH) {
                List<String> batch = ordered.subList(start, Math.min(start + IN_EFFECT_BATCH, ordered.size()));
                // 없는 개체의 값을 묻는 것은 "값 없음"이 아니라 잘못 물은 것이다. 조용히 넘기지 않는다.
                TreeSet<String> known = new TreeSet<>(CodePointOrder.STRINGS);
                jdbc.query("select id from entity where id = any(?)",
                        ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", batch.toArray())),
                        rs -> {
                            known.add(rs.getString("id"));
                        });
                for (String entityId : batch) {
                    if (!known.contains(entityId)) {
                        throw new EntityNotFoundException(entityId);
                    }
                }
                found.addAll(jdbc.query(VALUES_OF_MANY_AS_OF, ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("text", batch.toArray()));
                    ps.setObject(2, asOf);
                    ps.setObject(3, asOf);
                }, AttributeValueQuery::toValue));
            }
        }
        catch (DataAccessException e) {
            throw new CoreStorageException("속성 값을 조회하지 못했습니다. 개체 " + ordered.size() + "개, 기준 날짜: " + asOf
                    + ". 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
        // 묶음은 개체의 식별자 순이고 묶음 안은 저장소가 같은 순서로 준다. 그래도 순서를 저장소의 정렬 규칙에
        // 맡겨 두지 않고 여기서 한 번 더 정한다. 같은 개체의 같은 속성은 많아야 하나지만 값의 식별자까지 견준다.
        found.sort(Comparator.comparing(AttributeValue::entityId, CodePointOrder.STRINGS)
            .thenComparing(AttributeValue::attributeName, CodePointOrder.STRINGS)
            .thenComparing(AttributeValue::id, CodePointOrder.STRINGS));
        return List.copyOf(found);
    }

    /**
     * 개체들의 속성 값 가운데 어느 날부터 하루라도 유효한 것을 돌려준다: 종료일이 없거나 그날보다 뒤인 것이다.
     * 그날 뒤에 시작하는 것도 든다. 새로 넣을 값이 이미 있는 값과 기간이 겹치는지 넣기 전에 가리는 데 쓴다
     * (D2 AD-344). 겹침은 출처를 보지 않으므로 (D2 AD-53) 출처를 가리지 않고 돌려준다.
     *
     * @param entityIds 개체의 식별자. 차례와 겹침은 결과에 닿지 않는다. 아직 없는 개체는 값도 없다
     * @param from 이날부터 본다. 시점 조회의 기준 날짜와 달리 기간의 시작이다. 반드시 받는다
     * @return 값. 식별자의 글자 코드 순. 없으면 빈 목록
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     */
    public List<AttributeValue> valuesInEffectFrom(Collection<String> entityIds, LocalDate from) {
        Objects.requireNonNull(entityIds, "개체의 식별자 목록은 반드시 있어야 합니다");
        requireStorable(from);
        TreeSet<String> ids = new TreeSet<>(CodePointOrder.STRINGS);
        for (String entityId : entityIds) {
            Identifiers.requireNotBlank(entityId, "개체의 식별자");
            ids.add(entityId);
        }
        // 한 번에 묻는 개체의 수를 정해 둔다. 묶음은 개체의 식별자 순이고 답은 값의 식별자 순이라, 이은 것이
        // 값의 식별자 순이라는 보장이 없다. 마지막에 값의 식별자로 다시 정렬한다.
        List<String> ordered = List.copyOf(ids);
        List<AttributeValue> found = new ArrayList<>();
        try {
            for (int start = 0; start < ordered.size(); start += IN_EFFECT_BATCH) {
                List<String> batch = ordered.subList(start, Math.min(start + IN_EFFECT_BATCH, ordered.size()));
                found.addAll(jdbc.query(VALUES_IN_EFFECT_FROM, ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("text", batch.toArray()));
                    ps.setObject(2, from);
                }, AttributeValueQuery::toValue));
            }
        }
        catch (DataAccessException e) {
            throw new CoreStorageException("속성 값을 조회하지 못했습니다. 개체 " + ordered.size() + "개, 기준 날짜: " + from
                    + ". 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
        found.sort(Comparator.comparing(AttributeValue::id, CodePointOrder.STRINGS));
        return List.copyOf(found);
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
