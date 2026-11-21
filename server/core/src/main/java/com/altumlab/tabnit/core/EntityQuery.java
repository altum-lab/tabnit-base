package com.altumlab.tabnit.core;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 개체를 식별자로 읽는다 (보드에 없던 카드 A-14, D2 AD-218).
 *
 * <p>여럿을 읽은 결과의 차례는 식별자의 글자 코드 순이다. (Pack, 유형, 키) 순과 다를 수 있다.
 * 여러 Pack 의 식별자를 섞어 물으면 섞어 돌려준다. 같은 Pack 의 것인지는 부르는 쪽이 견준다.
 *
 * <p>개체가 어느 Pack 의 어느 유형의 어느 키인지를 답한다. 없는 개체는 오류가 아니라 "없다"로 답한다:
 * 있는지를 묻는 것이 이 조회의 쓰임이기 때문이다. 식별자를 풀어 읽지 않고 저장된 값을 돌려준다.
 *
 * <p>개체에는 유효기간이 없다. 관계와 속성 값을 읽지 않으므로 기준 날짜를 받지 않는다 (D3 2절).
 */
public class EntityQuery {

    private static final String FIND = "select pack_id, entity_type, entity_key from entity where id = ?";

    /* 순서: 식별자. 글자 코드 순이다. 식별자는 유일하므로 동률이 없다. */
    private static final String FIND_ALL = """
            select pack_id, entity_type, entity_key
            from entity
            where id = any(?)
            order by id collate "C\"""";

    private final JdbcTemplate jdbc;

    public EntityQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
    }

    /**
     * 개체 하나를 식별자로 읽는다.
     *
     * @return 그 개체. 없으면 비어 있다
     * @throws IllegalArgumentException 식별자가 비어 있거나 저장할 수 없는 글자를 담았을 때
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     */
    public Optional<EntityRef> find(String entityId) {
        Identifiers.requireNotBlank(entityId, "개체의 식별자");
        try {
            return jdbc.query(FIND, EntityQuery::toEntity, entityId).stream().findFirst();
        }
        catch (DataAccessException e) {
            throw new CoreStorageException("개체를 조회하지 못했습니다. 개체: " + entityId
                    + ". 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    /**
     * 여러 개체를 식별자로 한 번에 읽는다. 있는 것만 돌려준다.
     *
     * @param entityIds 물을 식별자. 같은 식별자가 여러 번 있어도 된다
     * @return 있는 개체. 식별자의 글자 코드 순이고 겹치지 않는다. 물은 차례와 무관하다
     * @throws IllegalArgumentException 식별자 가운데 비어 있거나 저장할 수 없는 글자를 담은 것이 있을 때
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     */
    public List<EntityRef> findAll(Collection<String> entityIds) {
        Objects.requireNonNull(entityIds, "개체의 식별자 목록은 반드시 있어야 합니다");
        TreeSet<String> distinct = new TreeSet<>(CodePointOrder.STRINGS);
        int position = 0;
        for (String entityId : entityIds) {
            position++;
            distinct.add(Identifiers.requireNotBlank(entityId, "개체의 식별자 목록의 " + position + "번째 값"));
        }
        if (distinct.isEmpty()) {
            return List.of();
        }
        String[] ids = distinct.toArray(String[]::new);
        try {
            // 식별자를 배열 하나로 넘긴다. 인자의 수에 한도가 없다.
            return jdbc.query(FIND_ALL, (PreparedStatement statement) -> {
                Array array = statement.getConnection().createArrayOf("text", ids);
                statement.setArray(1, array);
            }, EntityQuery::toEntity);
        }
        catch (DataAccessException e) {
            throw new CoreStorageException("개체를 조회하지 못했습니다. 물은 개체: " + ids.length
                    + "개. 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    private static EntityRef toEntity(ResultSet rs, int rowNum) throws SQLException {
        return new EntityRef(rs.getString("pack_id"), rs.getString("entity_type"), rs.getString("entity_key"));
    }
}
