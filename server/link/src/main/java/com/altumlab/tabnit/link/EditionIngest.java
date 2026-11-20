package com.altumlab.tabnit.link;

import com.altumlab.tabnit.pack.PackDefinition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 투입: 받은 파일 하나를 판으로 만든다 (보드 C-04, D5 5절).
 *
 * <p>판, 원본, 항목, 항목이 만든 것, 알림, 그리고 core 의 개체·관계·속성 값이 트랜잭션 하나로 모두 들어가거나
 * 하나도 안 들어간다 (D2 AD-150, AD-164). 투입은 받아들여졌든 거부됐든 투입 기록에 남는다 (D2 AD-152).
 * 받은 때와 적용일은 부르는 쪽이 넘긴다. 이 클래스는 현재 시각을 읽지 않는다.
 *
 * <p>지금은 입력 자료마다 첫 판만 받는다. 앞 판과 비교해 끝내는 일(판 비교, C-05)이 생길 때까지 둘째 판은
 * 거부한다 (D2 AD-184).
 */
public class EditionIngest {

    /** 받는 파일 크기의 기본 한도. 20MB (D2 AD-158). */
    public static final long DEFAULT_MAX_FILE_BYTES = 20L * 1024 * 1024;

    private static final LocalDate EARLIEST = LocalDate.of(1, 1, 1);

    private static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    private final JdbcTemplate jdbc;

    private final TransactionTemplate transaction;

    private final MappingExecutor executor;

    private final long maxFileBytes;

    public EditionIngest(DataSource dataSource) {
        this(dataSource, DEFAULT_MAX_FILE_BYTES);
    }

    /**
     * @param maxFileBytes 받는 파일 크기의 한도 (바이트). 설치 설정에서 읽어 넘긴다. 이 크기까지는 받는다
     */
    public EditionIngest(DataSource dataSource, long maxFileBytes) {
        Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException("파일 크기의 한도는 1 바이트 이상이어야 합니다: " + maxFileBytes);
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.executor = new MappingExecutor(dataSource);
        this.maxFileBytes = maxFileBytes;
    }

    /**
     * 파일 하나를 투입한다.
     *
     * @param pack Pack 의 정의
     * @param inputSourceId 입력 자료의 식별자. 이 Pack 의 매핑 가운데 하나여야 한다
     * @param fileName 받은 파일의 이름. 사람이 보기 위한 것이다. 없으면 null
     * @param content 파일의 바이트
     * @param effectiveDate 판의 적용일
     * @param receivedAt 받은 때. 밖에서 받아 넘긴다
     * @return 결과. 파일이 틀려 거부한 것도 예외가 아니라 결과로 돌려준다
     * @throws IllegalArgumentException 그 입력 자료의 매핑이 Pack 에 없을 때. 투입 기록을 남기지 않는다
     * @throws IngestFailedException 저장하다가 실패했을 때. 아무것도 남지 않고 투입 기록에는 거부로 남는다
     */
    public IngestResult ingest(PackDefinition pack, String inputSourceId, String fileName, byte[] content,
            LocalDate effectiveDate, Instant receivedAt) {
        Objects.requireNonNull(pack, "Pack 의 정의는 반드시 있어야 합니다");
        Objects.requireNonNull(inputSourceId, "입력 자료의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(content, "파일의 내용은 반드시 있어야 합니다");
        Objects.requireNonNull(effectiveDate, "적용일은 반드시 있어야 합니다");
        Objects.requireNonNull(receivedAt, "받은 때는 반드시 있어야 합니다");
        if (pack.mappings().stream().noneMatch(m -> m.inputSourceId().equals(inputSourceId))) {
            throw new IllegalArgumentException("Pack " + pack.packId() + " 에 입력 자료 " + inputSourceId
                    + " 의 매핑이 없습니다. 입력 자료의 식별자를 확인하세요.");
        }
        // 저장소가 담지 못하는 값은 저장하다가 실패하지 않도록 여기서 막는다.
        if (effectiveDate.isBefore(EARLIEST) || effectiveDate.isAfter(LATEST)) {
            throw new IllegalArgumentException(
                    "적용일은 0001-01-01 부터 9999-12-31 까지만 받습니다: " + effectiveDate + ". 적용일을 확인하세요.");
        }
        // 날짜로 바꾸지 않고 초로 견준다. 아주 이르거나 늦은 시각은 날짜로 바꾸는 것부터 실패한다.
        if (receivedAt.isBefore(EARLIEST.atStartOfDay(ZoneOffset.UTC).toInstant())
                || !receivedAt.isBefore(LATEST.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())) {
            throw new IllegalArgumentException("받은 때는 0001 년부터 9999 년까지만 받습니다: " + receivedAt + ".");
        }
        if (fileName != null && fileName.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("파일의 이름에 쓸 수 없는 글자(NUL)가 있습니다. 파일의 이름을 고쳐 다시 넣으세요.");
        }
        Attempt attempt = new Attempt(pack.packId(), inputSourceId, fileName, effectiveDate, receivedAt,
                LinkHash.sha256Hex(content));
        if (content.length > maxFileBytes) {
            return recordRejection(attempt, "FILE_TOO_LARGE", Integer.toString(content.length));
        }
        try {
            return transaction.execute(status -> decide(pack, attempt, content));
        }
        catch (TableFileRejectedException e) {
            // 파일 수준의 문제. 판의 트랜잭션은 되돌려졌다. 투입 기록은 따로 남긴다.
            return recordRejection(attempt, e.code(), e.detail());
        }
        catch (RuntimeException e) {
            // 저장소가 받지 않았다. 되돌려진 뒤에 투입 기록을 남기고 알린다 (D2 AD-185).
            try {
                transaction.execute(status -> reject(attempt, "STORAGE_FAILURE", null));
            }
            catch (RuntimeException recordFailure) {
                e.addSuppressed(recordFailure);
            }
            throw new IngestFailedException("입력 자료 " + inputSourceId + " 의 투입을 저장하지 못했습니다. 판과 원본을 포함해 "
                    + "이 투입에서 넣은 것은 남지 않았습니다. 까닭: " + e.getMessage(), e);
        }
    }

    /** 거부를 따로 여는 트랜잭션으로 투입 기록에 남긴다. 기록하지 못하면 무엇을 거부하려 했는지 담아 알린다. */
    private IngestResult recordRejection(Attempt attempt, String reasonCode, String reasonDetail) {
        try {
            return transaction.execute(status -> reject(attempt, reasonCode, reasonDetail));
        }
        catch (RuntimeException e) {
            throw new IngestFailedException("입력 자료 " + attempt.inputSourceId() + " 의 투입을 거부했지만 (이유의 코드 "
                    + reasonCode + ") 투입 기록에 남기지 못했습니다. 데이터베이스의 상태를 확인한 뒤 다시 넣으세요.", e);
        }
    }

    /** 트랜잭션 안에서: 앞 판을 보고 받을지 정하고, 받으면 모두 저장한다. */
    private IngestResult decide(PackDefinition pack, Attempt attempt, byte[] content) {
        // 같은 입력 자료의 투입이 겹치면 판 번호가 엇갈린다. 트랜잭션이 끝날 때까지 이 입력 자료를 잡아 둔다.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, LinkOrder.join(List.of(attempt.packId(), attempt.inputSourceId())));
        List<Edition> latest = jdbc.query("""
                select id, pack_id, input_source_id, edition_no, effective_date, content_hash, file_name, received_at
                  from edition
                 where pack_id = ? and input_source_id = ?
                 order by edition_no desc
                 limit 1
                """, EditionRecords.EDITION, attempt.packId(), attempt.inputSourceId());
        if (!latest.isEmpty()) {
            Edition previous = latest.get(0);
            if (previous.contentHash().equals(attempt.contentHash())) {
                // 내용이 바로 앞 판과 같으면 적용일이 달라도 판을 만들지 않는다 (D2 AD-155).
                record(attempt, IngestOutcome.UNCHANGED, previous.id(), null, null);
                return new IngestResult(IngestOutcome.UNCHANGED, previous.id(), null, null, null);
            }
            if (!attempt.effectiveDate().isAfter(previous.effectiveDate())) {
                // 적용일은 바로 앞 판보다 하루 이상 뒤여야 한다 (D2 AD-149).
                return reject(attempt, "EFFECTIVE_DATE_NOT_AFTER_PREVIOUS", previous.effectiveDate().toString());
            }
            // 앞 판의 값을 끝내는 일(판 비교)이 없으면 둘째 판을 바르게 넣을 수 없다 (D2 AD-184).
            return reject(attempt, "EDITION_DIFF_NOT_READY", previous.id());
        }
        int editionNo = 1;
        String editionId = LinkOrder
            .join(List.of(attempt.packId(), attempt.inputSourceId(), Integer.toString(editionNo)));
        // 매핑 실행은 이 트랜잭션에 함께 든다. 파일이 틀렸거나 저장소가 받지 않으면 예외가 나가고 모두 되돌려진다.
        MappingOutcome outcome = executor.execute(pack, attempt.inputSourceId(), content, editionNo,
                attempt.effectiveDate(), attempt.receivedAt());
        store(attempt, editionId, editionNo, content, outcome);
        record(attempt, IngestOutcome.ACCEPTED, editionId, null, null);
        return new IngestResult(IngestOutcome.ACCEPTED, editionId, null, null, outcome);
    }

    private void store(Attempt attempt, String editionId, int editionNo, byte[] content, MappingOutcome outcome) {
        jdbc.update("""
                insert into edition (id, pack_id, input_source_id, edition_no, effective_date, content_hash, content,
                                     file_name, received_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, editionId, attempt.packId(), attempt.inputSourceId(), editionNo, attempt.effectiveDate(),
                attempt.contentHash(), content, attempt.fileName(), utc(attempt.receivedAt()));
        List<Object[]> items = new ArrayList<>();
        List<Object[]> outputs = new ArrayList<>();
        for (IngestedItem item : outcome.items()) {
            items.add(new Object[] { editionId, item.itemKey(), item.rowNo(), item.contentHash() });
            for (ItemOutput output : item.outputs()) {
                // 만든 것의 식별자는 길 수 있어 색인에 넣지 않는다. 유일은 그 해시로 지킨다 (D2 AD-187).
                outputs.add(new Object[] { editionId, item.itemKey(), output.kind().name(), output.objectId(),
                        LinkHash.sha256Hex(output.objectId().getBytes(java.nio.charset.StandardCharsets.UTF_8)) });
            }
        }
        jdbc.batchUpdate("insert into edition_item (edition_id, item_key, row_no, content_hash) values (?, ?, ?, ?)",
                items);
        jdbc.batchUpdate("""
                insert into edition_item_output (edition_id, item_key, kind, object_id, object_hash)
                values (?, ?, ?, ?, ?)
                """, outputs);
        // 알림의 번호는 정해진 차례(행 번호, 종류, 코드, 내용)로 1 부터 매긴다 (D2 AD-153). 결과가 이미 그 차례다.
        List<Object[]> notices = new ArrayList<>();
        int noticeNo = 0;
        for (IngestNotice notice : outcome.notices()) {
            notices.add(new Object[] { editionId, ++noticeNo, notice.kind(), notice.rowNo(), notice.code(),
                    notice.subject(), notice.detail() });
        }
        jdbc.batchUpdate("""
                insert into edition_notice (edition_id, notice_no, kind, row_no, code, subject, detail)
                values (?, ?, ?, ?, ?, ?, ?)
                """, notices);
    }

    private IngestResult reject(Attempt attempt, String reasonCode, String reasonDetail) {
        record(attempt, IngestOutcome.REJECTED, null, reasonCode, reasonDetail);
        return new IngestResult(IngestOutcome.REJECTED, null, reasonCode, reasonDetail, null);
    }

    private void record(Attempt attempt, IngestOutcome outcome, String editionId, String reasonCode,
            String reasonDetail) {
        jdbc.update("""
                insert into ingest_record (pack_id, input_source_id, effective_date, content_hash, file_name,
                                           received_at, outcome, edition_id, reason_code, reason_detail)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, attempt.packId(), attempt.inputSourceId(), attempt.effectiveDate(), attempt.contentHash(),
                attempt.fileName(), utc(attempt.receivedAt()), outcome.name(), editionId, reasonCode, reasonDetail);
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** 투입 하나에서 받은 것. */
    private record Attempt(String packId, String inputSourceId, String fileName, LocalDate effectiveDate,
            Instant receivedAt, String contentHash) {
    }
}
