package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.EntityNotFoundException;
import com.altumlab.tabnit.link.Edition;
import com.altumlab.tabnit.link.EditionListEntry;
import com.altumlab.tabnit.link.EditionPage;
import com.altumlab.tabnit.link.EditionRecords;
import com.altumlab.tabnit.verify.PagedLists;
import com.altumlab.tabnit.verify.Product;
import com.altumlab.tabnit.verify.VerificationQuestions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * 적재된 Pack 의 검증 질문을 지금의 저장소에 묻는다 (카드 E-23, D7 10절 "검증 질문을 화면에서 돌린다", D2 AD-422).
 *
 * <p>읽기만 한다. 질문을 읽고 정답과 견주는 일은 verify-core 가 한다 (명령줄 실행기와 같은 코드다). 여기서는 두 가지를 한다:
 * 저장소의 판이 시나리오의 단계와 꼭 같은지 맞춰 보고, 같은 API 의 답을 서버 안에서 만들어 묻는 길로 내준다.
 */
class VerificationRuns {

	private static final Logger log = LoggerFactory.getLogger(VerificationRuns.class);

	/** 한 번에 받는 수. 목록의 API 가 받는 가장 큰 값이다 (D7 2절). */
	private static final int PAGE_SIZE = 200;

	/** 받는 사이에 목록이 달라지면 다시 받는 횟수. 명령줄 실행기와 같다. */
	private static final int MAX_ATTEMPTS = 3;

	private final PackLoading packs;

	private final EditionRecords editions;

	private final RelationsController relations;

	private final RelationMapController relationMaps;

	private final AttributeValuesController attributeValues;

	private final ActionsController actions;

	private final EditionsController editionResults;

	private final ObjectMapper json;

	VerificationRuns(PackLoading packs, EditionRecords editions, RelationsController relations,
			RelationMapController relationMaps, AttributeValuesController attributeValues, ActionsController actions,
			EditionsController editionResults, ObjectMapper json) {
		this.packs = packs;
		this.editions = editions;
		this.relations = relations;
		this.relationMaps = relationMaps;
		this.attributeValues = attributeValues;
		this.actions = actions;
		this.editionResults = editionResults;
		this.json = json;
	}

	/** 규격의 VerificationResults. */
	record Results(String packId, boolean available, String unavailableReason, List<ScenarioResult> scenarios) {
	}

	/** 규격의 VerificationScenario. */
	record ScenarioResult(String fileName, String scenarioId, String title, int stepCount, int questionCount, boolean matched,
			String mismatch, int passedCount, boolean repeatable, List<String> differingQuestionIds,
			List<VerificationQuestions.Result> results) {

		/** 묻지 않은 시나리오: 통과도 실패도 없다. */
		static ScenarioResult notAsked(VerificationQuestions.Scenario scenario, String mismatch) {
			return new ScenarioResult(scenario.fileName(), scenario.scenarioId(), scenario.title(), scenario.steps().size(),
					scenario.questionCount(), false, mismatch, 0, true, List.of(), List.of());
		}

		ScenarioResult withMismatch(String text) {
			return new ScenarioResult(this.fileName, this.scenarioId, this.title, this.stepCount, this.questionCount,
					this.matched, text, this.passedCount, this.repeatable, this.differingQuestionIds, this.results);
		}
	}

	/**
	 * @param packId 적재된 Pack 의 식별자
	 * @return 시나리오마다의 결과. 질문을 읽지 못하면 그 까닭
	 */
	Results run(String packId) {
		Optional<Path> folder = this.packs.folderIfPresent(packId);
		if (folder.isEmpty()) {
			return unavailable(packId, "서버의 Pack 폴더에 이 Pack 의 폴더가 없습니다 (또는 서버에 Pack 폴더가 정해져 있지 않습니다). "
					+ "검증 질문은 그 폴더의 questions 에서 읽습니다. 서버의 Pack 폴더에 이 Pack 을 둔 뒤 다시 시도하세요.");
		}
		List<VerificationQuestions.Loaded> files;
		try {
			files = VerificationQuestions.read(folder.get());
		}
		catch (IllegalArgumentException e) {
			// 어느 파일의 어느 항목인지는 verify-core 가 담는다. 파일을 열지 못한 때에도 서버의 경로는 담지 않는다:
			// 파일의 이름과 오류의 종류까지다 (시험이 본다).
			return unavailable(packId, "질문 파일을 읽지 못했습니다: " + e.getMessage());
		}
		if (files.isEmpty()) {
			return unavailable(packId, "이 Pack 의 폴더에 질문 파일이 없습니다 (questions 아래의 .yaml). 질문 파일을 둔 뒤 다시 시도하세요.");
		}
		List<Edition> stored = storedEditions(packId);
		List<ScenarioResult> scenarios = new ArrayList<>();
		for (VerificationQuestions.Loaded file : files) {
			ScenarioResult result = scenario(packId, folder.get(), file, stored);
			// 시나리오는 저마다 빈 저장소에서 시작하는 것이다 (D2 AD-110). 질문 파일이 여럿이면 한 저장소가 그 모두와
			// 맞을 수 없다: 맞지 않은 시나리오에 그 까닭을 덧붙인다 (D2 AD-423).
			if (!result.matched() && files.size() > 1) {
				result = result.withMismatch(result.mismatch() + " 이 Pack 에는 질문 파일이 " + files.size()
						+ "개 있습니다: 시나리오마다 빈 저장소에서 시작하는 것이라 화면에서는 한 번에 하나만 맞을 수 있습니다.");
			}
			scenarios.add(result);
		}
		return new Results(packId, true, null, scenarios);
	}

	private static Results unavailable(String packId, String reason) {
		return new Results(packId, false, reason, List.of());
	}

	private ScenarioResult scenario(String packId, Path folder, VerificationQuestions.Loaded file, List<Edition> stored) {
		VerificationQuestions.Scenario scenario = file.scenario();
		Map<Integer, Edition> matched = new LinkedHashMap<>();
		String mismatch = match(packId, folder, scenario, stored, matched);
		if (mismatch != null) {
			return ScenarioResult.notAsked(scenario, mismatch);
		}
		Product product = new InProcess();
		// 두 번 묻는다. 저장된 판의 답도 번마다 다시 읽는다: 한 번 읽은 것을 두 번 견주면 변동의 질문은 제 자신과
		// 견주는 것이 된다.
		List<VerificationQuestions.Result> first = file.evaluate(packId, product, storedReplies(matched));
		List<VerificationQuestions.Result> second = file.evaluate(packId, product, storedReplies(matched));
		// 묻는 사이에 판이 올라왔으면 위의 답은 시나리오와 같은 저장소의 것이 아니다. 통과도 실패도 내지 않는다
		// (D2 AD-423). 판은 지워지지 않으므로 (D5 5절) 처음 읽은 것과 식별자와 수가 같으면 달라진 것이 없다.
		if (!sameEditions(stored, storedEditions(packId))) {
			return ScenarioResult.notAsked(scenario, "묻는 사이에 저장소의 판이 달라졌습니다 (판을 올리는 일이 함께 돌았습니다). "
					+ "결과를 내지 않습니다. 판을 올리는 일이 끝난 뒤 다시 돌리세요.");
		}
		List<String> differing = new ArrayList<>();
		for (int i = 0; i < first.size(); i++) {
			if (i >= second.size() || !first.get(i).equals(second.get(i))) {
				differing.add(first.get(i).id());
			}
		}
		int passed = (int) first.stream().filter(VerificationQuestions.Result::passed).count();
		return new ScenarioResult(scenario.fileName(), scenario.scenarioId(), scenario.title(), scenario.steps().size(),
				scenario.questionCount(), true, null, passed, differing.isEmpty(), differing, first);
	}

	/** 판을 올렸을 때의 답 대신 저장된 그 판의 답을 준다: 같은 모양이다 (D2 AD-383). 부를 때마다 새로 읽는다. */
	private Map<Integer, Product.Reply> storedReplies(Map<Integer, Edition> matched) {
		Map<Integer, Product.Reply> steps = new LinkedHashMap<>();
		matched.forEach((step, edition) -> steps.put(step, call(() -> this.editionResults.stored(edition.id()))));
		return steps;
	}

	private static boolean sameEditions(List<Edition> before, List<Edition> after) {
		if (before.size() != after.size()) {
			return false;
		}
		java.util.TreeSet<String> ids = new java.util.TreeSet<>();
		before.forEach(edition -> ids.add(edition.id()));
		return after.stream().allMatch(edition -> ids.contains(edition.id()));
	}

	/**
	 * 저장소의 판이 시나리오의 단계와 꼭 같은지 본다: 단계마다 입력 자료와 판 번호, 적용일, 파일의 내용, 받은 차례.
	 *
	 * @param matched 같으면 단계의 번호마다 그 판을 담는다
	 * @return 다르면 어느 단계가 어떻게 다른지. 같으면 null
	 */
	private String match(String packId, Path folder, VerificationQuestions.Scenario scenario, List<Edition> stored,
			Map<Integer, Edition> matched) {
		Map<String, Edition> byKey = new TreeMap<>();
		stored.forEach(edition -> byKey.put(edition.inputSourceId() + "|" + edition.editionNo(), edition));
		Map<String, Integer> seen = new TreeMap<>();
		// 입력 자료마다, 바로 앞에 올린 파일의 내용.
		Map<String, byte[]> lastContent = new TreeMap<>();
		Edition previous = null;
		for (VerificationQuestions.Step step : scenario.steps()) {
			Path data = folder.resolve(step.file()).normalize();
			byte[] expected;
			try {
				// 바로가기(심볼릭 링크)는 따라가지 않는다: 이름은 폴더 안이어도 가리키는 곳이 Pack 폴더 밖일 수 있다.
				// 파일 자신이 바로가기가 아니어야 하고, 중간의 폴더가 바로가기여도 실제 자리가 Pack 의 폴더 안이어야 한다.
				if (!data.startsWith(folder) || !Files.isRegularFile(data, java.nio.file.LinkOption.NOFOLLOW_LINKS)
						|| !data.toRealPath().startsWith(folder.toRealPath())) {
					return "시나리오의 " + step.step() + "단계의 파일이 서버의 Pack 폴더에 없습니다: " + step.file();
				}
				expected = Files.readAllBytes(data);
			}
			catch (IOException e) {
				return "시나리오의 " + step.step() + "단계의 파일을 읽지 못했습니다: " + step.file();
			}
			// 앞 판과 같은 파일을 다시 올리는 단계는 판을 만들지 않는다 ("바뀐 것 없음", D2 AD-164). 명령줄 실행기는 그
			// 단계까지 올려서 묻지만, 여기서는 저장소의 판을 단계와 하나씩 맞출 수 없다. 틀린 까닭("판이 없다")으로
			// 답하지 않고 돌릴 수 없는 시나리오라고 알린다 (D2 AD-423).
			if (Arrays.equals(expected, lastContent.get(step.inputSource()))) {
				return "시나리오의 " + step.step() + "단계는 판을 만들지 않는 단계입니다 (입력 자료 " + step.inputSource()
						+ " 에 앞 판과 같은 파일을 다시 올립니다). 이런 시나리오는 화면에서 돌릴 수 없습니다. "
						+ "명령줄에서 돌리세요 (bash verify/run.sh).";
			}
			lastContent.put(step.inputSource(), expected);
			int editionNo = seen.merge(step.inputSource(), 1, Integer::sum);
			String where = "시나리오의 " + step.step() + "단계 (입력 자료 " + step.inputSource() + " 의 " + editionNo + "번째 판)";
			Edition edition = byKey.remove(step.inputSource() + "|" + editionNo);
			if (edition == null) {
				return where + "가 저장소에 없습니다. 시나리오의 판을 차례대로 모두 올린 저장소에서만 돌릴 수 있습니다.";
			}
			if (!edition.effectiveDate().equals(step.effectiveDate())) {
				return where + "의 적용일이 다릅니다. 시나리오는 " + step.effectiveDate() + ", 저장소는 " + edition.effectiveDate()
						+ " 입니다.";
			}
			byte[] original = this.editions.originalContent(edition.id()).orElse(null);
			if (original == null || !Arrays.equals(original, expected)) {
				return where + "의 내용이 다릅니다. 저장소에 올린 파일이 Pack 폴더의 " + step.file() + " 과(와) 같지 않습니다.";
			}
			if (previous != null && edition.receivedAt().isBefore(previous.receivedAt())) {
				return where + "를 앞 단계보다 먼저 올렸습니다. 시나리오의 차례대로 올린 저장소에서만 돌릴 수 있습니다.";
			}
			matched.put(step.step(), edition);
			previous = edition;
		}
		if (!byKey.isEmpty()) {
			Edition extra = byKey.values().iterator().next();
			return "시나리오에 없는 판이 저장소에 있습니다: 입력 자료 " + extra.inputSourceId() + " 의 " + extra.editionNo()
					+ "번째 판 (모두 " + byKey.size() + "개). 시나리오의 판만 올린 저장소에서만 돌릴 수 있습니다.";
		}
		return null;
	}

	/** 그 Pack 의 판 전부. 나눠 읽어 잇는다. */
	private List<Edition> storedEditions(String packId) {
		List<Edition> all = new ArrayList<>();
		for (int offset = 0;; offset += PAGE_SIZE) {
			EditionPage page = this.editions.packEditions(packId, offset, PAGE_SIZE);
			for (EditionListEntry entry : page.editions()) {
				all.add(entry.edition());
			}
			if (page.editions().size() < PAGE_SIZE) {
				return all;
			}
		}
	}

	/** API 의 답을 만들어, HTTP 로 받아 읽은 것과 같은 모양(객체는 Map, 목록은 List)으로 바꾼다. 오류는 그 상태로 답한다. */
	private Product.Reply call(Supplier<Object> handler) {
		try {
			Object answer = handler.get();
			return new Product.Reply(200, this.json.readValue(this.json.writeValueAsString(answer), Map.class));
		}
		catch (ApiProblemException e) {
			Map<String, Object> problem = new LinkedHashMap<>();
			problem.put("code", e.code());
			problem.put("detail", e.getMessage());
			return new Product.Reply(e.status().value(), problem);
		}
		catch (EntityNotFoundException e) {
			Map<String, Object> problem = new LinkedHashMap<>();
			problem.put("code", "ENTITY_NOT_FOUND");
			problem.put("detail", e.getMessage());
			return new Product.Reply(404, problem);
		}
		catch (RuntimeException e) {
			// 그 밖의 것은 서버 쪽의 문제다. HTTP 로 불렀을 때와 같이 원인은 기록에 남기고 답에는 담지 않는다:
			// 예외의 종류와 문구(SQL 따위)가 질문의 실패 사유로 나가지 않는다.
			log.error("검증 질문을 묻다가 서버 안에서 오류가 났습니다", e);
			Map<String, Object> problem = new LinkedHashMap<>();
			problem.put("code", "INTERNAL_ERROR");
			problem.put("detail", "서버 안에서 오류가 났습니다. 서버의 기록을 확인하세요.");
			return new Product.Reply(500, problem);
		}
	}

	/** 묻는 길: 화면과 명령줄 실행기가 부르는 그 API 의 답을 서버 안에서 만든다. 나눠 주는 목록은 끝까지 받아 잇는다. */
	private final class InProcess implements Product {

		@Override
		public Reply relations(String entityId, LocalDate asOf) {
			return PagedLists.whole(offset -> call(() -> VerificationRuns.this.relations.relationsAsOf(entityId,
					asOf.toString(), String.valueOf(offset), String.valueOf(PAGE_SIZE))), "relations", PAGE_SIZE, MAX_ATTEMPTS,
					"시점 조회 " + entityId);
		}

		@Override
		public Reply relationMap(String entityId, LocalDate asOf, int depth) {
			return call(() -> VerificationRuns.this.relationMaps.relationMap(entityId, asOf.toString(), String.valueOf(depth)));
		}

		@Override
		public Reply attributeValues(String entityId, LocalDate asOf) {
			return call(() -> VerificationRuns.this.attributeValues.valuesAsOf(entityId, asOf.toString()));
		}

		@Override
		public Reply impactScope(String changeEventId) {
			return PagedLists.whole(offset -> call(() -> VerificationRuns.this.actions.impactScope(changeEventId, null,
					String.valueOf(offset), String.valueOf(PAGE_SIZE))), "targets", PAGE_SIZE, MAX_ATTEMPTS,
					"영향 범위 " + changeEventId);
		}

		@Override
		public Reply actions(String packId, String changeEventId) {
			return PagedLists.whole(offset -> call(() -> VerificationRuns.this.actions.list(packId, null, changeEventId,
					String.valueOf(offset), String.valueOf(PAGE_SIZE))), "actions", PAGE_SIZE, MAX_ATTEMPTS,
					"조치 " + changeEventId);
		}
	}
}
