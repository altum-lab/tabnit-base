package com.altumlab.tabnit.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * 두 개체 사이의 가장 짧은 경로를 찾는다 (카드 A-18, D2 AD-413, D3 5절, D5 3절). 읽기만 한다.
 *
 * <p>기준 날짜에 유효한 관계만 쓴다 (시작일 포함, 종료일 제외). 관계의 방향을 가리지 않는다: 들어오는 관계와 나가는
 * 관계를 모두 따라간다. 영향 범위의 근거 경로({@link ImpactQuery})와 다른 계산이다: 영향이 퍼지는 방향을 보지 않는다.
 * 자신을 가리키는 관계는 저장소가 받지 않는다: 있더라도 걸음으로 치지 않는다.
 *
 * <p>길이가 같은 가장 짧은 경로가 여럿이면 하나를 고른다: 출발 개체 쪽부터 관계의 식별자를 차례로 견줘 글자 코드 순으로
 * 앞선 경로다. 근거 경로를 고르는 규칙과 같다. 같은 자료에서는 언제나 같은 경로가 나온다.
 *
 * <p>출발 개체에서 한 단계씩 넓혀 가며 저장소를 읽는다. 그 사이에 다른 쪽이 관계를 저장해도 단계마다 다른 상태를 보지
 * 않도록 읽기 전용 트랜잭션 하나(같은 상태를 되풀이해 읽는 격리 수준)로 묶는다. 부르는 쪽이 이미 트랜잭션 안이면 그
 * 트랜잭션에 들어 그 격리 수준을 따른다. 한 번 찾은 개체는 다시 펼치지 않으므로 관계가 돌아오는 구조에서도 끝난다.
 *
 * <p>단계마다 그 단계의 개체에 닿은 관계를 끝까지 읽는다: 개체의 한도는 읽은 뒤에 본다. 관계가 아주 많이 몰린 개체가
 * 그 안에 있으면 그만큼 읽는다 ({@link RelationMapQuery} 와 같다).
 *
 * <p>기준 날짜, 최대 길이, 훑는 개체의 한도는 반드시 받는다. 이 클래스에는 기본값이 없다.
 */
public class RelationPathQuery {

    /*
     * 유효기간: 시작일 포함, 종료일 제외. 종료일이 없으면 계속 유효하다 (D3 4절).
     * 읽은 것을 정렬된 모음에 담고 고를 때 식별자로 견주므로 읽는 차례는 결과에 닿지 않는다.
     */
    private static final String TOUCHING = """
            select id, pack_id, source_entity_id, target_entity_id, relation_type,
                   valid_from, valid_to, input_source_id, edition_no, recorded_at
            from relation
            where (source_entity_id = any(?) or target_entity_id = any(?))
              and valid_from <= ?
              and (valid_to is null or ? < valid_to)""";

    /** 한 단계 나아가는 걸음: 관계 하나를 어느 개체에서 어느 개체로 건넜는가. */
    private record Hop(Relation relation, String from, String to) {
    }

    private final JdbcTemplate jdbc;

    private final TransactionTemplate snapshot;

    public RelationPathQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 두 개체 사이의 가장 짧은 경로를 찾는다.
     *
     * @param fromEntityId 출발 개체의 식별자
     * @param toEntityId 도착 개체의 식별자. 출발 개체와 같으면 길이 0 의 경로다
     * @param asOf 기준 날짜
     * @param maxLength 경로의 최대 길이(관계의 수). 1 이상. 이보다 긴 경로는 찾지 않는다
     * @param maxEntities 훑는 개체의 한도. 1 이상. 출발 개체를 넣어 센다. 찾은 개체가 이 수를 넘은 단계에서 멈춘다
     *        (그 단계에서 도착 개체를 찾았으면 찾은 것이다)
     * @return 경로. 찾지 못했으면 {@link RelationPath#found()} 가 거짓이고, 한도 때문이면
     *         {@link RelationPath#limitReached()} 가 참이다
     * @throws EntityNotFoundException 출발 개체나 도착 개체가 없을 때
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     * @throws IllegalArgumentException 기준 날짜가 받는 범위(0001-01-01 ~ 9999-12-31) 밖이거나, 개체의 식별자가 비었거나
     *         쓸 수 없는 글자(NUL, 짝 없는 대리 문자)를 담았거나, 한도가 1 보다 작을 때
     */
    public RelationPath shortestPath(String fromEntityId, String toEntityId, LocalDate asOf, int maxLength,
            int maxEntities) {
        Objects.requireNonNull(fromEntityId, "출발 개체의 식별자는 반드시 있어야 합니다");
        Objects.requireNonNull(toEntityId, "도착 개체의 식별자는 반드시 있어야 합니다");
        Identifiers.requireNotBlank(fromEntityId, "출발 개체의 식별자");
        Identifiers.requireNotBlank(toEntityId, "도착 개체의 식별자");
        StorableDates.requireAsOf(asOf);
        if (maxLength < 1) {
            throw new IllegalArgumentException("경로의 최대 길이는 1 이상이어야 합니다. 받은 값: " + maxLength + ".");
        }
        if (maxEntities < 1) {
            throw new IllegalArgumentException("훑는 개체의 한도는 1 이상이어야 합니다. 받은 값: " + maxEntities + ".");
        }
        try {
            return snapshot.execute(status -> find(fromEntityId, toEntityId, asOf, maxLength, maxEntities));
        }
        catch (DataAccessException | TransactionException e) {
            throw new CoreStorageException("경로를 찾지 못했습니다. 출발 개체: " + fromEntityId + ", 도착 개체: " + toEntityId
                    + ", 기준 날짜: " + asOf + ". 값을 확인하고, 문제가 없으면 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    private RelationPath find(String from, String to, LocalDate asOf, int maxLength, int maxEntities) {
        requireEntity(from);
        requireEntity(to);
        if (from.equals(to)) {
            return new RelationPath(true, false, List.of(), List.of(from));
        }
        // 개체마다 출발 개체에서의 가장 짧은 거리. 단계를 가까운 것부터 밟으므로 처음 찾은 거리가 가장 작다.
        TreeMap<String, Integer> distance = new TreeMap<>(CodePointOrder.STRINGS);
        distance.put(from, 0);
        TreeSet<String> frontier = new TreeSet<>(CodePointOrder.STRINGS);
        frontier.add(from);
        // 단계마다의 걸음: 거리 n 인 개체에서 거리 n + 1 인 개체로 건너는 관계.
        List<List<Hop>> hopsByLevel = new ArrayList<>();
        boolean reached = false;
        boolean limitReached = false;
        for (int level = 0; level < maxLength && !frontier.isEmpty(); level++) {
            String[] ids = frontier.toArray(String[]::new);
            List<Relation> touching = jdbc.query(TOUCHING, RelationQuery::toRelation, ids, ids, asOf, asOf);
            // 먼저 이 단계에서 새로 찾은 개체를 모두 정한다. 그 뒤에 걸음을 적는다: 읽은 차례에 결과가 달라지지 않는다.
            TreeSet<String> next = new TreeSet<>(CodePointOrder.STRINGS);
            for (Relation relation : touching) {
                for (String end : new String[] { relation.sourceEntityId(), relation.targetEntityId() }) {
                    if (!distance.containsKey(end)) {
                        next.add(end);
                    }
                }
            }
            for (String id : next) {
                distance.put(id, level + 1);
            }
            List<Hop> hops = new ArrayList<>();
            for (Relation relation : touching) {
                addHop(hops, relation, relation.sourceEntityId(), relation.targetEntityId(), distance, level);
                addHop(hops, relation, relation.targetEntityId(), relation.sourceEntityId(), distance, level);
            }
            hopsByLevel.add(hops);
            frontier = next;
            if (next.contains(to)) {
                reached = true;
                break;
            }
            if (distance.size() > maxEntities) {
                // 훑은 개체가 한도를 넘었다. 더 넓히지 않는다: 그 너머에 경로가 있는지는 알 수 없다.
                limitReached = true;
                break;
            }
        }
        if (!reached) {
            // 넓힐 개체가 남았는데 최대 길이에서 멈췄으면 한도 때문이다: 그 너머는 읽지 않았다 (마침 거기가 끝이어도
            // 그렇게 답한다). 넓힐 개체가 없으면 정말 없는 것이다.
            return new RelationPath(false, limitReached || !frontier.isEmpty(), List.of(), List.of());
        }
        return choose(from, to, hopsByLevel);
    }

    /** 거리 level 인 개체에서 거리 level + 1 인 개체로 건너는 걸음이면 적는다. 자신을 가리키는 관계는 걸음이 아니다. */
    private static void addHop(List<Hop> hops, Relation relation, String from, String to,
            TreeMap<String, Integer> distance, int level) {
        Integer fromDistance = distance.get(from);
        Integer toDistance = distance.get(to);
        if (fromDistance != null && toDistance != null && fromDistance == level && toDistance == level + 1) {
            hops.add(new Hop(relation, from, to));
        }
    }

    /**
     * 가장 짧은 경로 가운데 하나를 고른다. 먼저 도착 개체에서 거꾸로 올라가며 도착 개체에 닿는 걸음만 남기고,
     * 출발 개체에서 내려가며 단계마다 관계의 식별자가 글자 코드 순으로 가장 앞선 걸음을 고른다.
     */
    private static RelationPath choose(String from, String to, List<List<Hop>> hopsByLevel) {
        // 단계마다, 도착 개체에 닿을 수 있는 개체.
        List<TreeSet<String>> leading = new ArrayList<>();
        for (int i = 0; i <= hopsByLevel.size(); i++) {
            leading.add(new TreeSet<>(CodePointOrder.STRINGS));
        }
        leading.get(hopsByLevel.size()).add(to);
        for (int level = hopsByLevel.size() - 1; level >= 0; level--) {
            for (Hop hop : hopsByLevel.get(level)) {
                if (leading.get(level + 1).contains(hop.to())) {
                    leading.get(level).add(hop.from());
                }
            }
        }
        List<Relation> relations = new ArrayList<>();
        List<String> entityIds = new ArrayList<>();
        entityIds.add(from);
        String current = from;
        for (int level = 0; level < hopsByLevel.size(); level++) {
            Hop best = null;
            for (Hop hop : hopsByLevel.get(level)) {
                if (hop.from().equals(current) && leading.get(level + 1).contains(hop.to())
                        && (best == null || CodePointOrder.compare(hop.relation().id(), best.relation().id()) < 0)) {
                    best = hop;
                }
            }
            if (best == null) {
                // 도착 개체를 찾았으면 출발 개체에서 닿는 걸음이 단계마다 반드시 있다. 없으면 이 계산이 틀린 것이다.
                throw new IllegalStateException("경로를 고르다 " + (level + 1) + "번째 걸음을 찾지 못했습니다. 출발 개체: " + from
                        + ", 도착 개체: " + to + ".");
            }
            relations.add(best.relation());
            entityIds.add(best.to());
            current = best.to();
        }
        return new RelationPath(true, false, relations, entityIds);
    }

    private void requireEntity(String entityId) {
        Integer found = jdbc.queryForObject("select count(*) from entity where id = ?", Integer.class, entityId);
        if (found == null || found == 0) {
            throw new EntityNotFoundException(entityId);
        }
    }
}
