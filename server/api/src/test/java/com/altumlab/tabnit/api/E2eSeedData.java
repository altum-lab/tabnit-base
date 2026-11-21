package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.NewRelation;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.RelationStore;
import com.altumlab.tabnit.core.ValidityPeriod;
import java.time.Instant;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 화면 흐름 시험(web/tests)이 보는 지어낸 자료. 시험용 실행(bootTestRun)에서만 넣는다.
 *
 * <p>이 클래스는 시험 쪽 코드라 배포물에 들어가지 않는다. 관계 조회 화면의 자료는 core 에 직접 넣은 지어낸 값이고
 * 어느 Pack 의 것도 아니다. 변동 감지 화면의 자료는 시험용 Pack 의 사본을 적재해 둔다.
 */
@TestConfiguration(proxyBeanMethods = false)
class E2eSeedData {

	/** 기록한 때는 밖에서 받아 넘기는 값이다. 시험 자료에서는 고정한다. */
	private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

	private static final String PACK = "e2e";

	/** 화면 시험(web/tests/changes.spec.ts)이 고르는 Pack 의 식별자. */
	private static final String CHANGES_PACK = "e2e-changes";

	private static final Provenance SRC_1 = new Provenance("src", 1);

	@Bean
	ApplicationRunner e2eSeed(DataSource dataSource) {
		return args -> {
			RelationStore store = new RelationStore(dataSource);

			// D3 5절의 예시와 같은 모양: 기준 날짜에 따라 유효한 관계가 달라진다.
			EntityRef ga = entity(store, "ga");
			relation(store, ga, entity(store, "na"), "R", "2026-03-01", "2026-06-01");
			relation(store, ga, entity(store, "da"), "R", "2026-06-01", null);
			relation(store, ga, entity(store, "ra"), "R", "2026-01-01", "2026-03-01");

			// 순서를 보는 자료: 관계 유형, 상대 개체, 관계 식별자 순. 넣는 순서는 일부러 섞는다.
			EntityRef me = entity(store, "me");
			EntityRef x = entity(store, "x");
			EntityRef y = entity(store, "y");
			relation(store, me, y, "b-type", "2020-01-01", null);
			relation(store, me, y, "a-type", "2020-01-01", null);
			relation(store, x, me, "a-type", "2020-01-01", null);
			relation(store, me, x, "a-type", "2020-01-01", null);

			// 관계가 하나도 없는 개체.
			entity(store, "alone");
			// 변동 감지 화면의 시험이 판을 올릴 Pack: 시험용 Pack 의 사본이다. 판은 시험이 화면으로 올린다.
			PackCopies.load(dataSource, java.nio.file.Files.createTempDirectory("tabnit-e2e-pack"), CHANGES_PACK, text -> text);
		};
	}

	private static EntityRef entity(RelationStore store, String key) {
		EntityRef ref = new EntityRef(PACK, "T", key);
		store.saveEntity(ref, RECORDED_AT);
		return ref;
	}

	private static void relation(RelationStore store, EntityRef source, EntityRef target, String type, String from,
			String to) {
		ValidityPeriod period = new ValidityPeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to));
		store.saveRelation(new NewRelation(source, target, type, period, SRC_1), RECORDED_AT);
	}

}
