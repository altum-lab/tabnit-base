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

	/** 화면 시험(web/tests/actions.spec.ts)이 조치를 읽는 Pack 의 식별자. */
	private static final String ACTIONS_PACK = "e2e-actions";

	/** 화면 시험(web/tests/unprocessed.spec.ts)이 처리에 실패한 변동을 읽는 Pack 의 식별자. */
	private static final String FAILED_PACK = "e2e-failed";

	/** 그 Pack 에 올리는 판. 입력 자료, 파일, 적용일. 시험용 Pack 의 시나리오의 차례다. */
	private static final String[][] ACTIONS_EDITIONS = { { "program", "program-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v1.csv", "2026-01-01" },
			{ "guideline-program", "guideline-program-v1.csv", "2026-01-01" },
			{ "partner-health", "partner-health-v1.csv", "2026-01-01" },
			{ "partner-private", "partner-private-v1.csv", "2026-01-01" }, { "team", "team-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v2.csv", "2026-03-01" },
			{ "guideline-program", "guideline-program-v2.csv", "2026-03-01" } };

	private static final String COMPARE_PACK = "e2e-compare";

	private static final String[][] COMPARE_EDITIONS = { { "program", "program-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v1.csv", "2026-01-01" }, { "guideline", "guideline-v2.csv", "2026-03-01" },
			{ "guideline", "guideline-v3.csv", "2026-05-01" } };

	private static final Provenance SRC_1 = new Provenance("src", 1);

	@Bean
	ApplicationRunner e2eSeed(DataSource dataSource, EditionProcessing processing,
			com.altumlab.tabnit.common.UserAccounts accounts) {
		return args -> {
			// 화면 시험이 로그인하는 사용자 (web/tests/fixtures.ts). 아이디와 비밀번호는 지어낸 시험용 값이다.
			accounts.create("e2e-officer", "지어낸-화면-시험-비밀번호-1", com.altumlab.tabnit.common.Role.OFFICER, RECORDED_AT);
			accounts.create("e2e-admin", "지어낸-화면-시험-비밀번호-2", com.altumlab.tabnit.common.Role.ADMIN, RECORDED_AT);

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
			// 나눠 주기를 보는 자료: 관계가 한 쪽(50개)을 넘는 개체. 넣는 순서는 일부러 섞는다 (7 은 60 과 서로소다).
			EntityRef many = entity(store, "many");
			for (int i = 0; i < 60; i++) {
				relation(store, many, entity(store, String.format("m%02d", (i * 7) % 60)), "R", "2020-01-01", null);
			}
			// 관계 지도가 잘리는 것을 보는 자료 (D-08): 이웃이 지도의 한도(200개)를 넘는 개체.
			EntityRef crowd = entity(store, "crowd");
			for (int i = 0; i < 250; i++) {
				relation(store, crowd, entity(store, String.format("c%03d", i)), "R", "2020-01-01", null);
			}
			// 변동 감지 화면의 시험이 판을 올릴 Pack: 시험용 Pack 의 사본이다. 판은 시험이 화면으로 올린다.
			PackCopies.load(dataSource, java.nio.file.Files.createTempDirectory("tabnit-e2e-pack"), CHANGES_PACK, text -> text);
			// 조치 화면의 시험이 읽을 Pack: 시험용 Pack 의 사본에 그 시나리오의 판을 차례로 올려 조치까지 만들어 둔다.
			// 화면 시험은 이 Pack 의 조치를 읽기만 한다 (확정과 보류의 시험은 위의 Pack 에 새 판을 올려 만든 조치로 한다).
			java.nio.file.Path actionsPack = PackCopies.load(dataSource,
					java.nio.file.Files.createTempDirectory("tabnit-e2e-pack"), ACTIONS_PACK, text -> text);
			for (String[] edition : ACTIONS_EDITIONS) {
				processing.submit(ACTIONS_PACK, edition[0], edition[1],
						java.nio.file.Files.readAllBytes(actionsPack.resolve("data/" + edition[1])),
						java.time.LocalDate.parse(edition[2]));
			}
			// 두 판의 비교의 시험이 읽을 Pack (C-16, web/tests/edition-comparison.spec.ts): 같은 입력 자료의 판이 셋이다.
			// 이웃하지 않은 두 판(첫째와 셋째)을 견줄 수 있다. 화면 시험은 읽기만 한다.
			java.nio.file.Path comparePack = PackCopies.load(dataSource,
					java.nio.file.Files.createTempDirectory("tabnit-e2e-pack"), COMPARE_PACK, text -> text);
			for (String[] edition : COMPARE_EDITIONS) {
				processing.submit(COMPARE_PACK, edition[0], edition[1],
						java.nio.file.Files.readAllBytes(comparePack.resolve("data/" + edition[1])),
						java.time.LocalDate.parse(edition[2]));
			}
			// 시연 리허설(D-10, web/tests/rehearsal.spec.ts)이 화면으로 판을 올릴 두 예시 Pack. 정의만 적재한다:
			// 판은 그 시험이 시나리오의 차례대로 화면에서 올린다.
			for (String example : java.util.List.of("manufacturing-safety", "retirement-pension")) {
				new com.altumlab.tabnit.pack.PackLoader(dataSource).load(java.nio.file.Path.of("../../packs", example),
						RECORDED_AT);
			}
			// 처리되지 않은 변동의 시험이 읽을 Pack: 규칙 하나의 기한을 날짜로 담을 수 없게 고친 사본이다.
			// 그 규칙에 걸리는 변동이 실패로 남는다. 고치지 않으므로 다시 처리해도 다시 실패한다: 시험을 되풀이할 수 있다.
			PackCopies.requireUnstorableDueDate();
			java.nio.file.Path failedPack = PackCopies.load(dataSource,
					java.nio.file.Files.createTempDirectory("tabnit-e2e-pack"), FAILED_PACK,
					PackCopies::unstorableDueDate);
			for (String[] edition : ACTIONS_EDITIONS) {
				processing.submit(FAILED_PACK, edition[0], edition[1],
						java.nio.file.Files.readAllBytes(failedPack.resolve("data/" + edition[1])),
						java.time.LocalDate.parse(edition[2]));
			}
		};
	}

	/** 화면 시험이 적재할 Pack 의 폴더: 문제없는 것, 경고가 나오는 것, 틀린 것. 모두 시험용 Pack 의 사본이다. */
	static final String LOADABLE_PACK = "e2e-loadable";

	static final String WARNING_PACK = "e2e-warning";

	static final String BROKEN_PACK = "e2e-broken";

	/**
	 * 시험용 서버의 Pack 폴더를 임시 폴더에 만든다 (설치 설정 tabnit.packs-dir 로 쓴다). 적재하지는 않는다:
	 * 화면 시험이 관리자로 골라 적재한다 (web/tests/pack-loading.spec.ts).
	 */
	static java.nio.file.Path preparePacksDirectory() {
		try {
			java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("tabnit-e2e-packs-dir").toRealPath();
			PackCopies.copyInto(directory, LOADABLE_PACK, text -> text);
			// 적재는 되지만 뜻대로 걸리지 않을 수 있는 규칙 하나를 더한다: 경고가 하나 나온다 (D2 AD-323).
			String more = """

					ex:규칙-0009 a tabnit:Rule ; tabnit:ruleNo "0009" ; rdfs:label "경고가 나오는 규칙" ; tabnit:priority 9 ;
					    tabnit:appliesTo tabnit:ImpactTarget ; tabnit:targetType ex:의료사업 ;
					    tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:property ex:점검주기일 ;
					                       tabnit:operator tabnit:LessThanOrEqual ; tabnit:value 30 ] ;
					    tabnit:actionText "할 일" ; tabnit:dueInDays 1 .
					""";
			PackCopies.copyInto(directory, WARNING_PACK, text -> text.contains("a tabnit:Rule") ? text + more : text);
			// Pack 의 머리에 모르는 항목을 적는다: 검증에서 거부된다.
			PackCopies.copyInto(directory, BROKEN_PACK,
					text -> text.replace("tabnit:defaultMaxDepth 3", "tabnit:noSuchThing 3"));
			return directory;
		}
		catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException("시험용 서버의 Pack 폴더를 만들지 못했습니다", e);
		}
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
