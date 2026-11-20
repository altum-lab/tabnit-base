package com.altumlab.tabnit.core;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 개체와 관계를 유효기간, 출처와 함께 저장한다 (보드 CORE-01, D5 3절).
 *
 * <p>기록한 때는 밖에서 받는다. 이 클래스는 현재 시각을 읽지 않는다.
 * 관계를 지우지 않는다. 같은 것을 다시 저장해도 행이 늘지 않는다.
 */
public class RelationStore {

    /** PostgreSQL 의 배제 제약 위반. 같은 관계의 유효기간이 겹칠 때 난다 (D2 AD-67). */
    private static final String EXCLUSION_VIOLATION = "23P01";

    private final JdbcTemplate jdbc;

    public RelationStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
    }

    /**
     * 개체를 저장하고 식별자를 돌려준다. 이미 있는 개체면 그대로 두고, 처음 기록한 때를 바꾸지 않는다.
     */
    public String saveEntity(EntityRef entity, Instant recordedAt) {
        Objects.requireNonNull(entity, "개체는 반드시 있어야 합니다");
        Objects.requireNonNull(recordedAt, "기록한 때는 반드시 있어야 합니다");
        String id = entity.id();
        jdbc.update("""
                insert into entity (id, pack_id, entity_type, entity_key, recorded_at)
                values (?, ?, ?, ?, ?)
                on conflict (id) do nothing""", id, entity.packId(), entity.entityType(), entity.entityKey(),
                Timestamp.from(recordedAt));
        return id;
    }

    /**
     * 관계를 저장하고 식별자를 돌려준다. 같은 관계(식별자와 종료일이 같은 것)가 이미 있으면 그대로 둔다.
     *
     * @throws RelationRejectedException 없는 개체를 가리키거나, 출발과 도착이 같거나, 서로 다른 Pack 의 개체를 잇거나,
     *         같은 관계의 유효기간이 겹칠 때
     */
    public String saveRelation(NewRelation relation, Instant recordedAt) {
        Objects.requireNonNull(relation, "관계는 반드시 있어야 합니다");
        Objects.requireNonNull(recordedAt, "기록한 때는 반드시 있어야 합니다");
        EntityRef source = relation.source();
        EntityRef target = relation.target();
        String sourceId = source.id();
        String targetId = target.id();

        if (!source.packId().equals(target.packId())) {
            throw new RelationRejectedException("관계의 두 개체는 같은 Pack 에 있어야 합니다. 출발 개체: " + sourceId
                    + ", 도착 개체: " + targetId + ". 같은 Pack 의 개체로 고쳐 다시 보내세요.");
        }
        if (sourceId.equals(targetId)) {
            throw new RelationRejectedException(
                    "출발 개체와 도착 개체가 같은 관계는 저장할 수 없습니다: " + sourceId + ". 입력 자료에서 두 개체를 확인하세요.");
        }
        requireEntity(sourceId, "출발 개체");
        requireEntity(targetId, "도착 개체");

        String id = relation.id();
        ValidityPeriod period = relation.period();
        Provenance provenance = relation.provenance();

        // 넣기와 "이미 있는가"의 판단을 문장 하나로 한다. 같은 식별자를 두 곳에서 동시에 저장해도
        // 한쪽만 들어가고, 다른 쪽은 아래에서 이미 있는 행과 견주어 같은 답을 얻는다.
        int inserted;
        try {
            inserted = jdbc.update("""
                    insert into relation (id, pack_id, source_entity_id, target_entity_id, relation_type,
                                          valid_from, valid_to, input_source_id, edition_no, recorded_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (id) do nothing""", id, source.packId(), sourceId, targetId,
                    relation.relationType(), period.from(), period.to(), provenance.inputSourceId(),
                    provenance.editionNo(), Timestamp.from(recordedAt));
        }
        catch (DataIntegrityViolationException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql
                    && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                throw overlap(relation, sourceId, targetId, e);
            }
            throw new RelationRejectedException(
                    "관계를 저장하지 못했습니다: " + id + ". 저장소의 제약에 걸렸습니다. 관리자에게 이 식별자와 함께 알려 주세요.", e);
        }
        if (inserted == 0) {
            // 같은 식별자(출발, 도착, 유형, 출처, 시작일)의 관계가 이미 있다.
            // 종료일까지 같으면 같은 관계를 다시 저장한 것이므로 그대로 둔다. 다르면 기간이 겹친다.
            List<ValidityPeriod> existing = jdbc.query("select valid_from, valid_to from relation where id = ?",
                    (rs, n) -> new ValidityPeriod(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)),
                    id);
            if (existing.isEmpty() || !existing.get(0).equals(period)) {
                throw overlap(relation, sourceId, targetId, null);
            }
        }
        return id;
    }

    private void requireEntity(String entityId, String role) {
        Integer found = jdbc.queryForObject("select count(*) from entity where id = ?", Integer.class, entityId);
        if (found == null || found == 0) {
            throw new RelationRejectedException(
                    role + "가 없습니다: " + entityId + ". 개체를 먼저 저장하거나 식별자(Pack, 개체 유형, 키)를 확인하세요.");
        }
    }

    /** 무엇과 겹치는지 찾아 오류에 담는다. 데이터베이스의 오류 문구는 내보내지 않는다. */
    private RelationRejectedException overlap(NewRelation relation, String sourceId, String targetId,
            Throwable cause) {
        ValidityPeriod period = relation.period();
        Provenance provenance = relation.provenance();
        List<String> overlapping = jdbc.query("""
                select id, valid_from, valid_to from relation
                where source_entity_id = ? and target_entity_id = ? and relation_type = ?
                  and input_source_id = ? and edition_no = ?
                  and daterange(valid_from, valid_to, '[)') && daterange(?, ?, '[)')
                order by id""",
                (rs, n) -> rs.getString(1) + " ("
                        + describe(rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class)) + ")",
                sourceId, targetId, relation.relationType(), provenance.inputSourceId(), provenance.editionNo(),
                period.from(), period.to());
        String message = "같은 관계의 유효기간이 겹칩니다. 저장하려는 기간: " + describe(period.from(), period.to())
                + ". 겹치는 관계: " + overlapping.stream().collect(Collectors.joining(", "))
                + ". 앞 관계에 종료일을 넣어 끝내거나 기간을 고쳐 다시 보내세요.";
        return cause == null ? new RelationRejectedException(message) : new RelationRejectedException(message, cause);
    }

    private static String describe(LocalDate from, LocalDate to) {
        return from + " ~ " + (to == null ? "종료일 없음" : to.toString());
    }
}
