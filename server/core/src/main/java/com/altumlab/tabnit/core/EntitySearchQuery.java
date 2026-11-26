package com.altumlab.tabnit.core;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pack 하나의 개체를 글자로 찾는다 (카드 A-17, D2 AD-411, D5 3절). 읽기만 한다.
 *
 * <p>검색어가 다음 가운데 하나에 맞는 개체를 찾는다: 키에 검색어가 들어 있다, 식별자 전체가 검색어와 같다,
 * 기준 날짜에 유효한 속성 값에 검색어가 들어 있다 (시작일 포함, 종료일 제외). 속성 값을 읽으므로 기준 날짜를 반드시
 * 받는다. 개체에는 유효기간이 없어서 키와 식별자로는 어느 날짜에나 찾는다 (D2 AD-218).
 *
 * <p>키와 속성 값을 견줄 때 영어 글자(A ~ Z)의 대소문자만 가리지 않는다. 식별자는 대소문자까지 같아야 한다. 그 밖의 글자는 적힌 그대로 견준다: 저장소의 설정(로케일)에 따라
 * 결과가 달라지지 않게 한다. 검색어의 {@code %} 와 {@code _} 는 글자 그대로다.
 *
 * <p>순서는 식별자의 글자 코드 순이다. 이 모듈은 어느 속성이 표시 이름인지, 유형 사이의 위아래를 모른다: 걸러 낼
 * 유형은 부르는 쪽이 이름 그대로 넘긴다.
 */
public class EntitySearchQuery {

    /*
     * 인자의 차례: Pack, 모든 유형인가, 유형의 배열, 검색어가 비었는가, 검색어 그대로(식별자와 견준다),
     * 소문자로 바꾼 검색어(키), 기준 날짜 둘, 소문자로 바꾼 검색어(값).
     * lower 는 "C" 순서에서 영어 글자만 바꾼다. entity_key 는 열이 "C" 다.
     * 유효기간: 시작일 포함, 종료일 제외. 종료일이 없으면 계속 유효하다 (D3 4절).
     */
    private static final String MATCHING = """
            from entity e
            where e.pack_id = ?
              and (? or e.entity_type = any(?))
              and (? or e.id = ?
                   or position(? in lower(e.entity_key)) > 0
                   or exists (select 1 from attribute_value v
                              where v.pack_id = e.pack_id and v.entity_id = e.id
                                and v.valid_from <= ? and (v.valid_to is null or ? < v.valid_to)
                                and position(? in lower(v.value collate "C")) > 0))""";

    /* 순서: 식별자. 글자 코드 순이다. 식별자는 유일하므로 동률이 없다. */
    private static final String PAGE = "select e.pack_id, e.entity_type, e.entity_key\n" + MATCHING
            + "\norder by e.id collate \"C\"\nlimit ? offset ?";

    private static final String COUNT = "select count(*)\n" + MATCHING;

    private final JdbcTemplate jdbc;

    /**
     * 전체 수와 그 쪽을 한 시점의 것으로 읽는다 (읽기 전용, REPEATABLE READ). {@link RelationQuery} 와 같다.
     * 부르는 쪽이 이미 트랜잭션 안이면 그 트랜잭션에 들어 그 격리 수준을 따른다: 한 시점을 보장받으려면 트랜잭션 밖에서 부른다.
     */
    private final TransactionTemplate snapshot;

    public EntitySearchQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 개체를 찾아 나눠 돌려준다.
     *
     * @param packId Pack 의 식별자. 없는 Pack 이면 빈 목록이다 (이 모듈은 Pack 의 정의를 모른다)
     * @param text 검색어. 빈 글자이면 그 Pack 의 개체 전부다. 빈칸도 글자로 본다
     * @param entityTypes 걸러 낼 개체 유형의 이름. 비어 있으면 모든 유형이다. 넘긴 차례와 겹침은 결과에 닿지 않는다
     * @param asOf 기준 날짜. 속성 값은 이 날짜에 유효한 것만 본다
     * @param offset 건너뛸 수. 0 이상. 전체 수와 같거나 크면 빈 목록이다
     * @param limit 한 번에 받을 수. 1 이상. 위쪽 한도는 부르는 쪽이 정한다
     * @return 그 쪽의 개체와, 나누기 전의 전체 수
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     * @throws IllegalArgumentException Pack 의 식별자나 유형의 이름이 비었거나, 글자에 쓸 수 없는 것(NUL, 짝 없는 대리
     *         문자)이 들었거나, 기준 날짜가 받는 범위(0001-01-01 ~ 9999-12-31) 밖이거나, 나누는 값이 틀렸을 때
     */
    public EntityPage search(String packId, String text, Collection<String> entityTypes, LocalDate asOf, int offset,
            int limit) {
        Objects.requireNonNull(packId, "Pack 의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(text, "검색어는 반드시 있어야 합니다. 없으면 빈 글자를 넘깁니다");
        Objects.requireNonNull(entityTypes, "개체 유형의 목록은 반드시 있어야 합니다. 모든 유형이면 빈 목록을 넘깁니다");
        Identifiers.requireNotBlank(packId, "Pack 의 식별자");
        if (!text.isEmpty()) {
            Identifiers.requireNotBlank(text, "검색어");
        }
        TreeSet<String> types = new TreeSet<>(CodePointOrder.STRINGS);
        for (String entityType : entityTypes) {
            types.add(Identifiers.requireNotBlank(entityType, "개체 유형"));
        }
        StorableDates.requireAsOf(asOf);
        if (offset < 0) {
            throw new IllegalArgumentException("건너뛸 수는 0 이상이어야 합니다. 받은 값: " + offset + ".");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("한 번에 받을 수는 1 이상이어야 합니다. 받은 값: " + limit + ".");
        }
        String[] typeNames = types.toArray(String[]::new);
        String folded = foldEnglishCase(text);
        try {
            // 전체 수와 그 쪽은 한 시점의 것으로 읽는다. 따로 읽으면 그 사이에 개체가 늘어 수와 목록이 어긋날 수 있다.
            return snapshot.execute(status -> {
                Integer total = jdbc.query(COUNT, statement -> bind(statement, packId, typeNames, text, folded, asOf),
                        rs -> rs.next() ? rs.getInt(1) : 0);
                List<EntityRef> entities = jdbc.query(PAGE, statement -> {
                    int next = bind(statement, packId, typeNames, text, folded, asOf);
                    statement.setInt(next, limit);
                    statement.setInt(next + 1, offset);
                }, EntityQuery::toEntity);
                return new EntityPage(entities, total == null ? 0 : total);
            });
        }
        catch (DataAccessException | TransactionException e) {
            throw new CoreStorageException("개체를 찾지 못했습니다. Pack: " + packId + ", 기준 날짜: " + asOf
                    + ". 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /** 조건의 인자를 채운다. 다음에 채울 자리를 돌려준다. */
    private static int bind(PreparedStatement statement, String packId, String[] typeNames, String text, String folded,
            LocalDate asOf) throws SQLException {
        Array types = statement.getConnection().createArrayOf("text", typeNames);
        statement.setString(1, packId);
        statement.setBoolean(2, typeNames.length == 0);
        statement.setArray(3, types);
        statement.setBoolean(4, text.isEmpty());
        statement.setString(5, text);
        statement.setString(6, folded);
        statement.setObject(7, asOf);
        statement.setObject(8, asOf);
        statement.setString(9, folded);
        return 10;
    }

    /** 영어 대문자(A ~ Z)만 소문자로 바꾼다. 저장소의 lower 가 "C" 순서에서 하는 것과 같다. */
    private static String foldEnglishCase(String text) {
        StringBuilder folded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            folded.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return folded.toString();
    }
}
