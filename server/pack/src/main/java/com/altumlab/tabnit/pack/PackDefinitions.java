package com.altumlab.tabnit.pack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 적재된 Pack 의 정의를 읽는 공개 API. 다른 모듈은 pack 의 테이블을 직접 읽지 않고 이것으로 읽는다 (D2 AD-18).
 *
 * <p>여러 테이블을 읽으므로, 그 사이에 다시 적재돼도 섞인 상태를 보지 않도록 읽기 전용 트랜잭션 하나로 묶는다.
 */
public class PackDefinitions {

    private final PackTables tables;

    private final TransactionTemplate snapshot;

    public PackDefinitions(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
        this.tables = new PackTables(new JdbcTemplate(dataSource));
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 적재된 Pack 의 식별자를 모두 돌려준다.
     *
     * @return 식별자의 글자 코드 순. 적재된 Pack 이 없으면 빈 목록
     * @throws PackStorageException 저장소에서 읽지 못했을 때
     */
    public List<String> packIds() {
        try {
            return tables.packIds();
        }
        catch (DataAccessException e) {
            throw new PackStorageException("적재된 Pack 의 목록을 읽지 못했습니다. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /**
     * Pack 하나의 정의를 돌려준다.
     *
     * @param packId Pack 식별자
     * @return 정의. 적재되지 않은 Pack 이면 비어 있다
     * @throws PackStorageException 저장소에서 읽지 못했을 때
     */
    public Optional<PackDefinition> find(String packId) {
        Objects.requireNonNull(packId, "Pack 식별자는 반드시 있어야 합니다");
        try {
            return snapshot.execute(status -> tables.read(packId));
        }
        catch (DataAccessException | TransactionException e) {
            throw new PackStorageException("Pack 의 정의를 읽지 못했습니다. Pack: " + packId
                    + ". 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }
}
