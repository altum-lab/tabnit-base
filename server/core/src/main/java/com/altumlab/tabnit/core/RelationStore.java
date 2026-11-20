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
 * 개체와 관계를 유효기간, 출처와 함께 저장한다 (보드 CORE-01, D5 3절).
 *
 * <p>기록한 때는 밖에서 받는다. 이 클래스는 현재 시각을 읽지 않는다.
 * 관계를 지우지 않는다. 같은 것을 다시 저장해도 행이 늘지 않는다.
 */
public class RelationStore {

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
        try {
            jdbc.update("""
                    insert into entity (id, pack_id, entity_type, entity_key, recorded_at)
                    values (?, ?, ?, ?, ?)
                    on conflict (id) do nothing""", id, entity.packId(), entity.entityType(), entity.entityKey(),
                    Timestamp.from(recordedAt));
        }
        catch (DataAccessException e) {
            throw new CoreStorageException("개체를 저장하지 못했습니다: " + id + ". 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
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
        ValidityPeriod period = relation.period();
        requireFinite(period);
        String id = relation.id();
        try {
            requireEntity(sourceId, "출발 개체");
            requireEntity(targetId, "도착 개체");
            return insert(relation, id, sourceId, targetId, recordedAt);
        }
        catch (DataAccessException e) {
            throw new CoreStorageException("관계를 저장하지 못했습니다: " + id + ". 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    private String insert(NewRelation relation, String id, String sourceId, String targetId, Instant recordedAt) {
        ValidityPeriod period = relation.period();
        Provenance provenance = relation.provenance();

        // 넣기와 "이미 있는가, 겹치는가"의 판단을 문장 하나로 한다.
        // 대상을 적지 않은 on conflict do nothing 은 기본 키, 유일 제약, 유효기간 겹침(배제 제약) 가운데
        // 어느 것에 걸려도 오류 없이 0 행을 돌려준다. 그래서
        //  - 같은 관계를 두 곳에서 동시에 저장해도 한쪽만 들어가고 다른 쪽은 아래에서 같은 답을 얻는다.
        //  - 오류가 나지 않으므로 호출하는 쪽의 트랜잭션이 깨지지 않고, 무엇과 겹치는지 이어서 조회할 수 있다.
        // 0 행이면 반드시 아래에서 까닭을 가린다. 조용히 넘어가는 경우는 없다.
        int inserted;
        try {
            inserted = jdbc.update("""
                    insert into relation (id, pack_id, source_entity_id, target_entity_id, relation_type,
                                          valid_from, valid_to, input_source_id, edition_no, recorded_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict do nothing""", id, relation.source().packId(), sourceId, targetId,
                    relation.relationType(), period.from(), period.to(), provenance.inputSourceId(),
                    provenance.editionNo(), Timestamp.from(recordedAt));
        }
        catch (DataIntegrityViolationException e) {
            // on conflict 가 다루지 않는 제약(참조, 검사)에 걸렸다. 앞의 확인을 지나온 뒤라 드문 경우다.
            throw new RelationRejectedException(
                    "관계를 저장하지 못했습니다: " + id + ". 저장소의 제약에 걸렸습니다. 관리자에게 이 식별자와 함께 알려 주세요.", e);
        }
        if (inserted == 1) {
            return id;
        }

        // 들어가지 않았다. 같은 관계(식별자와 종료일이 같은 것)가 이미 있으면 다시 저장한 것이므로 그대로 둔다.
        List<ValidityPeriod> sameId = jdbc.query("select valid_from, valid_to from relation where id = ?",
                (rs, n) -> new ValidityPeriod(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)), id);
        if (!sameId.isEmpty() && sameId.get(0).equals(period)) {
            return id;
        }

        // 아니면 같은 관계의 유효기간이 겹친 것이다. 무엇과 겹치는지 찾아 알린다.
        List<String> overlapping = jdbc.query("""
                select id, valid_from, valid_to from relation
                where source_entity_id = ? and target_entity_id = ? and relation_type = ?
                  and input_source_id = ? and edition_no = ?
                  and daterange(valid_from, valid_to, '[)') && daterange(?, ?, '[)')
                order by id collate "C\"""",
                (rs, n) -> rs.getString(1) + " ("
                        + describe(rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class)) + ")",
                sourceId, targetId, relation.relationType(), provenance.inputSourceId(), provenance.editionNo(),
                period.from(), period.to());
        throw overlap(period, overlapping);
    }

    /** 끝없는 날짜는 받지 않는다. 종료일이 없다는 것은 종료일을 비우는 것 하나로만 나타낸다 (D2 AD-75). */
    private static void requireFinite(ValidityPeriod period) {
        if (isEndless(period.from()) || isEndless(period.to())) {
            throw new RelationRejectedException("끝없는 날짜는 유효기간에 쓸 수 없습니다: "
                    + describe(period.from(), period.to()) + ". 종료일이 없으면 종료일을 비워 다시 보내세요.");
        }
    }

    private static boolean isEndless(LocalDate date) {
        return LocalDate.MAX.equals(date) || LocalDate.MIN.equals(date);
    }

    private void requireEntity(String entityId, String role) {
        Integer found = jdbc.queryForObject("select count(*) from entity where id = ?", Integer.class, entityId);
        if (found == null || found == 0) {
            throw new RelationRejectedException(
                    role + "가 없습니다: " + entityId + ". 개체를 먼저 저장하거나 식별자(Pack, 개체 유형, 키)를 확인하세요.");
        }
    }

    /** 무엇과 겹치는지 오류에 담는다. 데이터베이스의 오류 문구는 내보내지 않는다. */
    private static RelationRejectedException overlap(ValidityPeriod period, List<String> overlapping) {
        return new RelationRejectedException(
                "같은 관계의 유효기간이 겹칩니다. 저장하려는 기간: " + describe(period.from(), period.to()) + "."
                        + (overlapping.isEmpty() ? "" : " 겹치는 관계: " + String.join(", ", overlapping) + ".")
                        + " 기간을 고쳐 다시 보내세요.");
    }

    private static String describe(LocalDate from, LocalDate to) {
        return from + " ~ " + (to == null ? "종료일 없음" : to.toString());
    }
}
