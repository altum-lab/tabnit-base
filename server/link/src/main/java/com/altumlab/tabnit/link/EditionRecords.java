package com.altumlab.tabnit.link;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 판과 투입 기록을 읽는다 (보드 C-04, D5 5절). 다른 모듈은 link 의 테이블을 직접 읽지 않고 이 API 로 읽는다 (D2 AD-18).
 *
 * <p>모든 목록은 순서가 정해져 있다. 관계를 읽지 않으므로 조회 시점을 받지 않는다.
 */
public class EditionRecords {

    static final RowMapper<Edition> EDITION = (rs, n) -> new Edition(rs.getString("id"), rs.getString("pack_id"),
            rs.getString("input_source_id"), rs.getInt("edition_no"),
            rs.getObject("effective_date", java.time.LocalDate.class), rs.getString("content_hash"),
            rs.getString("file_name"), rs.getObject("received_at", OffsetDateTime.class).toInstant());

    private static final Comparator<ItemOutput> OUTPUT_ORDER = Comparator.comparing(ItemOutput::kind)
        .thenComparing(ItemOutput::objectId, LinkOrder.STRINGS);

    private final JdbcTemplate jdbc;

    public EditionRecords(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 판 하나. 없으면 비어 있다. 원본의 내용은 담지 않는다. */
    public Optional<Edition> findEdition(String editionId) {
        Objects.requireNonNull(editionId, "판의 식별자는 반드시 있어야 합니다");
        return jdbc.query("""
                select id, pack_id, input_source_id, edition_no, effective_date, content_hash, file_name, received_at
                  from edition
                 where id = ?
                """, EDITION, editionId).stream().findFirst();
    }

    /**
     * 입력 자료의 가장 새 판: 판 번호가 가장 큰 판이다. 다음 판의 적용일은 이 판의 적용일보다 늦어야 한다.
     *
     * @return 그 입력 자료에 판이 없으면 (없는 Pack, 없는 입력 자료 포함) 비어 있다
     */
    public Optional<Edition> latestEdition(String packId, String inputSourceId) {
        Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(inputSourceId, "입력 자료의 식별자는 반드시 있어야 합니다");
        return jdbc.query("""
                select id, pack_id, input_source_id, edition_no, effective_date, content_hash, file_name, received_at
                  from edition
                 where pack_id = ? and input_source_id = ?
                 order by edition_no desc
                 limit 1
                """, EDITION, packId, inputSourceId).stream().findFirst();
    }

    /** 판의 원본: 받은 파일의 바이트 그대로 (D2 AD-150). 그런 판이 없으면 비어 있다. */
    public Optional<byte[]> originalContent(String editionId) {
        Objects.requireNonNull(editionId, "판의 식별자는 반드시 있어야 합니다");
        return jdbc.query("select content from edition where id = ?", (rs, n) -> rs.getBytes("content"), editionId)
            .stream()
            .findFirst();
    }

    /** 판의 항목과 항목이 만든 것. 항목의 키의 글자 코드 순이고, 만든 것은 종류, 식별자 순이다. */
    public List<IngestedItem> items(String editionId) {
        Objects.requireNonNull(editionId, "판의 식별자는 반드시 있어야 합니다");
        Map<String, List<ItemOutput>> outputs = new TreeMap<>(LinkOrder.STRINGS);
        jdbc.query("""
                select item_key, kind, object_id
                  from edition_item_output
                 where edition_id = ?
                 order by item_key, kind, object_hash
                """, rs -> {
            outputs.computeIfAbsent(rs.getString("item_key"), k -> new ArrayList<>())
                .add(new ItemOutput(ItemOutput.Kind.valueOf(rs.getString("kind")), rs.getString("object_id")));
        }, editionId);
        return jdbc.query("""
                select item_key, row_no, content_hash, primary_entity_id
                  from edition_item
                 where edition_id = ?
                 order by item_key
                """, (rs, n) -> {
            List<ItemOutput> made = new ArrayList<>(outputs.getOrDefault(rs.getString("item_key"), List.of()));
            // 종류의 차례는 글자 순이 아니라 정해진 차례(개체, 관계, 속성 값)다.
            made.sort(OUTPUT_ORDER);
            return new IngestedItem(rs.getString("item_key"), rs.getInt("row_no"), rs.getString("content_hash"),
                    rs.getString("primary_entity_id"), made);
        }, editionId);
    }

    /**
     * 판의 변동 이벤트와 변동 대상. 항목의 키의 글자 코드 순이고, 변동 대상은 식별자 순이다 (D2 AD-122).
     * 첫 판이면 비어 있다 (D2 AD-148).
     */
    public List<ChangeEvent> changeEvents(String editionId) {
        Objects.requireNonNull(editionId, "판의 식별자는 반드시 있어야 합니다");
        Map<String, List<String>> targets = new TreeMap<>(LinkOrder.STRINGS);
        jdbc.query("""
                select t.change_event_id, t.entity_id
                  from change_target t
                  join change_event e on e.id = t.change_event_id
                 where e.edition_id = ?
                 order by t.change_event_id, t.entity_id
                """, rs -> {
            targets.computeIfAbsent(rs.getString("change_event_id"), k -> new ArrayList<>())
                .add(rs.getString("entity_id"));
        }, editionId);
        return jdbc.query("""
                select id, edition_id, item_key, change_kind, primary_entity_id, status, failed_stage, failure_code,
                       changed_items_recorded, changed_values_recorded
                  from change_event
                 where edition_id = ?
                 order by item_key
                """, ChangeEvents.mapper(targets, ChangedItems.read(jdbc, """
                  join change_event e on e.id = c.change_event_id
                 where e.edition_id = ?
                """, editionId)), editionId);
    }

    /** 앞 판의 항목과 그 기록. 판 비교에 쓴다. 항목의 키 순이다. */
    static List<EditionPlan.PreviousItem> previousItems(JdbcTemplate jdbc, String editionId) {
        Map<String, List<EditionPlan.OutputDetail>> outputs = new TreeMap<>(LinkOrder.STRINGS);
        jdbc.query("""
                select item_key, kind, object_id, subject, fact_hash, valid_from, valid_to, value
                  from edition_item_output
                 where edition_id = ?
                 order by item_key, kind, object_hash
                """, rs -> {
            outputs.computeIfAbsent(rs.getString("item_key"), k -> new ArrayList<>())
                .add(new EditionPlan.OutputDetail(ItemOutput.Kind.valueOf(rs.getString("kind")),
                        rs.getString("object_id"), rs.getString("subject"), rs.getString("fact_hash"),
                        rs.getObject("valid_from", java.time.LocalDate.class),
                        rs.getObject("valid_to", java.time.LocalDate.class), rs.getString("value")));
        }, editionId);
        return jdbc.query("""
                select item_key, content_hash, primary_entity_id
                  from edition_item
                 where edition_id = ?
                 order by item_key
                """, (rs, n) -> {
            List<EditionPlan.OutputDetail> made = new ArrayList<>(
                    outputs.getOrDefault(rs.getString("item_key"), List.of()));
            made.sort(Comparator.comparing(EditionPlan.OutputDetail::kind)
                .thenComparing(EditionPlan.OutputDetail::objectId, LinkOrder.STRINGS));
            return new EditionPlan.PreviousItem(rs.getString("item_key"), rs.getString("content_hash"),
                    rs.getString("primary_entity_id"), made);
        }, editionId);
    }

    /** 판의 알림. 매겨진 번호 순이다 (행 번호, 종류, 코드, 내용 순으로 매긴 번호, D2 AD-153). */
    public List<IngestNotice> notices(String editionId) {
        Objects.requireNonNull(editionId, "판의 식별자는 반드시 있어야 합니다");
        return jdbc.query("""
                select kind, row_no, code, subject, detail
                  from edition_notice
                 where edition_id = ?
                 order by notice_no
                """, (rs, n) -> new IngestNotice(rs.getString("kind"), rs.getInt("row_no"), rs.getString("code"),
                rs.getString("subject"), rs.getString("detail")), editionId);
    }

    /** 한 입력 자료의 투입 기록. 받은 때, 일련번호 순이다. */
    public List<IngestRecord> ingestRecords(String packId, String inputSourceId) {
        Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(inputSourceId, "입력 자료의 식별자는 반드시 있어야 합니다");
        return jdbc.query("""
                select id, pack_id, input_source_id, effective_date, content_hash, file_name, received_at, outcome,
                       edition_id, reason_code, reason_detail
                  from ingest_record
                 where pack_id = ? and input_source_id = ?
                 order by received_at, id
                """, (rs, n) -> new IngestRecord(rs.getLong("id"), rs.getString("pack_id"),
                rs.getString("input_source_id"), rs.getObject("effective_date", java.time.LocalDate.class),
                rs.getString("content_hash"), rs.getString("file_name"),
                rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                IngestOutcome.valueOf(rs.getString("outcome")), rs.getString("edition_id"),
                rs.getString("reason_code"), rs.getString("reason_detail")), packId, inputSourceId);
    }

    /**
     * 이 Pack 에서 판이 있는 입력 자료의 식별자. 글자 코드 순이다.
     * Pack 을 다시 적재할 때 매핑을 바꿔도 되는지 가리는 데 쓴다 (D2 AD-142, AD-159).
     */
    public List<String> inputSourcesWithEditions(String packId) {
        Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
        return jdbc.queryForList("""
                select distinct input_source_id
                  from edition
                 where pack_id = ?
                 order by input_source_id
                """, String.class, packId);
    }
}
