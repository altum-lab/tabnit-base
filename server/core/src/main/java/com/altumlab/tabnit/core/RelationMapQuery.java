package com.altumlab.tabnit.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관계 지도용 조회: 개체 하나를 가운데 두고, 기준 날짜에 유효한 관계로 이어진 이웃을 노드와 간선으로 돌려준다
 * (보드 CORE-05, D3 5절, D2 AD-352).
 *
 * <p>영향 범위 계산({@link ImpactQuery})과 다르다. 영향이 퍼지는 방향을 보지 않는다: 들어오는 관계와 나가는 관계를
 * 모두 따라간다. 근거 경로도 만들지 않는다. 지도는 "무엇과 이어져 있는가"를 보는 것이다.
 *
 * <p>기준 날짜, 깊이, 노드의 한도는 반드시 받는다. 이 클래스에는 기본값이 없다.
 *
 * <p>깊이 한 단계마다 저장소를 읽는다. 그 사이에 다른 쪽이 관계를 저장해도 단계마다 다른 상태를 보지 않도록,
 * 읽기 전용 트랜잭션 하나(같은 상태를 되풀이해 읽는 격리 수준)로 묶는다. 한 번 찾은 개체는 다시 펼치지 않으므로
 * 관계가 돌아오는 구조에서도 끝난다.
 *
 * <p>찾은 노드가 한도를 넘은 단계에서 멈춘다 (D2 AD-357). 가까운 것부터 담으므로 더 깊은 곳의 노드는 어차피
 * 담기지 않는다: 담기는 노드와 간선은 끝까지 따라갔을 때와 같다. 달라지는 것은 자르기 전의 수다 (따라간 데까지의 수).
 * 한도를 넘은 단계까지만 읽는다. 그 단계는 끝까지 읽으므로 (개체 maxNodes 개 이하에 닿은 관계 모두), 관계가 아주
 * 많이 몰린 개체가 그 안에 있으면 그만큼 읽는다.
 */
public class RelationMapQuery {

    /*
     * 유효기간: 시작일 포함, 종료일 제외. 종료일이 없으면 계속 유효하다 (D3 4절).
     * 이웃을 찾을 때는 관계의 두 끝만 읽는다. 읽은 것을 정렬된 모음에 담으므로 읽는 차례는 결과에 닿지 않는다.
     */
    private static final String ENDS_TOUCHING = """
            select source_entity_id, target_entity_id
            from relation
            where (source_entity_id = any(?) or target_entity_id = any(?))
              and valid_from <= ?
              and (valid_to is null or ? < valid_to)""";

    private static final String ENTITY_TYPES = """
            select id, entity_type from entity where id = any(?)""";

    /* 간선: 두 끝이 모두 담긴 노드인 관계. 순서: 관계 유형, 출발 개체, 도착 개체, 관계의 식별자 (글자 코드 순). */
    private static final String EDGES_AMONG = """
            select id, pack_id, source_entity_id, target_entity_id, relation_type,
                   valid_from, valid_to, input_source_id, edition_no, recorded_at
            from relation
            where source_entity_id = any(?) and target_entity_id = any(?)
              and valid_from <= ?
              and (valid_to is null or ? < valid_to)
            order by relation_type collate "C", source_entity_id collate "C", target_entity_id collate "C",
                     id collate "C\"""";

    private static final Comparator<RelationMapNode> NODE_ORDER = Comparator.comparingInt(RelationMapNode::depth)
        .thenComparing(RelationMapNode::entityType, CodePointOrder.STRINGS)
        .thenComparing(RelationMapNode::entityId, CodePointOrder.STRINGS);

    private final JdbcTemplate jdbc;

    private final TransactionTemplate snapshot;

    public RelationMapQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 관계 지도를 돌려준다.
     *
     * @param centerEntityId 가운데 개체의 식별자
     * @param asOf 기준 날짜
     * @param depth 따라갈 깊이. 1 이상. 위쪽 한도는 부르는 쪽이 정한다
     * @param maxNodes 담을 노드의 한도. 1 이상. 가운데 개체를 넣어 센다. 찾은 노드가 이 수를 넘은 단계에서 멈춘다
     * @return 노드와 간선. 이어진 것이 없으면 가운데 개체만 든다
     * @throws EntityNotFoundException 가운데 개체가 없을 때
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     */
    public RelationMap relationMap(String centerEntityId, LocalDate asOf, int depth, int maxNodes) {
        Objects.requireNonNull(centerEntityId, "가운데 개체의 식별자는 반드시 있어야 합니다");
        // 깨진 글자가 든 식별자와 받는 범위 밖의 날짜는 저장소까지 보내지 않는다: 틀린 입력이다 (D5 3절, D2 AD-370).
        Identifiers.requireNotBlank(centerEntityId, "가운데 개체의 식별자");
        StorableDates.requireAsOf(asOf);
        if (depth < 1) {
            throw new IllegalArgumentException("깊이는 1 이상이어야 합니다. 받은 값: " + depth + ".");
        }
        if (maxNodes < 1) {
            throw new IllegalArgumentException("노드의 한도는 1 이상이어야 합니다. 받은 값: " + maxNodes + ".");
        }
        try {
            return snapshot.execute(status -> read(centerEntityId, asOf, depth, maxNodes));
        }
        catch (DataAccessException | TransactionException e) {
            throw new CoreStorageException("관계 지도를 조회하지 못했습니다. 개체: " + centerEntityId + ", 기준 날짜: " + asOf
                    + ". 값을 확인하고, 문제가 없으면 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    private RelationMap read(String center, LocalDate asOf, int depth, int maxNodes) {
        Integer found = jdbc.queryForObject("select count(*) from entity where id = ?", Integer.class, center);
        if (found == null || found == 0) {
            throw new EntityNotFoundException(center);
        }
        // 개체마다 가운데에서 가장 가까운 깊이. 단계를 얕은 것부터 밟으므로 처음 찾은 깊이가 가장 작다.
        TreeMap<String, Integer> depthOf = new TreeMap<>(CodePointOrder.STRINGS);
        depthOf.put(center, 0);
        TreeSet<String> frontier = new TreeSet<>(CodePointOrder.STRINGS);
        frontier.add(center);
        // 따라간 깊이. 한도를 넘은 단계에서 멈추면 그 단계다. 멈추지 않았으면 청한 깊이다.
        int reachedDepth = depth;
        for (int level = 1; level <= depth && !frontier.isEmpty(); level++) {
            String[] ids = frontier.toArray(String[]::new);
            TreeSet<String> next = new TreeSet<>(CodePointOrder.STRINGS);
            jdbc.query(ENDS_TOUCHING, rs -> {
                // 두 끝 가운데 아직 찾지 못한 쪽이 새 이웃이다. 방향은 보지 않는다.
                for (String end : new String[] { rs.getString(1), rs.getString(2) }) {
                    if (!depthOf.containsKey(end)) {
                        next.add(end);
                    }
                }
            }, ids, ids, asOf, asOf);
            for (String id : next) {
                depthOf.put(id, level);
            }
            frontier = next;
            if (depthOf.size() > maxNodes) {
                // 이 단계까지로 한도를 넘었다. 이 단계의 노드는 모두 찾았으므로 가까운 것부터 고르는 데 모자람이 없다.
                // 그 너머에 이어진 것이 더 있는지는 보지 않는다 (보려면 다음 단계를 읽어야 한다): 마침 여기가 끝이어도
                // 따라간 깊이는 이 단계다.
                reachedDepth = level;
                break;
            }
        }
        // 유형은 찾은 개체 모두에서 읽는다: 자르는 순서(깊이, 유형, 식별자)에 유형이 든다.
        Map<String, String> typeOf = new TreeMap<>(CodePointOrder.STRINGS);
        jdbc.query(ENTITY_TYPES, rs -> {
            typeOf.put(rs.getString(1), rs.getString(2));
        }, (Object) depthOf.keySet().toArray(String[]::new));
        List<RelationMapNode> nodes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : depthOf.entrySet()) {
            String type = typeOf.get(entry.getKey());
            if (type == null) {
                // 관계의 끝은 개체를 가리킨다 (저장소가 지킨다). 없으면 저장소가 어긋난 것이다: 묻는 쪽이 틀린 것이 아니다.
                throw new CoreStorageException("관계가 가리키는 개체가 저장소에 없습니다: " + entry.getKey()
                        + ". 저장소의 상태를 확인하세요.", null);
            }
            nodes.add(new RelationMapNode(entry.getKey(), type, entry.getValue()));
        }
        nodes.sort(NODE_ORDER);
        int total = nodes.size();
        List<RelationMapNode> kept = total > maxNodes ? nodes.subList(0, maxNodes) : nodes;
        String[] keptIds = kept.stream().map(RelationMapNode::entityId).toArray(String[]::new);
        List<Relation> edges = jdbc.query(EDGES_AMONG, RelationMapQuery::toRelation, keptIds, keptIds, asOf, asOf);
        return new RelationMap(kept, edges, total, total > maxNodes, reachedDepth);
    }

    private static Relation toRelation(ResultSet rs, int rowNum) throws SQLException {
        return new Relation(rs.getString("id"), rs.getString("pack_id"), rs.getString("source_entity_id"),
                rs.getString("target_entity_id"), rs.getString("relation_type"),
                new ValidityPeriod(rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class)),
                new Provenance(rs.getString("input_source_id"), rs.getInt("edition_no")),
                rs.getTimestamp("recorded_at").toInstant());
    }
}
