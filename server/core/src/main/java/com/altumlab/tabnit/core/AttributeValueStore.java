package com.altumlab.tabnit.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 속성 값을 유효기간, 출처와 함께 저장하고, 열려 있는 값을 끝낸다 (D5 3절).
 *
 * <p>기록한 때는 밖에서 받는다. 이 클래스는 현재 시각을 읽지 않는다. 값을 지우지 않는다.
 * 저장은 받은 값을 그대로 넣는다. 다른 행을 고치지 않는다. 값이 바뀔 때 앞 값을 끝내는 것은 부르는 쪽이 한다 (D2 AD-163).
 */
public class AttributeValueStore {

    private final JdbcTemplate jdbc;

    public AttributeValueStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
    }

    /**
     * 속성 값을 저장하고 식별자를 돌려준다. 같은 값(식별자, 값, 종료일, 출처가 모두 같은 것)이 이미 있으면 그대로 둔다.
     *
     * @throws AttributeValueRejectedException 없는 개체의 값이거나, 같은 개체의 같은 속성에 유효기간이 겹치는 값이 있을 때.
     *         겹침은 출처를 보지 않는다 (D2 AD-53)
     * @throws CoreStorageException 저장소에 쓰지 못했을 때
     */
    public String saveAttributeValue(NewAttributeValue value, Instant recordedAt) {
        Objects.requireNonNull(value, "속성 값은 반드시 있어야 합니다");
        Objects.requireNonNull(recordedAt, "기록한 때는 반드시 있어야 합니다");
        ValidityPeriod period = value.period();
        // 저장소가 담지 못하는 날짜와 시각은 저장소까지 보내지 않고 여기서 거부한다.
        for (String fault : new String[] { StorableDates.fault(period.from(), "시작일"),
                StorableDates.fault(period.to(), "종료일"), StorableDates.fault(recordedAt) }) {
            if (fault != null) {
                throw new AttributeValueRejectedException(fault);
            }
        }
        String id = value.id();
        String entityId = value.entity().id();
        try {
            Integer found = jdbc.queryForObject("select count(*) from entity where id = ?", Integer.class, entityId);
            if (found == null || found == 0) {
                throw new AttributeValueRejectedException("개체가 없습니다: " + entityId
                        + ". 개체를 먼저 저장하거나 식별자(Pack, 개체 유형, 키)를 확인하세요.");
            }
            return insert(value, id, entityId, recordedAt);
        }
        catch (DataAccessException e) {
            throw new CoreStorageException("속성 값을 저장하지 못했습니다: " + id + ". 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    private String insert(NewAttributeValue value, String id, String entityId, Instant recordedAt) {
        ValidityPeriod period = value.period();
        Provenance provenance = value.provenance();
        // 넣기와 "이미 있는가, 겹치는가"의 판단을 문장 하나로 한다 (관계의 저장과 같은 방식).
        // 0 행이면 반드시 아래에서 까닭을 가린다. 조용히 넘어가는 경우는 없다.
        int inserted;
        try {
            inserted = jdbc.update("""
                    insert into attribute_value (id, pack_id, entity_id, attribute_name, value,
                                                 valid_from, valid_to, input_source_id, edition_no, recorded_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict do nothing""", id, value.entity().packId(), entityId, value.attributeName(),
                    value.value(), period.from(), period.to(), provenance.inputSourceId(), provenance.editionNo(),
                    Timestamp.from(recordedAt));
        }
        catch (DataIntegrityViolationException e) {
            throw new AttributeValueRejectedException(
                    "속성 값을 저장하지 못했습니다: " + id + ". 저장소의 제약에 걸렸습니다. 관리자에게 이 식별자와 함께 알려 주세요.", e);
        }
        if (inserted == 1) {
            return id;
        }

        // 들어가지 않았다. 같은 값이 이미 있으면 다시 저장한 것이므로 그대로 둔다.
        List<String> existing = jdbc.query("""
                select case when value = ? and valid_to is not distinct from ?
                                 and input_source_id = ? and edition_no = ? then ''
                            else concat_ws(', ',
                                 case when value <> ? then '값' end,
                                 case when valid_to is distinct from ? then '종료일' end,
                                 case when input_source_id <> ? or edition_no <> ? then '출처' end) end
                from attribute_value where id = ?""", (rs, n) -> rs.getString(1), value.value(), period.to(),
                provenance.inputSourceId(), provenance.editionNo(), value.value(), period.to(),
                provenance.inputSourceId(), provenance.editionNo(), id);
        if (!existing.isEmpty() && existing.get(0).isEmpty()) {
            return id;
        }
        if (!existing.isEmpty()) {
            // 같은 개체, 같은 속성, 같은 시작일의 값이 이미 다른 내용으로 있다. 앞 값을 끝내도 들어가지 않는다.
            throw new AttributeValueRejectedException("같은 개체의 같은 속성에 같은 시작일의 값이 이미 다른 내용으로 있습니다: " + id
                    + ". 다른 것: " + existing.get(0) + ". 한 번 저장한 값은 바꾸지 않습니다. 시작일을 달리해 새 값으로 저장하세요.");
        }

        // 아니면 같은 개체의 같은 속성에 유효기간이 겹치는 값이 있다. 무엇과 겹치는지 찾아 알린다.
        List<String> overlapping = jdbc.query("""
                select id, valid_from, valid_to from attribute_value
                where entity_id = ? and attribute_name = ?
                  and daterange(valid_from, valid_to, '[)') && daterange(?, ?, '[)')
                order by id collate "C\"""",
                (rs, n) -> rs.getString(1) + " ("
                        + describe(rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class)) + ")",
                entityId, value.attributeName(), period.from(), period.to());
        throw new AttributeValueRejectedException("같은 개체의 같은 속성에 유효기간이 겹치는 값이 있습니다. 저장하려는 기간: "
                + describe(period.from(), period.to()) + "."
                + (overlapping.isEmpty() ? "" : " 겹치는 값: " + String.join(", ", overlapping) + ".")
                + " 앞 값을 먼저 끝내거나 기간을 고쳐 다시 보내세요.");
    }

    /**
     * 열려 있는 속성 값에 종료일을 넣어 끝낸다 (D2 AD-162).
     *
     * <p>이미 같은 날짜로 끝나 있으면 아무것도 바꾸지 않는다. 한 번 넣은 종료일은 바꾸지 않는다.
     *
     * @param attributeValueId 속성 값의 식별자
     * @param endDate 종료일. 이 날부터 유효하지 않다. 시작일보다 뒤여야 한다
     * @throws AttributeValueRejectedException 그런 값이 없거나, 종료일이 시작일보다 뒤가 아니거나, 이미 다른 날짜로 끝나 있을 때
     * @throws CoreStorageException 저장소에 쓰지 못했을 때
     */
    public void endAttributeValue(String attributeValueId, LocalDate endDate) {
        Identifiers.requireNotBlank(attributeValueId, "속성 값의 식별자");
        Objects.requireNonNull(endDate, "종료일은 반드시 있어야 합니다");
        String fault = PeriodEnd.ATTRIBUTE_VALUE.end(jdbc, attributeValueId, endDate);
        if (fault != null) {
            throw new AttributeValueRejectedException(fault);
        }
    }

    private static String describe(LocalDate from, LocalDate to) {
        return from + " ~ " + (to == null ? "종료일 없음" : to.toString());
    }
}
