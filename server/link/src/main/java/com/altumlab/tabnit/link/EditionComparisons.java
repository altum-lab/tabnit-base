package com.altumlab.tabnit.link;

import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 입력 자료의 두 판을 견준다 (카드 C-16, D2 AD-418, D5 5절). 읽기만 한다: 판, 변동 이벤트, 그 뒤의 것을 만들지 않는다.
 *
 * <p>판마다 저장된 기록("그 행이 무엇을 만들었는가": 만든 것의 식별자, 유효기간, 값)을 견준다. 판을 올릴 때 바로 앞 판과
 * 견주는 것과 같은 계산이다 ({@link EditionPlanner#compareStored}): 그래서 바로 앞 판과 견준 결과는 올릴 때 저장한 변동과
 * 같다. 원본 파일을 다시 읽지 않는다.
 *
 * <p>견주는 것은 그 행이 만든 것의 식별자다. 앞 판의 것을 이어받은 것은 두 판에 같은 식별자로 있어 바뀐 것이 아니다.
 * 값이 바뀌었다가 되돌아온 속성은 끊긴 뒤 새로 만든 것이라 식별자가 다르다: 변경으로 나오고 앞뒤의 값이 같다.
 */
public class EditionComparisons {

    private final JdbcTemplate jdbc;

    /** 두 판의 기록을 한 시점의 것으로 읽는다 (읽기 전용, REPEATABLE READ). */
    private final TransactionTemplate snapshot;

    public EditionComparisons(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
        this.jdbc = new JdbcTemplate(dataSource);
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 두 판을 적힌 방향 그대로 견준다. 뒤의 판을 앞에 적어도 된다: 그때는 추가와 삭제, 값의 앞뒤가 거꾸로다.
     *
     * @param fromEditionId 앞에 적은 판의 식별자
     * @param toEditionId 뒤에 적은 판의 식별자. 앞의 것과 같으면 바뀐 것이 없다
     * @return 두 판과 바뀐 항목
     * @throws IllegalArgumentException 그런 판이 없거나, 두 판이 같은 Pack 의 같은 입력 자료의 것이 아닐 때.
     *         어느 판이 왜 틀렸는지 담는다
     */
    public EditionComparison compare(String fromEditionId, String toEditionId) {
        Objects.requireNonNull(fromEditionId, "앞의 판의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(toEditionId, "뒤의 판의 식별자는 반드시 있어야 합니다");
        return snapshot.execute(status -> {
            Edition from = edition(fromEditionId);
            Edition to = edition(toEditionId);
            if (!from.packId().equals(to.packId()) || !from.inputSourceId().equals(to.inputSourceId())) {
                throw new IllegalArgumentException("두 판이 같은 입력 자료의 것이 아닙니다. 앞의 판: " + fromEditionId
                        + ", 뒤의 판: " + toEditionId + ". 같은 Pack 의 같은 입력 자료의 두 판을 고르세요.");
            }
            List<ItemChange> changes = EditionPlanner.compareStored(from.packId(),
                    EditionRecords.previousItems(jdbc, from.id()), EditionRecords.previousItems(jdbc, to.id()));
            return new EditionComparison(from, to, changes);
        });
    }

    private Edition edition(String editionId) {
        return jdbc.query("""
                select id, pack_id, input_source_id, edition_no, effective_date, content_hash, file_name, received_at
                  from edition
                 where id = ?
                """, EditionRecords.EDITION, editionId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("그런 판이 없습니다: " + editionId + ". 판의 식별자를 확인하세요."));
    }
}
