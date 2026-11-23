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
 * 영향 범위 계산: 출발 개체에서 기준 날짜에 유효한 관계를 따라가며 영향 대상과 근거 경로를 찾는다 (D3 7절).
 *
 * <p>이 모듈은 출발 개체가 왜 출발 개체인지, 기준 날짜들이 무엇을 뜻하는지 모른다. 받은 값으로 따라갈 뿐이다.
 *
 * <p>깊이 한 단계마다 저장소에서, 그 단계의 개체에서 영향이 퍼져 나가는 쪽의 관계와 방향을 넘겨받지 못한 유형의
 * 관계를 읽는다 (그 끝에서 퍼지지 않는 관계는 읽지 않는다. 결과는 모두 읽을 때와 같다). 한 번 찾은 개체는 다시 펼치지 않으므로
 * 관계가 돌아오는 구조에서도 끝난다.
 *
 * <p>계산 하나는 저장소를 여러 번 읽는다. 그 사이에 다른 쪽이 관계를 저장해도 단계마다 다른 상태를 보지 않도록,
 * 읽기 전용 트랜잭션 하나(같은 상태를 되풀이해 읽는 격리 수준)로 묶는다.
 */
public class ImpactQuery {

    /*
     * 한 단계에서 읽는 관계 (A-07 의 측정으로 고쳤다. D9 의 A-07).
     *
     * 그 단계의 개체에서 영향이 퍼져 나가는 쪽의 관계만 읽는다: 개체가 출발 쪽이면 출발에서 도착으로 퍼지는 유형,
     * 도착 쪽이면 도착에서 출발로 퍼지는 유형, 그리고 어느 쪽이든 방향을 넘겨받지 못한 유형이다.
     * 방향을 아는데 그 끝에서는 퍼지지 않는 유형의 관계는 읽지 않는다. 읽어도 따라가지 않으므로 결과는 같고,
     * 읽는 양이 준다 (마지막 단계의 개체로 들어오기만 하는 관계가 가장 많았다).
     * 방향을 모르는 유형은 어느 끝에서 닿든 읽어서 알린다 (D2 AD-123).
     *
     * "그 끝에서 퍼지지 않는, 방향을 아는 유형"을 하나씩 빼는 조건으로 적는다. 문장에 이어 붙는 것은 자리표시자뿐이다.
     * 자리표시자의 수가 유형의 수의 두 배까지 는다: 유형이 수만 개인 요청은 저장소의 인자 한도에 걸릴 수 있다
     * (그런 Pack 은 없다고 보고 두었다).
     *
     * 유효기간: 시작일 포함, 종료일 제외. 종료일이 없으면 계속 유효하다 (D3 4절).
     * 순서는 결과에 닿지 않게 계산하지만, 읽는 차례도 정해 둔다.
     */
    private static final String RELATION_COLUMNS = """
            select id, pack_id, source_entity_id, target_entity_id, relation_type,
                   valid_from, valid_to, input_source_id, edition_no, recorded_at
            from relation
            where (""";

    private static final String RELATION_PERIOD_AND_ORDER = """
            )
              and valid_from <= ?
              and (valid_to is null or ? < valid_to)
            order by id collate "C\"""";

    /**
     * 요청 하나에 쓰는 단계의 질의. 관계 유형마다의 방향이 요청 안에서 같으므로 한 번 만든다.
     *
     * @param sql 질의. 자리: 출발 쪽의 개체들, 출발 쪽에서 뺄 유형들, 도착 쪽의 개체들, 도착 쪽에서 뺄 유형들, 기준 날짜 둘
     * @param notFromSource 방향을 알고, 출발 쪽에서는 퍼지지 않는 유형 (도착에서 출발로만 퍼지거나 퍼지지 않는 것)
     * @param notFromTarget 방향을 알고, 도착 쪽에서는 퍼지지 않는 유형 (출발에서 도착으로만 퍼지거나 퍼지지 않는 것)
     */
    private record StepQuery(String sql, List<String> notFromSource, List<String> notFromTarget) {

        static StepQuery of(Map<String, ImpactDirection> directions) {
            List<String> notFromSource = new ArrayList<>();
            List<String> notFromTarget = new ArrayList<>();
            // 유형의 차례는 요청이 정해 둔 글자 코드 순이다: 같은 요청이면 같은 문장이 나온다.
            for (Map.Entry<String, ImpactDirection> entry : directions.entrySet()) {
                ImpactDirection direction = entry.getValue();
                if (direction != ImpactDirection.SOURCE_TO_TARGET && direction != ImpactDirection.BOTH) {
                    notFromSource.add(entry.getKey());
                }
                if (direction != ImpactDirection.TARGET_TO_SOURCE && direction != ImpactDirection.BOTH) {
                    notFromTarget.add(entry.getKey());
                }
            }
            String sql = RELATION_COLUMNS + "(source_entity_id = any(?)" + " and relation_type <> ?".repeat(notFromSource.size())
                    + ")\n   or (target_entity_id = any(?)" + " and relation_type <> ?".repeat(notFromTarget.size()) + ")"
                    + RELATION_PERIOD_AND_ORDER;
            return new StepQuery(sql, List.copyOf(notFromSource), List.copyOf(notFromTarget));
        }

        Object[] arguments(String[] entityIds, LocalDate asOf) {
            List<Object> arguments = new ArrayList<>();
            arguments.add(entityIds);
            arguments.addAll(notFromSource);
            arguments.add(entityIds);
            arguments.addAll(notFromTarget);
            arguments.add(asOf);
            arguments.add(asOf);
            return arguments.toArray();
        }
    }

    private static final String ENTITY_TYPES = """
            select id, pack_id, entity_type from entity where id = any(?) order by id collate "C\"""";

    private static final Comparator<ImpactTarget> TARGET_ORDER = Comparator.comparingInt(ImpactTarget::depth)
        .thenComparing(ImpactTarget::entityType, CodePointOrder.STRINGS)
        .thenComparing(ImpactTarget::entityId, CodePointOrder.STRINGS);

    private final JdbcTemplate jdbc;

    private final TransactionTemplate snapshot;

    public ImpactQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다"));
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 영향 범위를 계산한다.
     *
     * @param request 출발 개체, 기준 날짜, 관계 유형마다의 영향 방향, 최대 깊이
     * @return 영향 범위. 영향 대상이 없으면 빈 목록을 담는다
     * @throws EntityNotFoundException 출발 개체가 없을 때
     * @throws IllegalArgumentException 출발 개체가 서로 다른 Pack 에 있을 때
     * @throws UnknownImpactDirectionException 영향 방향을 넘겨받지 못한 관계 유형의 관계를 만났을 때
     * @throws CoreStorageException 저장소에서 읽지 못했을 때
     */
    public ImpactScope impactScope(ImpactRequest request) {
        Objects.requireNonNull(request, "영향 범위 계산의 입력은 반드시 있어야 합니다");
        try {
            return snapshot.execute(status -> compute(request));
        }
        catch (DataAccessException | TransactionException e) {
            throw new CoreStorageException("영향 범위를 계산하지 못했습니다. 출발 개체: " + request.startEntityIds()
                    + ", 기준 날짜: " + request.asOfDates()
                    + ". 값을 확인하고, 문제가 없으면 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }

    private ImpactScope compute(ImpactRequest request) {
        {
            requireEntitiesExist(request.startEntityIds());

            // 날짜마다 최대 깊이까지 따라간다.
            StepQuery stepQuery = StepQuery.of(request.directions());
            List<Traversal> traversals = new ArrayList<>();
            for (LocalDate asOf : request.asOfDates()) {
                Traversal traversal = new Traversal(asOf, request, stepQuery);
                traversal.advanceTo(request.maxDepth());
                traversals.add(traversal);
            }

            // 합친다: 더 짧은 경로를, 길이가 같으면 더 늦은 날짜의 경로를 남긴다 (D2 AD-116).
            // 날짜는 이른 것부터 돌므로, 길이가 같거나 짧으면 뒤의 것으로 바꾼다.
            TreeMap<String, Found> merged = new TreeMap<>(CodePointOrder.STRINGS);
            for (Traversal traversal : traversals) {
                for (Found found : traversal.reached()) {
                    Found kept = merged.get(found.entityId);
                    if (kept == null || found.pathIds.size() <= kept.pathIds.size()) {
                        merged.put(found.entityId, found);
                    }
                }
            }

            // 한계 표시: 합친 결과에 없는 대상이 최대 깊이 너머에 하나라도 있을 때만 켠다 (D2 AD-62, AD-117).
            // 날짜마다 본다. 앞 날짜에서 켜졌다고 뒤 날짜를 건너뛰면, 방향을 모르는 유형을 알리는지가
            // 상관없는 관계에 따라 달라진다.
            boolean truncated = false;
            for (Traversal traversal : traversals) {
                if (traversal.findsBeyond(merged)) {
                    truncated = true;
                }
            }
            return toScope(merged, truncated);
        }
    }

    private ImpactScope toScope(TreeMap<String, Found> merged, boolean truncated) {
        Map<String, String> types = new TreeMap<>(CodePointOrder.STRINGS);
        if (!merged.isEmpty()) {
            jdbc.query(ENTITY_TYPES, rs -> {
                types.put(rs.getString("id"), rs.getString("entity_type"));
            }, (Object) merged.keySet().toArray(String[]::new));
        }
        List<ImpactTarget> targets = new ArrayList<>();
        for (Found found : merged.values()) {
            String type = types.get(found.entityId);
            if (type == null) {
                // 관계는 있는 개체만 가리키므로 생기지 않는다. 생기면 저장소가 어긋난 것이다.
                throw new CoreStorageException("관계가 가리키는 개체가 저장소에 없습니다: " + found.entityId
                        + ". 저장소의 상태를 확인하세요.", null);
            }
            targets.add(new ImpactTarget(found.entityId, type, found.pathIds.size(),
                    new ImpactPath(found.startEntityId, found.asOf, found.path)));
        }
        targets.sort(TARGET_ORDER);
        TreeMap<String, Integer> byType = new TreeMap<>(CodePointOrder.STRINGS);
        TreeMap<Integer, Integer> byDepth = new TreeMap<>();
        for (ImpactTarget target : targets) {
            byType.merge(target.entityType(), 1, Integer::sum);
            byDepth.merge(target.depth(), 1, Integer::sum);
        }
        return new ImpactScope(targets, byType, byDepth, truncated);
    }

    private void requireEntitiesExist(List<String> entityIds) {
        TreeMap<String, String> packOf = new TreeMap<>(CodePointOrder.STRINGS);
        jdbc.query(ENTITY_TYPES, rs -> {
            packOf.put(rs.getString("id"), rs.getString("pack_id"));
        }, (Object) entityIds.toArray(String[]::new));
        for (String id : entityIds) {
            if (!packOf.containsKey(id)) {
                throw new EntityNotFoundException(id);
            }
        }
        // 영향 범위는 한 Pack 안에서만 계산한다 (D5 1절, D2 AD-74).
        TreeSet<String> packs = new TreeSet<>(CodePointOrder.STRINGS);
        packs.addAll(packOf.values());
        if (packs.size() > 1) {
            throw new IllegalArgumentException("출발 개체가 서로 다른 Pack 에 있습니다: " + packs
                    + ". 영향 범위는 한 Pack 안에서만 계산합니다. 같은 Pack 의 개체만 넣어 다시 보내세요.");
        }
    }

    private static Relation toRelation(ResultSet rs, int rowNum) throws SQLException {
        return new Relation(rs.getString("id"), rs.getString("pack_id"), rs.getString("source_entity_id"),
                rs.getString("target_entity_id"), rs.getString("relation_type"),
                new ValidityPeriod(rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class)),
                new Provenance(rs.getString("input_source_id"), rs.getInt("edition_no")),
                rs.getTimestamp("recorded_at").toInstant());
    }

    /** 찾은 개체 하나와 그에 이르는, 지금까지 가장 앞선 경로. */
    private static final class Found {

        final String entityId;

        final String startEntityId;

        final LocalDate asOf;

        final List<Relation> path;

        final List<String> pathIds;

        Found(String entityId, String startEntityId, LocalDate asOf, List<Relation> path, List<String> pathIds) {
            this.entityId = entityId;
            this.startEntityId = startEntityId;
            this.asOf = asOf;
            this.path = path;
            this.pathIds = pathIds;
        }

        Found extend(String nextEntityId, Relation relation) {
            List<Relation> nextPath = new ArrayList<>(path);
            nextPath.add(relation);
            List<String> nextIds = new ArrayList<>(pathIds);
            nextIds.add(relation.id());
            return new Found(nextEntityId, startEntityId, asOf, nextPath, nextIds);
        }
    }

    /** 기준 날짜 하나로 따라가는 일. 깊이 한 단계씩 나아간다. */
    private final class Traversal {

        private final LocalDate asOf;

        private final ImpactRequest request;

        private final StepQuery stepQuery;

        /** 이 날짜에 찾은 개체. 출발 개체도 들어 있다 (경로가 빈 것). */
        private final TreeMap<String, Found> visited = new TreeMap<>(CodePointOrder.STRINGS);

        /** 가장 최근 단계에서 새로 찾은 개체. */
        private TreeMap<String, Found> frontier = new TreeMap<>(CodePointOrder.STRINGS);

        private int depth;

        Traversal(LocalDate asOf, ImpactRequest request, StepQuery stepQuery) {
            this.asOf = asOf;
            this.request = request;
            this.stepQuery = stepQuery;
            for (String start : request.startEntityIds()) {
                Found found = new Found(start, start, asOf, List.of(), List.of());
                visited.put(start, found);
                frontier.put(start, found);
            }
        }

        void advanceTo(int maxDepth) {
            while (depth < maxDepth && !frontier.isEmpty()) {
                step();
            }
        }

        /** 이 날짜에 찾은 영향 대상. 출발 개체는 뺀다 (D2 AD-34). */
        List<Found> reached() {
            List<Found> reached = new ArrayList<>();
            for (Found found : visited.values()) {
                if (!found.pathIds.isEmpty()) {
                    reached.add(found);
                }
            }
            return reached;
        }

        /**
         * 최대 깊이 너머를 더 따라가, 합친 결과에 없는 개체에 닿는지 본다. 닿으면 바로 멈춘다.
         * 합친 결과에 이미 있는 개체는 지나서 계속 따라간다.
         */
        boolean findsBeyond(Map<String, Found> merged) {
            while (!frontier.isEmpty()) {
                step();
                for (String entityId : frontier.keySet()) {
                    if (!merged.containsKey(entityId)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * 한 단계 나아간다. 가장 최근 단계의 개체에서 영향이 퍼져 나가는 쪽의 관계(와 방향을 모르는 유형의 관계)를 읽어,
         * 아직 찾지 못한 개체를 새 단계로 삼는다.
         */
        private void step() {
            String[] ids = frontier.keySet().toArray(String[]::new);
            List<Relation> relations = jdbc.query(stepQuery.sql(), ImpactQuery::toRelation, stepQuery.arguments(ids, asOf));
            TreeMap<String, Found> next = new TreeMap<>(CodePointOrder.STRINGS);
            for (Relation relation : relations) {
                ImpactDirection direction = request.directions().get(relation.relationType());
                if (direction == null) {
                    throw new UnknownImpactDirectionException(relation.relationType(), relation.id());
                }
                if (direction == ImpactDirection.SOURCE_TO_TARGET || direction == ImpactDirection.BOTH) {
                    offer(next, relation, relation.sourceEntityId(), relation.targetEntityId());
                }
                if (direction == ImpactDirection.TARGET_TO_SOURCE || direction == ImpactDirection.BOTH) {
                    offer(next, relation, relation.targetEntityId(), relation.sourceEntityId());
                }
            }
            visited.putAll(next);
            frontier = next;
            depth++;
        }

        /**
         * from 이 가장 최근 단계의 개체이고 to 를 아직 찾지 못했으면, from 의 경로에 이 관계를 이은 경로를 to 의 후보로 삼는다.
         * 같은 단계의 후보끼리는 길이가 같다. 관계 식별자를 출발 개체 쪽부터 비교해 앞선 것을 남긴다 (D2 AD-36).
         */
        private void offer(TreeMap<String, Found> next, Relation relation, String from, String to) {
            Found parent = frontier.get(from);
            if (parent == null || visited.containsKey(to)) {
                return;
            }
            Found kept = next.get(to);
            if (kept == null) {
                next.put(to, parent.extend(to, relation));
                return;
            }
            int byParent = CodePointOrder.compareLists(parent.pathIds, kept.pathIds.subList(0, parent.pathIds.size()));
            if (byParent < 0 || (byParent == 0
                    && CodePointOrder.compare(relation.id(), kept.pathIds.get(kept.pathIds.size() - 1)) < 0)) {
                next.put(to, parent.extend(to, relation));
            }
        }
    }
}
