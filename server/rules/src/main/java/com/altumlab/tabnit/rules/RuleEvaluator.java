package com.altumlab.tabnit.rules;

import com.altumlab.tabnit.core.AttributeValue;
import com.altumlab.tabnit.core.AttributeValueQuery;
import com.altumlab.tabnit.core.EntityQuery;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.ImpactScope;
import com.altumlab.tabnit.core.ImpactTarget;
import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.RuleCompareTo;
import com.altumlab.tabnit.pack.RuleCondition;
import com.altumlab.tabnit.pack.RuleDefinition;
import com.altumlab.tabnit.pack.RuleOperator;
import com.altumlab.tabnit.pack.RuleSubject;
import com.altumlab.tabnit.pack.ValueFormat;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 규칙 실행기: 변동 하나의 영향 범위에 Pack 의 규칙을 적용해 해당하는 (규칙, 대상)을 고른다 (보드 B-05, D8 3절 ~ 4절).
 *
 * <p>고르기까지다. 조치를 만들거나 저장하지 않는다 (D2 AD-219). 규칙의 내용은 Pack 의 정의에서만 온다.
 * 특정 규칙의 조건을 알지 못한다. 대상의 속성 값과 변동 대상의 유형은 core 의 공개 조회로 읽는다 (D2 AD-220).
 * 영향 범위를 계산하지 않는다: 계산된 것을 받는다.
 *
 * <p>같은 입력에는 같은 결과가 같은 차례로 나온다. 현재 시각을 읽지 않는다. 날짜는 판의 적용일에서만 온다.
 */
public class RuleEvaluator {

    private static final LocalDate EARLIEST = LocalDate.of(1, 1, 1);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);

    /** 규칙을 평가하는 차례: 우선순위(작은 수가 먼저), 규칙 번호 (D2 AD-103). */
    private static final Comparator<RuleDefinition> RULES = Comparator.comparingInt(RuleDefinition::priority)
        .thenComparing(RuleDefinition::ruleNo, RulesOrder.STRINGS);

    /** 돌려주는 차례: 우선순위, 규칙 번호, 대상 개체의 식별자 (D2 AD-222). */
    private static final Comparator<RuleMatch> MATCHES = Comparator.comparingInt(RuleMatch::priority)
        .thenComparing(RuleMatch::ruleNo, RulesOrder.STRINGS)
        .thenComparing(RuleMatch::targetEntityId, RulesOrder.STRINGS);

    private final EntityQuery entities;

    private final AttributeValueQuery attributeValues;

    /**
     * 읽기는 한 시점의 것으로 한다. 평가하는 사이에 새 판이 들어와도 값이 섞이지 않는다.
     * 부르는 쪽이 이미 트랜잭션 안이면 그 트랜잭션에 든다: 그때의 "한 시점"은 부르는 쪽의 격리 수준이 정한다.
     * 영향 범위는 이 밖에서 계산된 것을 받으므로, 영향 범위의 계산과 이 평가를 같은 시점으로 묶는 것도 부르는 쪽의 일이다.
     */
    private final TransactionTemplate snapshot;

    public RuleEvaluator(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
        this.entities = new EntityQuery(dataSource);
        this.attributeValues = new AttributeValueQuery(dataSource);
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 변동 하나에 규칙을 적용한다.
     *
     * @param pack Pack 의 정의. 규칙, 개체 유형의 위아래, 속성의 형식을 여기서 읽는다
     * @param change 변동. 변동의 종류, 변동 대상, 판의 적용일
     * @param scope 그 변동의 영향 범위. core 가 변동 대상에서 출발해 적용일 전날과 적용일로 계산한 것이다 (D2 AD-100)
     * @return 해당하는 (규칙, 대상)과 평가에 실패한 규칙
     * @throws IllegalArgumentException 변동 대상이 없거나, 저장소에 없는 개체이거나, 이 Pack 의 것이 아니거나,
     *         영향 범위가 이 변동 대상에서 출발한 것이 아닐 때
     */
    public RuleSelection select(PackDefinition pack, RuleChange change, ImpactScope scope) {
        Objects.requireNonNull(pack, "Pack 의 정의는 반드시 있어야 합니다");
        Objects.requireNonNull(change, "변동은 반드시 있어야 합니다");
        Objects.requireNonNull(scope, "영향 범위는 반드시 있어야 합니다");
        if (change.targetEntityIds().isEmpty()) {
            throw new IllegalArgumentException("변동 대상이 없습니다 (변동 이벤트 " + change.changeEventId()
                    + "). 변동 대상을 하나 이상 넣어 다시 보내세요.");
        }
        // 잘못 부른 것(IllegalArgumentException)은 읽기 트랜잭션 밖에서 던진다. 안에서 던지면, 부르는 쪽이 이미
        // 트랜잭션 안일 때 그 트랜잭션까지 되돌려야 하는 것으로 표시되어, 예외를 잡고 계속하려던 부르는 쪽의 저장이
        // 사라진다. 저장소에서 읽지 못한 것(core 의 예외)은 그대로 안에서 나간다.
        Object result = snapshot.execute(status -> {
            try {
                return new Run(pack, change, scope).select();
            }
            catch (IllegalArgumentException e) {
                return e;
            }
        });
        if (result instanceof IllegalArgumentException wrongCall) {
            throw wrongCall;
        }
        return (RuleSelection) result;
    }

    /** 평가에 실패했다. 그 규칙의 평가를 끊는 데만 쓰고 밖으로 나가지 않는다. */
    private static final class RuleFailed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final transient RuleFailure failure;

        RuleFailed(RuleFailure failure) {
            super(null, null, false, false);
            this.failure = failure;
        }
    }

    /** 변동 하나를 평가하는 동안의 상태. */
    private final class Run {

        private final PackDefinition pack;

        private final RuleChange change;

        private final ImpactScope scope;

        private final TreeMap<String, EntityTypeDefinition> entityTypes = new TreeMap<>(RulesOrder.STRINGS);

        private final TreeMap<String, AttributeDefinition> attributes = new TreeMap<>(RulesOrder.STRINGS);

        /** 변동 대상. 식별자 순. */
        private final TreeMap<String, EntityRef> changeTargets = new TreeMap<>(RulesOrder.STRINGS);

        /** 유형마다 자신과 그 위의 유형들. 찾는 데만 쓴다. */
        private final TreeMap<String, TreeSet<String>> ancestors = new TreeMap<>(RulesOrder.STRINGS);

        /** 읽은 속성 값. (개체, 속성)마다 한 번만 읽는다. */
        private final TreeMap<String, Optional<String>> read = new TreeMap<>(RulesOrder.STRINGS);

        Run(PackDefinition pack, RuleChange change, ImpactScope scope) {
            this.pack = pack;
            this.change = change;
            this.scope = scope;
            pack.entityTypes().forEach(type -> entityTypes.put(type.name(), type));
            pack.attributes().forEach(attribute -> attributes.put(attribute.name(), attribute));
        }

        RuleSelection select() {
            loadChangeTargets();
            // 영향 범위가 이 변동의 것인지는 규칙을 보기 전에 한 번 확인한다. 어떤 규칙이 있는가에 달라지지 않게 한다.
            for (ImpactTarget target : scope.targets()) {
                String startId = target.path().startEntityId();
                if (!changeTargets.containsKey(startId)) {
                    throw new IllegalArgumentException("영향 대상 " + target.entityId() + " 의 근거 경로가 변동 대상이 아닌 "
                            + startId + " 에서 시작합니다. 이 변동의 변동 대상에서 계산한 영향 범위를 넘기세요.");
                }
            }
            List<RuleDefinition> rules = new ArrayList<>(pack.rules());
            rules.sort(RULES);
            List<RuleMatch> matches = new ArrayList<>();
            List<RuleFailure> failures = new ArrayList<>();
            for (RuleDefinition rule : rules) {
                if (!rule.changeKinds().contains(change.kind())) {
                    continue;
                }
                try {
                    if (!appliesToChange(rule)) {
                        continue;
                    }
                    // 규칙 하나의 결과는 따로 모았다가 끝까지 평가된 뒤에만 더한다. 도중에 실패하면 하나도 내지 않는다
                    // (D2 AD-221).
                    matches.addAll(apply(rule));
                }
                catch (RuleFailed failed) {
                    // 다른 규칙은 계속 평가한다. 조용히 건너뛰지 않고 실패한 규칙으로 알린다 (D8 7절).
                    failures.add(failed.failure);
                }
            }
            matches.sort(MATCHES);
            failures.sort(Comparator.comparing(RuleFailure::ruleNo, RulesOrder.STRINGS));
            return new RuleSelection(matches, failures);
        }

        /**
         * 이 변동이 규칙이 적은 입력 자료에서 나왔고, 규칙이 적은 것 가운데 하나가 그 행에서 바뀌었는가 (D2 AD-290, AD-291).
         * 적지 않은 규칙은 가리지 않는다. 규칙이 가리는데 변동이 그것을 모르면, 조용히 건너뛰지 않고 실패로 알린다.
         */
        private boolean appliesToChange(RuleDefinition rule) {
            if (!rule.inputSources().isEmpty()) {
                if (change.inputSourceId() == null) {
                    throw new RuleFailed(new RuleFailure(rule.ruleNo(), "INPUT_SOURCE_UNKNOWN", null, null, null));
                }
                if (!rule.inputSources().contains(change.inputSourceId())) {
                    return false;
                }
            }
            if (rule.limitsChangedItems()) {
                if (!change.changedItemsKnown()) {
                    throw new RuleFailed(new RuleFailure(rule.ruleNo(), "CHANGED_ITEMS_UNKNOWN", null, null, null));
                }
                return rule.changedAttributes().stream().anyMatch(change.changedAttributes()::contains)
                        || rule.changedRelationTypes().stream().anyMatch(change.changedRelationTypes()::contains);
            }
            return true;
        }

        /** 변동 대상을 저장소에서 읽어 유형을 안다. 변동 이벤트는 변동 대상을 식별자로만 담는다 (D2 AD-218). */
        private void loadChangeTargets() {
            for (EntityRef entity : entities.findAll(change.targetEntityIds())) {
                changeTargets.put(entity.id(), entity);
            }
            TreeSet<String> asked = new TreeSet<>(RulesOrder.STRINGS);
            asked.addAll(change.targetEntityIds());
            for (String id : asked) {
                EntityRef entity = changeTargets.get(id);
                if (entity == null) {
                    throw new IllegalArgumentException("변동 대상 " + id + " 이(가) 저장소에 없습니다 (변동 이벤트 "
                            + change.changeEventId() + "). 변동 이벤트와 자료가 어긋났습니다. 식별자를 확인하세요.");
                }
                if (!entity.packId().equals(pack.packId())) {
                    throw new IllegalArgumentException("변동 대상 " + id + " 은(는) Pack " + entity.packId() + " 의 개체입니다. 넘긴 정의는 Pack "
                            + pack.packId() + " 의 것입니다. 같은 Pack 의 정의와 변동을 넘기세요.");
                }
            }
        }

        /** 규칙 하나를 그 규칙의 대상들에 적용한다. */
        private List<RuleMatch> apply(RuleDefinition rule) {
            List<RuleMatch> matches = new ArrayList<>();
            if (rule.appliesTo() == RuleSubject.IMPACT_TARGET) {
                // 영향 범위가 준 차례(깊이, 개체 유형, 식별자)대로 본다 (D2 AD-61).
                for (ImpactTarget target : scope.targets()) {
                    // 조건이 보는 변동 대상은 이 영향 대상의 근거 경로가 시작한 변동 대상이다 (D2 AD-64).
                    EntityRef start = changeTargets.get(target.path().startEntityId());
                    if (isA(target.entityType(), rule.targetType())
                            && satisfies(rule, target.entityId(), target.entityType(), start)) {
                        matches.add(new RuleMatch(rule.ruleNo(), rule.priority(), RuleSubject.IMPACT_TARGET,
                                target.entityId(), target.entityType()));
                    }
                }
            }
            else {
                // 변동 대상을 개체 유형, 식별자 순으로 본다 (D2 AD-102).
                List<EntityRef> targets = new ArrayList<>(changeTargets.values());
                targets.sort(Comparator.comparing(EntityRef::entityType, RulesOrder.STRINGS)
                    .thenComparing(EntityRef::id, RulesOrder.STRINGS));
                for (EntityRef target : targets) {
                    // 대상이 변동 대상 자신이다. 조건이 보는 변동 대상도 그 개체다. 영향 대상을 보는 조건은 Pack 을
                    // 읽을 때 거부된다 (D2 AD-118).
                    if (isA(target.entityType(), rule.targetType())
                            && satisfies(rule, target.id(), target.entityType(), target)) {
                        matches.add(new RuleMatch(rule.ruleNo(), rule.priority(), RuleSubject.CHANGE_TARGET,
                                target.id(), target.entityType()));
                    }
                }
            }
            return matches;
        }

        /**
         * 조건을 모두 만족하는가 (D2 AD-97).
         *
         * <p>유형을 보는 조건을 먼저 본다. 거기서 걸러진 대상은 더 보지 않는다. 남은 대상에서는 속성 값을 보는 조건을
         * 하나가 거짓이어도 끝까지 평가한다: 틀린 값이 하나라도 있으면 어느 조건을 먼저 보든 그 규칙은 실패다.
         * 조건의 차례(속성의 이름)에 실패 여부가 달라지지 않는다 (D2 AD-224).
         */
        private boolean satisfies(RuleDefinition rule, String targetId, String targetType, EntityRef changeTarget) {
            for (RuleCondition condition : rule.conditions()) {
                if (condition.typeIs() == null && condition.attributeName() == null) {
                    throw invalid(rule, null);
                }
                boolean impactSide = condition.subject() == RuleSubject.IMPACT_TARGET;
                if (condition.typeIs() != null
                        && !isA(impactSide ? targetType : changeTarget.entityType(), condition.typeIs())) {
                    return false;
                }
            }
            boolean all = true;
            for (RuleCondition condition : rule.conditions()) {
                if (condition.typeIs() == null) {
                    boolean impactSide = condition.subject() == RuleSubject.IMPACT_TARGET;
                    String entityId = impactSide ? targetId : changeTarget.id();
                    if (condition.compareTo() == RuleCompareTo.VALUE) {
                        all &= compare(rule, condition, entityId);
                    }
                    else {
                        // 대상이 변동 대상인 규칙에는 영향 대상이 없다. 그 속성과 견주는 조건은 Pack 을 읽을 때 거부된다.
                        if (condition.compareSubject() == RuleSubject.IMPACT_TARGET
                                && rule.appliesTo() != RuleSubject.IMPACT_TARGET) {
                            throw invalid(rule, condition.attributeName());
                        }
                        String otherId = condition.compareSubject() == RuleSubject.IMPACT_TARGET ? targetId
                                : changeTarget.id();
                        all &= compareOther(rule, condition, entityId, otherId);
                    }
                }
            }
            return all;
        }

        /** 속성 값을 보는 조건 하나. 비교는 속성의 형식에 맞춰 한다 (D2 AD-71). */
        private boolean compare(RuleDefinition rule, RuleCondition condition, String entityId) {
            String name = condition.attributeName();
            AttributeDefinition attribute = attributes.get(name);
            if (attribute == null) {
                throw new RuleFailed(new RuleFailure(rule.ruleNo(), "UNKNOWN_ATTRIBUTE", null, name, null));
            }
            RuleOperator operator = condition.operator();
            ValueFormat format = attribute.valueFormat();
            boolean none = operator == RuleOperator.HAS_VALUE || operator == RuleOperator.HAS_NO_VALUE;
            int valueCount = condition.values().size();
            if (operator == null || format == null || (none ? valueCount != 0
                    : operator == RuleOperator.IN ? valueCount < 1 : valueCount != 1)) {
                // Pack 을 읽을 때 걸러진다. 여기 오면 넘겨받은 정의가 검증을 거치지 않은 것이다.
                throw invalid(rule, name);
            }
            Optional<String> stored = valueOf(entityId, name);
            if (operator == RuleOperator.HAS_VALUE) {
                return stored.isPresent();
            }
            if (operator == RuleOperator.HAS_NO_VALUE) {
                return stored.isEmpty();
            }
            if (stored.isEmpty()) {
                // 값이 없으면 "값이 없다" 말고는 어떤 비교도 만족하지 않는다. "다르다"도 그렇다 (D2 AD-104).
                return false;
            }
            Object value = parse(format, stored.get());
            if (value == null) {
                // 저장된 값이 속성의 형식과 맞지 않는다. 자료와 정의가 어긋난 것이다 (D8 7절).
                throw new RuleFailed(
                        new RuleFailure(rule.ruleNo(), "VALUE_FORMAT_MISMATCH", entityId, name, stored.get()));
            }
            List<Object> expected = new ArrayList<>();
            for (String text : condition.values()) {
                Object parsed = parse(format, text);
                if (parsed == null) {
                    // Pack 을 읽을 때 걸러진다. 여기 오면 넘겨받은 정의가 검증을 거치지 않은 것이다.
                    throw new RuleFailed(new RuleFailure(rule.ruleNo(), "RULE_VALUE_FORMAT_MISMATCH", null, name, text));
                }
                expected.add(parsed);
            }
            return switch (operator) {
                case EQUALS -> order(value, expected.get(0)) == 0;
                case NOT_EQUALS -> order(value, expected.get(0)) != 0;
                case GREATER_THAN -> order(value, expected.get(0)) > 0;
                case GREATER_THAN_OR_EQUAL -> order(value, expected.get(0)) >= 0;
                case LESS_THAN -> order(value, expected.get(0)) < 0;
                case LESS_THAN_OR_EQUAL -> order(value, expected.get(0)) <= 0;
                case IN -> expected.stream().anyMatch(candidate -> order(value, candidate) == 0);
                case HAS_VALUE, HAS_NO_VALUE -> throw new IllegalStateException("위에서 처리했다");
            };
        }

        /**
         * 속성 값을 판의 적용일이나 다른 대상의 속성 값과 견주는 조건 하나 (D2 AD-292).
         *
         * <p>두 쪽의 값을 모두 읽고 형식을 확인한 뒤에 견준다: 한쪽에 값이 없어도 다른 쪽의 틀린 값은 실패로 알린다.
         * 어느 한쪽에 값이 없으면 만족하지 않는다. "다르다"도 그렇다 (D2 AD-104).
         */
        private boolean compareOther(RuleDefinition rule, RuleCondition condition, String entityId, String otherId) {
            String name = condition.attributeName();
            AttributeDefinition attribute = attributes.get(name);
            if (attribute == null) {
                throw new RuleFailed(new RuleFailure(rule.ruleNo(), "UNKNOWN_ATTRIBUTE", null, name, null));
            }
            RuleOperator operator = condition.operator();
            ValueFormat format = attribute.valueFormat();
            boolean ordering = operator == RuleOperator.GREATER_THAN || operator == RuleOperator.GREATER_THAN_OR_EQUAL
                    || operator == RuleOperator.LESS_THAN || operator == RuleOperator.LESS_THAN_OR_EQUAL;
            boolean ordered = format == ValueFormat.INTEGER || format == ValueFormat.DECIMAL || format == ValueFormat.DATE;
            if (format == null || !condition.values().isEmpty()
                    || !(ordering ? ordered : operator == RuleOperator.EQUALS || operator == RuleOperator.NOT_EQUALS)) {
                // Pack 을 읽을 때 걸러진다. 여기 오면 넘겨받은 정의가 검증을 거치지 않은 것이다.
                throw invalid(rule, name);
            }
            Object other;
            if (condition.compareTo() == RuleCompareTo.EFFECTIVE_DATE) {
                if (format != ValueFormat.DATE || condition.compareSubject() != null
                        || condition.compareAttributeName() != null) {
                    throw invalid(rule, name);
                }
                other = change.effectiveDate();
            }
            else {
                String otherName = condition.compareAttributeName();
                if (condition.compareSubject() == null || otherName == null) {
                    throw invalid(rule, name);
                }
                AttributeDefinition otherAttribute = attributes.get(otherName);
                if (otherAttribute == null) {
                    throw new RuleFailed(new RuleFailure(rule.ruleNo(), "UNKNOWN_ATTRIBUTE", null, otherName, null));
                }
                if (otherAttribute.valueFormat() != format) {
                    throw invalid(rule, name);
                }
                other = stored(rule, format, otherId, otherName);
            }
            Object value = stored(rule, format, entityId, name);
            if (value == null || other == null) {
                return false;
            }
            int order = order(value, other);
            return switch (operator) {
                case EQUALS -> order == 0;
                case NOT_EQUALS -> order != 0;
                case GREATER_THAN -> order > 0;
                case GREATER_THAN_OR_EQUAL -> order >= 0;
                case LESS_THAN -> order < 0;
                case LESS_THAN_OR_EQUAL -> order <= 0;
                case IN, HAS_VALUE, HAS_NO_VALUE -> throw new IllegalStateException("위에서 걸렀다");
            };
        }

        /** 저장된 속성 값을 그 형식의 값으로 읽는다. 값이 없으면 null. 형식과 맞지 않으면 그 규칙은 실패다 (D8 7절). */
        private Object stored(RuleDefinition rule, ValueFormat format, String entityId, String attributeName) {
            Optional<String> stored = valueOf(entityId, attributeName);
            if (stored.isEmpty()) {
                return null;
            }
            Object value = parse(format, stored.get());
            if (value == null) {
                throw new RuleFailed(new RuleFailure(rule.ruleNo(), "VALUE_FORMAT_MISMATCH", entityId, attributeName,
                        stored.get()));
            }
            return value;
        }

        /** 규칙의 정의가 D8 의 모양이 아니다. 검증을 거친 정의에서는 생기지 않는다. 예외로 죽지 않고 실패로 알린다. */
        private RuleFailed invalid(RuleDefinition rule, String attributeName) {
            return new RuleFailed(new RuleFailure(rule.ruleNo(), "INVALID_RULE", null, attributeName, null));
        }

        /**
         * 조건이 보는 속성 값: 적용일의 값이고, 적용일에 값이 없으면 전날의 값이다. 둘 다 없을 때만 값 없음이다
         * (D2 AD-101).
         */
        private Optional<String> valueOf(String entityId, String attributeName) {
            String key = entityId + "\u0000" + attributeName;
            Optional<String> known = read.get(key);
            if (known != null) {
                return known;
            }
            LocalDate date = change.effectiveDate();
            Optional<String> value = attributeValues.valueAsOf(entityId, attributeName, date).map(AttributeValue::value);
            if (value.isEmpty() && date.isAfter(EARLIEST)) {
                value = attributeValues.valueAsOf(entityId, attributeName, date.minusDays(1)).map(AttributeValue::value);
            }
            read.put(key, value);
            return value;
        }

        /** type 이 ancestor 자신이거나 그 하위 유형인가. 여러 단계 아래도 해당한다. 돌아오는 정의가 있어도 끝난다 (D2 AD-88). */
        private boolean isA(String type, String ancestor) {
            if (type == null || ancestor == null) {
                return false;
            }
            return ancestors.computeIfAbsent(type, this::selfAndAncestors).contains(ancestor);
        }

        /** 유형 자신과 그 위의 모든 유형. 유형마다 한 번만 걷는다. 겹쳐 부르지 않고 차례로 걷는다. */
        private TreeSet<String> selfAndAncestors(String type) {
            TreeSet<String> seen = new TreeSet<>(RulesOrder.STRINGS);
            ArrayDeque<String> open = new ArrayDeque<>();
            open.add(type);
            while (!open.isEmpty()) {
                String current = open.remove();
                if (seen.add(current)) {
                    EntityTypeDefinition definition = entityTypes.get(current);
                    if (definition != null) {
                        open.addAll(definition.parents());
                    }
                }
            }
            return seen;
        }
    }

    /**
     * 글자를 속성의 형식의 값으로 읽는다. 설치한 곳의 언어 설정에 기대지 않는다.
     *
     * @return 정수는 BigInteger, 소수는 BigDecimal, 날짜는 LocalDate, 참·거짓은 Boolean, 글자는 String.
     *         형식과 맞지 않으면 null
     */
    private static Object parse(ValueFormat format, String text) {
        try {
            return switch (format) {
                case STRING -> text;
                case INTEGER -> plain(text, false) ? new BigInteger(unsigned(text)) : null;
                case DECIMAL -> plain(text, true) ? new BigDecimal(unsigned(text)) : null;
                case DATE -> {
                    // 저장소와 Pack 이 받는 범위(0001 ~ 9999 년)의 날짜만 날짜로 읽는다.
                    LocalDate date = LocalDate.parse(text, DATE);
                    yield date.getYear() < 1 || date.getYear() > 9999 ? null : date;
                }
                case BOOLEAN -> text.equals("true") ? Boolean.TRUE : text.equals("false") ? Boolean.FALSE : null;
            };
        }
        catch (NumberFormatException | DateTimeParseException e) {
            return null;
        }
    }

    /** 부호 하나와 숫자(0 ~ 9), 소수이면 마침표 하나로만 이루어졌는가. 숫자가 하나는 있어야 한다. 지수 표기는 받지 않는다. */
    private static boolean plain(String text, boolean allowPoint) {
        int digits = 0;
        int points = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            }
            else if (c == '.' && allowPoint) {
                points++;
            }
            else if (!((c == '+' || c == '-') && i == 0)) {
                return false;
            }
        }
        return digits > 0 && points <= 1;
    }

    private static String unsigned(String text) {
        return text.startsWith("+") ? text.substring(1) : text;
    }

    /** 같은 형식의 두 값을 견준다. 수는 값으로 (1.50 과 1.5 는 같다), 날짜는 날짜로, 글자는 글자 코드 순으로. */
    private static int order(Object left, Object right) {
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) {
            return a.compareTo(b);
        }
        if (left instanceof BigInteger a && right instanceof BigInteger b) {
            return a.compareTo(b);
        }
        if (left instanceof LocalDate a && right instanceof LocalDate b) {
            return a.compareTo(b);
        }
        if (left instanceof Boolean a && right instanceof Boolean b) {
            return Boolean.compare(a, b);
        }
        return RulesOrder.compare((String) left, (String) right);
    }
}
