package com.altumlab.tabnit.pack;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pack 적재: 폴더를 읽고, 검증하고, 정의를 저장소에 넣는다 (D6 9절).
 *
 * <p>적재하는 것은 정의뿐이다. 개체와 관계는 넣지 않는다 (D2 AD-45). 문제가 하나라도 있으면 아무것도 넣지 않는다.
 * 적재는 트랜잭션 하나라서 전부 되거나 전부 안 된다.
 */
public class PackLoader {

    private final JdbcTemplate jdbc;

    private final PackTables tables;

    private final TransactionTemplate transaction;

    public PackLoader(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "데이터 원본은 반드시 있어야 합니다");
        this.jdbc = new JdbcTemplate(dataSource);
        this.tables = new PackTables(jdbc);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * Pack 폴더 하나를 적재한다.
     *
     * @param packDirectory Pack 폴더. 폴더 이름이 Pack 식별자다
     * @param loadedAt 적재한 때. 밖에서 받아 넘긴다
     * @return 처음 적재했는지, 고친 것으로 바꿨는지, 같아서 그대로 뒀는지
     * @throws PackInvalidException Pack 이 형식에 맞지 않거나 정의가 서로 맞지 않을 때. 문제 전부를 담는다
     * @throws PackChangeRejectedException 이미 적재된 Pack 을 받을 수 없는 방식으로 고쳤을 때
     * @throws PackStorageException 저장소에 쓰지 못했을 때
     */
    public PackLoadResult load(Path packDirectory, Instant loadedAt) {
        return load(packDirectory, loadedAt, null);
    }

    /**
     * Pack 폴더 하나를 적재한다. 판이 들어온 입력 자료를 알려 주면, 그 밖의 입력 자료의 매핑은 바꾸거나 지울 수 있다
     * (D2 AD-142, AD-159).
     *
     * @param inputSourcesWithEditions 이 Pack 에서 판이 하나라도 들어온 입력 자료의 식별자. 순서를 잡는 쪽(api)이 link 에
     *        물어 넘긴다. null 이면 어느 것에 판이 있는지 모르는 것이라, 매핑을 바꾸거나 지우는 것을 모두 거부한다
     */
    public PackLoadResult load(Path packDirectory, Instant loadedAt, Collection<String> inputSourcesWithEditions) {
        Objects.requireNonNull(packDirectory, "Pack 폴더는 반드시 있어야 합니다");
        Objects.requireNonNull(loadedAt, "적재한 때는 반드시 있어야 합니다");
        PackReader.Result read = PackReader.read(packDirectory, loadedAt);
        if (!read.problems().isEmpty()) {
            throw new PackInvalidException(packDirectory.toString(), read.problems());
        }
        PackDefinition pack = read.definition();
        try {
            return transaction.execute(status -> store(pack, read.warnings(), inputSourcesWithEditions));
        }
        catch (DataAccessException | TransactionException e) {
            throw new PackStorageException("Pack 을 저장하지 못했습니다. Pack: " + pack.packId()
                    + ". 저장소의 상태를 확인한 뒤 다시 적재하세요. 저장된 정의는 적재 전과 같습니다.", e);
        }
    }

    private PackLoadResult store(PackDefinition pack, List<PackWarning> warnings,
            Collection<String> inputSourcesWithEditions) {
        // 같은 Pack 을 동시에 적재하면 한쪽이 기다린다. 트랜잭션이 끝나면 풀린다.
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> {
        }, "tabnit-pack:" + pack.packId());
        Optional<PackDefinition> existing = tables.read(pack.packId());
        if (existing.isEmpty()) {
            tables.insert(pack);
            return new PackLoadResult(pack.packId(), PackLoadResult.Outcome.LOADED, warnings);
        }
        if (existing.get().sameContentAs(pack)) {
            return new PackLoadResult(pack.packId(), PackLoadResult.Outcome.UNCHANGED, warnings);
        }
        List<String> reasons = rejectedChanges(existing.get(), pack, inputSourcesWithEditions);
        if (!reasons.isEmpty()) {
            throw new PackChangeRejectedException(pack.packId(), reasons);
        }
        tables.delete(pack.packId());
        tables.insert(pack);
        return new PackLoadResult(pack.packId(), PackLoadResult.Outcome.UPDATED, warnings);
    }

    /**
     * 고친 Pack 에서 받지 않는 변경을 찾는다 (D6 9절 "고친 Pack 을 다시 적재할 때", D2 AD-95).
     *
     * <p>받는 것: 정의를 더함, 이름표·표시 이름 속성·영향 방향·최대 깊이의 기본값을 바꿈 (D2 AD-129).
     * 거부하는 것: 상위 유형, 관계 유형의 출발·도착 유형, 속성의 개체 유형과 형식을 바꿈.
     * 지우는 것은 자료가 쓰지 않으면 받아야 하지만 (D2 AD-130), 자료가 쓰는지 답하는 core 의 공개 API 가
     * 아직 없어 지금은 모두 거부한다.
     */
    private static List<String> rejectedChanges(PackDefinition before, PackDefinition after,
            Collection<String> inputSourcesWithEditions) {
        List<String> reasons = new ArrayList<>();
        Map<String, EntityTypeDefinition> types = new TreeMap<>(PackOrder.STRINGS);
        after.entityTypes().forEach(t -> types.put(t.name(), t));
        for (EntityTypeDefinition old : before.entityTypes()) {
            EntityTypeDefinition now = types.get(old.name());
            if (now == null) {
                reasons.add("개체 유형 " + old.name() + " 이(가) 없어졌습니다. 이름을 바꾸는 것은 받지 않고, 지우는 것은 아직 받지 않습니다.");
            }
            else if (!now.parents().equals(old.parents())) {
                reasons.add("개체 유형 " + old.name() + " 의 상위 유형이 바뀌었습니다. 이 변경은 받지 않습니다. 새 이름의 유형을 더하세요.");
            }
        }
        Map<String, RelationTypeDefinition> relations = new TreeMap<>(PackOrder.STRINGS);
        after.relationTypes().forEach(r -> relations.put(r.name(), r));
        for (RelationTypeDefinition old : before.relationTypes()) {
            RelationTypeDefinition now = relations.get(old.name());
            if (now == null) {
                reasons.add("관계 유형 " + old.name() + " 이(가) 없어졌습니다. 이름을 바꾸는 것은 받지 않고, 지우는 것은 아직 받지 않습니다.");
            }
            else if (!now.sourceType().equals(old.sourceType()) || !now.targetType().equals(old.targetType())) {
                reasons.add("관계 유형 " + old.name() + " 의 출발 유형이나 도착 유형이 바뀌었습니다. 이 변경은 받지 않습니다. 새 이름의 관계 유형을 더하세요.");
            }
        }
        Map<String, AttributeDefinition> attributes = new TreeMap<>(PackOrder.STRINGS);
        after.attributes().forEach(a -> attributes.put(a.name(), a));
        for (AttributeDefinition old : before.attributes()) {
            AttributeDefinition now = attributes.get(old.name());
            if (now == null) {
                reasons.add("속성 " + old.name() + " 이(가) 없어졌습니다. 이름을 바꾸는 것은 받지 않고, 지우는 것은 아직 받지 않습니다.");
            }
            else if (now.valueFormat() != old.valueFormat() || !now.entityType().equals(old.entityType())) {
                reasons.add("속성 " + old.name() + " 의 형식이나 개체 유형이 바뀌었습니다. 이 변경은 받지 않습니다. 새 이름의 속성을 더하세요.");
            }
        }
        // 매핑을 더하는 것은 받는다. 판이 하나도 들어오지 않은 입력 자료의 매핑은 바꾸거나 지울 수 있다. 판이 하나라도 들어온
        // 입력 자료의 매핑은 바꾸거나 지울 수 없다: 이미 계산한 변동과 조치의 근거가 흔들린다 (D2 AD-142).
        // 어느 것에 판이 있는지 받지 못했으면 모두 판이 있는 것으로 본다.
        Map<String, MappingDefinition> mappings = new TreeMap<>(PackOrder.STRINGS);
        after.mappings().forEach(m -> mappings.put(m.inputSourceId(), m));
        for (MappingDefinition old : before.mappings()) {
            MappingDefinition now = mappings.get(old.inputSourceId());
            // 이름표만 다른 것은 바뀐 것이 아니다: 화면에 보이는 글자다 (D2 AD-384).
            if (now != null && now.readsLike(old)) {
                continue;
            }
            if (inputSourcesWithEditions != null && !inputSourcesWithEditions.contains(old.inputSourceId())) {
                continue;
            }
            String why = inputSourcesWithEditions == null ? "판이 들어왔는지 확인하지 못해" : "이미 판이 들어온 입력 자료라";
            reasons.add("입력 자료 " + old.inputSourceId() + " 의 매핑이 " + (now == null ? "없어졌습니다" : "바뀌었습니다") + ". " + why
                    + " 매핑을 바꾸거나 지울 수 없습니다. 매핑을 되돌리고, 바꾸려는 내용은 새 식별자의 입력 자료로 더하세요.");
        }
        return reasons;
    }
}
