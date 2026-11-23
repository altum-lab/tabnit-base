package com.altumlab.tabnit.rules;

import com.altumlab.tabnit.core.CoreStorageException;
import com.altumlab.tabnit.core.EntityQuery;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.ImpactScope;
import com.altumlab.tabnit.core.ImpactTarget;
import com.altumlab.tabnit.core.Relation;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.RuleDefinition;
import com.altumlab.tabnit.pack.RuleSubject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 조치 생성 (D8 5절). 변동 하나에 규칙을 적용해 조치를 만들고, 넘겨받은 영향 범위와 함께 트랜잭션 하나로 저장한다
 * (D2 AD-209). 영향 범위는 계산하지 않고 받는다. 조치는 대기로 만들어진다.
 *
 * <p>현재 시각을 읽지 않는다. 기한은 판의 적용일에서, 만든 때는 넘겨받은 시각에서 온다.
 *
 * <p>부르는 쪽이 이미 트랜잭션 안이면 저장은 그 트랜잭션에 든다. 그 트랜잭션의 격리 수준은 READ COMMITTED 여야
 * 한다: 더 높은 수준에서는, 그 트랜잭션이 시작한 뒤 다른 쪽이 같은 변동을 저장했을 때 저장된 것을 돌려받지 못하고
 * 저장소의 오류({@link ActionStorageException})가 된다.
 */
public class ActionGenerator {

    /** 저장소가 담는 마지막 날짜. 기한이 이보다 뒤면 저장할 수 없다. */
    private static final LocalDate LAST_DATE = LocalDate.of(9999, 12, 31);

    /** 받는 첫 날짜. 적용일이 이 날이면 그 전날을 기준 날짜로 담을 수 없다. */
    private static final LocalDate FIRST_DATE = LocalDate.of(1, 1, 1);

    private final JdbcTemplate jdbc;

    private final TransactionTemplate transaction;

    private final EntityQuery entities;

    private final RuleEvaluator evaluator;

    private final ActionRecords records;

    public ActionGenerator(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.entities = new EntityQuery(dataSource);
        this.evaluator = new RuleEvaluator(dataSource);
        this.records = new ActionRecords(dataSource);
    }

    /**
     * 변동 하나의 조치를 만들어 영향 범위와 함께 저장한다.
     *
     * <p>이미 저장된 변동이면 아무것도 다시 계산하지 않고 저장된 조치를 돌려준다 (D2 AD-228). 이때 넘겨받은 다른 값은
     * 보지 않는다. 평가에 실패한 규칙이 하나라도 있으면 아무것도 저장하지 않고 실패한 규칙을 돌려준다 (D2 AD-227):
     * 부르는 쪽이 변동 이벤트를 실패로 표시하고, 고친 뒤 다시 부르면 처음부터 계산한다.
     *
     * @param pack Pack 의 정의
     * @param change 변동. 그 주 개체가 조치의 근거가 된다 (D2 AD-120)
     * @param edition 그 변동이 나온 입력 자료의 판
     * @param scope 그 변동의 영향 범위. core 가 변동 대상에서 출발해 적용일 전날과 적용일로 계산한 것 (D2 AD-100)
     * @param maxDepth 그 계산에 쓴 최대 깊이
     * @param recordedAt 저장하는 때. 밖에서 받는다. 마이크로초까지 남긴다
     * @throws IllegalArgumentException 잘못 부른 것: 주 개체나 변동 대상이 저장소에 없거나 이 Pack 의 것이 아닐 때,
     *         영향 범위가 이 변동의 것으로 볼 수 없을 때. 아무것도 저장하지 않는다
     * @throws ActionStorageException 저장하지 못했을 때. 아무것도 남지 않는다
     */
    public ActionGeneration generate(PackDefinition pack, RuleChange change, BasisEdition edition, ImpactScope scope,
            int maxDepth, Instant recordedAt) {
        Objects.requireNonNull(pack, "Pack 의 정의는 반드시 있어야 합니다");
        Objects.requireNonNull(change, "변동은 반드시 있어야 합니다");
        Objects.requireNonNull(edition, "변동이 나온 판은 반드시 있어야 합니다");
        Objects.requireNonNull(scope, "영향 범위는 반드시 있어야 합니다");
        Objects.requireNonNull(recordedAt, "저장하는 때는 반드시 있어야 합니다");
        String eventId = change.changeEventId();
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("변동 이벤트의 식별자가 비어 있습니다. 식별자를 넣어 다시 보내세요.");
        }
        requireStorable(eventId, "변동 이벤트의 식별자");
        requireStorable(edition.inputSourceId(), "입력 자료의 식별자");
        if (!change.effectiveDate().isAfter(FIRST_DATE)) {
            throw new IllegalArgumentException("판의 적용일 " + change.effectiveDate() + " 의 전날을 날짜로 담을 수 없습니다 (변동 이벤트 "
                    + eventId + "). 적용일은 0001-01-02 부터 받습니다. 적용일을 확인하세요.");
        }
        try {
            Optional<String> storedPack = records.storedPackId(eventId);
            if (storedPack.isPresent()) {
                return alreadyStored(pack, eventId, storedPack.get());
            }
            checkScope(change, scope, maxDepth);
            checkPrimary(pack, change);
            // 잘못 부른 것은 여기까지에서, 저장하는 트랜잭션 밖에서 던진다. 부르는 쪽의 트랜잭션을 깨지 않는다.
            RuleSelection selection = evaluator.select(pack, change, scope);
            // 실패는 규칙 번호마다 하나만 알린다. 같은 번호의 규칙이 둘이면 평가가 둘을 따로 알릴 수 있다.
            List<RuleFailure> failures = new ArrayList<>();
            TreeSet<String> reported = new TreeSet<>(RulesOrder.STRINGS);
            for (RuleFailure failure : selection.failures()) {
                if (reported.add(failure.ruleNo())) {
                    failures.add(failure);
                }
            }
            Instant at = recordedAt.truncatedTo(ChronoUnit.MICROS);
            List<Action> actions = build(pack, change, edition, selection, at, failures);
            if (!failures.isEmpty()) {
                failures.sort((a, b) -> RulesOrder.compare(a.ruleNo(), b.ruleNo()));
                return new ActionGeneration(ActionGeneration.Outcome.RULE_FAILED, List.of(), failures);
            }
            boolean stored = Boolean.TRUE.equals(transaction.execute(status -> store(pack, change, scope, maxDepth, actions, at)));
            if (!stored) {
                // 그 사이에 다른 쪽이 같은 변동을 저장했다. 먼저 저장된 것이 남는다.
                return alreadyStored(pack, eventId, records.storedPackId(eventId).orElse(pack.packId()));
            }
            return new ActionGeneration(ActionGeneration.Outcome.STORED, actions, List.of());
        }
        catch (DataAccessException | TransactionException e) {
            throw new ActionStorageException("변동 이벤트 " + eventId
                    + " 의 영향 범위와 조치를 저장하지 못했습니다. 저장은 전부 되거나 전부 안 됩니다. 저장소의 상태를 확인한 뒤 다시 실행하세요."
                    + " 다시 실행해도 같은 조치가 두 번 생기지 않습니다.", e);
        }
        catch (CoreStorageException e) {
            // 규칙을 평가하려고 개체, 속성 값, 관계를 읽다가 저장소에서 읽지 못했다. 정의와 자료가 어긋난 것이 아니라
            // 저장소의 실패다 (D8 7절, D5 6절의 STORAGE_FAILURE, D2 AD-371). 아직 아무것도 저장하지 않았다.
            throw new ActionStorageException("변동 이벤트 " + eventId
                    + " 의 규칙을 평가하다가 저장소에서 읽지 못했습니다. 아무것도 저장하지 않았습니다. 저장소의 상태를 확인한 뒤 다시 실행하세요."
                    + " 다시 실행해도 같은 조치가 두 번 생기지 않습니다.", e);
        }
    }

    /** 이미 저장된 변동. 저장된 조치를 돌려준다. 다른 Pack 의 변동으로 저장된 식별자면 잘못 부른 것이다. */
    private ActionGeneration alreadyStored(PackDefinition pack, String eventId, String storedPackId) {
        if (!storedPackId.equals(pack.packId())) {
            throw new IllegalArgumentException("변동 이벤트 " + eventId + " 는 Pack " + storedPackId + " 의 것으로 이미 저장되어 있습니다."
                    + " 넘겨받은 Pack 은 " + pack.packId() + " 입니다. 변동 이벤트와 Pack 을 확인하세요.");
        }
        return new ActionGeneration(ActionGeneration.Outcome.ALREADY_STORED, records.actions(eventId), List.of());
    }

    /** 저장소가 그대로 담지 못하는 글자(NUL, 짝 없는 대리 문자)가 없는가. */
    static boolean storable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0) {
                return false;
            }
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            }
            else if (Character.isSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    static void requireStorable(String text, String name) {
        if (!storable(text)) {
            throw new IllegalArgumentException(name + "에 저장할 수 없는 글자(NUL 이나 짝 없는 대리 문자)가 들어 있습니다. 그 글자를 빼고 다시 보내세요.");
        }
    }

    /** 넘겨받은 영향 범위가 이 변동의 것으로 저장할 수 있는 모양인가. 아니면 잘못 부른 것이다. */
    private static void checkScope(RuleChange change, ImpactScope scope, int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("최대 깊이는 1 이상입니다: " + maxDepth + ". 영향 범위를 계산할 때 쓴 값을 넣어 다시 보내세요.");
        }
        LocalDate effective = change.effectiveDate();
        LocalDate previous = effective.minusDays(1);
        TreeSet<String> changeTargets = new TreeSet<>(RulesOrder.STRINGS);
        changeTargets.addAll(change.targetEntityIds());
        TreeSet<String> seen = new TreeSet<>(RulesOrder.STRINGS);
        for (ImpactTarget target : scope.targets()) {
            String where = "영향 대상 " + target.entityId() + " (변동 이벤트 " + change.changeEventId() + ")";
            if (changeTargets.contains(target.entityId())) {
                // 변동 대상 자신은 영향 대상이 아니다 (D2 AD-34). 변동 대상 전부에서 출발한 영향 범위가 아니다.
                throw new IllegalArgumentException(where + " 은 이 변동의 변동 대상입니다. 변동 대상 전부에서 출발해 계산한 영향 범위를 넘기세요.");
            }
            if (!seen.add(target.entityId())) {
                throw new IllegalArgumentException(where + " 이 영향 범위에 두 번 들어 있습니다. core 가 계산한 영향 범위를 그대로 넘기세요.");
            }
            if (target.path() == null || target.path().relations().isEmpty() || target.depth() != target.path().relations().size()) {
                throw new IllegalArgumentException(where + " 의 깊이와 근거 경로의 길이가 맞지 않습니다. core 가 계산한 영향 범위를 그대로 넘기세요.");
            }
            if (target.depth() > maxDepth) {
                throw new IllegalArgumentException(where + " 의 깊이 " + target.depth() + " 이 넘겨받은 최대 깊이 " + maxDepth
                        + " 보다 큽니다. 영향 범위를 계산할 때 쓴 최대 깊이를 넣어 다시 보내세요.");
            }
            String start = target.path().startEntityId();
            if (start == null || !changeTargets.contains(start)) {
                throw new IllegalArgumentException(where + " 의 근거 경로가 이 변동의 변동 대상이 아닌 개체 " + start
                        + " 에서 시작합니다. 이 변동의 변동 대상에서 출발해 계산한 영향 범위를 넘기세요.");
            }
            LocalDate asOf = target.path().asOf();
            if (!effective.equals(asOf) && !previous.equals(asOf)) {
                throw new IllegalArgumentException(where + " 의 근거 경로의 기준 날짜 " + asOf + " 가 판의 적용일 " + effective
                        + " 도 그 전날도 아닙니다. 이 변동의 두 날짜로 계산한 영향 범위를 넘기세요.");
            }
        }
    }

    /** 근거가 되는 주 개체가 저장소에 있고 이 Pack 의 것인가. 근거 없는 조치를 만들지 않는다. */
    private void checkPrimary(PackDefinition pack, RuleChange change) {
        if (!change.targetEntityIds().contains(change.primaryEntityId())) {
            // 주 개체는 그 행이 만든 개체 가운데 하나다 (D2 AD-43, AD-120).
            throw new IllegalArgumentException("변동 이벤트 " + change.changeEventId() + " 의 주 개체 " + change.primaryEntityId()
                    + " 가 변동 대상에 없습니다. 주 개체는 변동 대상 가운데 하나여야 합니다. 변동 이벤트를 확인하세요.");
        }
        Optional<EntityRef> primary = entities.find(change.primaryEntityId());
        if (primary.isEmpty() || !primary.get().packId().equals(pack.packId())) {
            throw new IllegalArgumentException("변동 이벤트 " + change.changeEventId() + " 의 주 개체 " + change.primaryEntityId()
                    + " 가 저장소에 없거나 Pack " + pack.packId() + " 의 것이 아닙니다. 변동 이벤트와 Pack 을 확인하세요.");
        }
    }

    /** 고른 (규칙, 대상)마다 조치 하나. 고른 차례 그대로다 (우선순위, 규칙 번호, 대상 식별자). */
    private static List<Action> build(PackDefinition pack, RuleChange change, BasisEdition edition, RuleSelection selection,
            Instant at, List<RuleFailure> failures) {
        // 규칙 번호로 찾는 데만 쓴다.
        TreeMap<String, RuleDefinition> rules = new TreeMap<>(RulesOrder.STRINGS);
        TreeSet<String> failed = new TreeSet<>(RulesOrder.STRINGS);
        // 평가에서 이미 실패한 규칙은 다시 알리지 않는다. 실패는 규칙마다 하나다.
        for (RuleFailure failure : selection.failures()) {
            failed.add(failure.ruleNo());
        }
        for (RuleDefinition rule : pack.rules()) {
            if (rules.put(rule.ruleNo(), rule) != null && failed.add(rule.ruleNo())) {
                // 같은 번호의 규칙이 둘이다. Pack 을 읽을 때 걸러진다. 검증을 거치지 않은 정의다.
                failures.add(new RuleFailure(rule.ruleNo(), "INVALID_RULE", null, null, null));
            }
        }
        List<Action> actions = new ArrayList<>();
        for (RuleMatch match : selection.matches()) {
            RuleDefinition rule = rules.get(match.ruleNo());
            if (failed.contains(rule.ruleNo())) {
                continue;
            }
            if (rule.label() == null || rule.label().isEmpty() || !storable(rule.label()) || rule.actionText() == null
                    || rule.actionText().isEmpty() || !storable(rule.actionText())) {
                // 이름이나 문구가 비었거나 저장할 수 없는 글자가 들어 있다. Pack 을 읽을 때 걸러지는 것이다.
                failed.add(rule.ruleNo());
                failures.add(new RuleFailure(rule.ruleNo(), "INVALID_RULE", null, null, null));
                continue;
            }
            LocalDate due = change.effectiveDate().plusDays(rule.dueInDays());
            if (rule.dueInDays() < 0 || due.isAfter(LAST_DATE)) {
                // 기한을 날짜로 담을 수 없다. 규칙마다 한 번만 알린다.
                failed.add(rule.ruleNo());
                // 틀린 것은 대상이 아니라 규칙에 적힌 일수다: 걸린 개체는 비우고 일수를 값으로 알린다 (D7 5절, D2 AD-371).
                failures.add(new RuleFailure(rule.ruleNo(), "DUE_DATE_OUT_OF_RANGE", null, null,
                        String.valueOf(rule.dueInDays())));
                continue;
            }
            actions.add(new Action(join(change.changeEventId(), rule.ruleNo(), match.targetEntityId()), pack.packId(),
                    change.changeEventId(), rule.ruleNo(), rule.label(), rule.actionText(), match.priority(),
                    match.appliesTo(), match.targetEntityId(), match.targetEntityType(), change.primaryEntityId(),
                    edition.inputSourceId(), edition.editionNo(), change.effectiveDate(), due, ActionStatus.PENDING, at));
        }
        return actions;
    }

    /** 트랜잭션 하나로 저장한다. 이미 저장된 변동이면 아무것도 넣지 않고 false. */
    private boolean store(PackDefinition pack, RuleChange change, ImpactScope scope, int maxDepth, List<Action> actions,
            Instant at) {
        String eventHash = RulesHash.sha256Hex(change.changeEventId());
        OffsetDateTime when = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        // 같은 변동을 두 곳에서 함께 저장하면 뒤의 것은 앞의 것이 끝나기를 기다렸다가 0 행이 된다.
        int inserted = jdbc.update("""
                insert into impact_scope (change_event_hash, change_event_id, pack_id, as_of_previous, as_of_effective,
                                          max_depth, truncated, stored_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (change_event_hash) do nothing
                """, eventHash, change.changeEventId(), pack.packId(), change.effectiveDate().minusDays(1),
                change.effectiveDate(), maxDepth, scope.truncated(), when);
        if (inserted == 0) {
            return false;
        }
        List<Object[]> targets = new ArrayList<>();
        List<Object[]> steps = new ArrayList<>();
        for (ImpactTarget target : scope.targets()) {
            String entityHash = RulesHash.sha256Hex(target.entityId());
            targets.add(new Object[] {eventHash, entityHash, target.entityId(), target.entityType(), target.depth(),
                    target.path().startEntityId(), target.path().asOf()});
            int stepNo = 0;
            for (Relation relation : target.path().relations()) {
                steps.add(new Object[] {eventHash, entityHash, ++stepNo, relation.id(), relation.relationType(),
                        relation.sourceEntityId(), relation.targetEntityId()});
            }
        }
        jdbc.batchUpdate("""
                insert into impact_target (change_event_hash, entity_hash, entity_id, entity_type, depth,
                                           path_start_entity_id, path_as_of)
                values (?, ?, ?, ?, ?, ?, ?)
                """, targets);
        jdbc.batchUpdate("""
                insert into impact_path_step (change_event_hash, entity_hash, step_no, relation_id, relation_type,
                                              source_entity_id, target_entity_id)
                values (?, ?, ?, ?, ?, ?, ?)
                """, steps);
        List<Object[]> rows = new ArrayList<>();
        for (Action action : actions) {
            String targetHash = RulesHash.sha256Hex(action.targetEntityId());
            rows.add(new Object[] {eventHash, action.ruleNo(), targetHash, action.id(), RulesHash.sha256Hex(action.id()),
                    action.appliesTo() == RuleSubject.IMPACT_TARGET ? targetHash : null, action.packId(), action.ruleLabel(), action.actionText(), action.priority(), action.appliesTo().name(),
                    action.targetEntityId(), action.targetEntityType(), action.basisEntityId(),
                    action.basisInputSourceId(), action.basisEditionNo(), action.effectiveDate(), action.dueDate(),
                    action.status().name(), when});
        }
        jdbc.batchUpdate("""
                insert into action (change_event_hash, rule_no, target_entity_hash, id, id_hash, impact_entity_hash,
                                    pack_id, rule_label, action_text,
                                    priority, applies_to, target_entity_id, target_entity_type, basis_entity_id,
                                    basis_input_source_id, basis_edition_no, as_of, due_date, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
        return true;
    }

    /** 식별자의 규칙으로 잇는다: 값을 | 로 잇고, 값 안의 | 와 \ 앞에 \ 를 붙인다 (D2 AD-65). */
    static String join(String... values) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                joined.append('|');
            }
            for (int j = 0; j < values[i].length(); j++) {
                char c = values[i].charAt(j);
                if (c == '|' || c == '\\') {
                    joined.append('\\');
                }
                joined.append(c);
            }
        }
        return joined.toString();
    }
}
