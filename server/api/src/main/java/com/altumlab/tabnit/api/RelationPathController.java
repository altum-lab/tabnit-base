package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.EntityQuery;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.Relation;
import com.altumlab.tabnit.core.RelationPath;
import com.altumlab.tabnit.core.RelationPathQuery;
import com.altumlab.tabnit.rules.ActionRecords;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 두 개체 사이의 가장 짧은 경로를 묻는 API (규격의 getRelationPath, D7 4절 "두 개체 사이의 경로", D2 AD-413).
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 어느 관계로 이어지는지와 여럿 가운데 어느 경로인지는 core 가 정한다.
 * 여기서는 걸음마다의 개체에 유형, 키, 조회 시점의 표시 이름을 붙인다. 걸음의 차례를 바꾸지 않는다.
 */
@RestController
class RelationPathController {

	/** 경로의 최대 길이의 기본값. 재어 보고 고친다 (D9 의 A-18). */
	static final int DEFAULT_MAX_LENGTH = 6;

	/** 훑는 개체의 한도의 기본값. */
	static final int DEFAULT_MAX_ENTITIES = 10_000;

	private final RelationPathQuery paths;

	private final EntityQuery entities;

	private final EntityViews entityViews;

	private final Clock clock;

	private final ZoneId installationZone;

	private final int maxLength;

	private final int maxEntities;

	RelationPathController(RelationPathQuery paths, EntityQuery entities, EntityViews entityViews, Clock clock,
			ZoneId installationZone,
			@Value("${tabnit.relation-path.max-length:" + DEFAULT_MAX_LENGTH + "}") int maxLength,
			@Value("${tabnit.relation-path.max-entities:" + DEFAULT_MAX_ENTITIES + "}") int maxEntities) {
		if (maxLength < 1 || maxEntities < 1) {
			throw new IllegalStateException("tabnit.relation-path.max-length 와 tabnit.relation-path.max-entities 는 1 이상이어야"
					+ " 합니다: " + maxLength + ", " + maxEntities + ". 값을 고쳐 다시 실행하세요.");
		}
		this.paths = paths;
		this.entities = entities;
		this.entityViews = entityViews;
		this.clock = clock;
		this.installationZone = installationZone;
		this.maxLength = maxLength;
		this.maxEntities = maxEntities;
	}

	/**
	 * 규격의 RelationPath.
	 *
	 * @param length 경로의 길이(관계의 수). 찾지 못했으면 null 이다
	 * @param entities 경로가 지나는 개체. 출발 개체에서 도착 개체까지 차례대로다. 찾지 못했으면 비어 있다
	 * @param steps 걸음. 출발 개체 쪽부터 차례대로다
	 */
	record Response(String asOf, String fromEntityId, String toEntityId, boolean found, boolean limitReached,
			int maxLength, Integer length, List<EntityViews.Item> entities, List<Step> steps) {
	}

	/**
	 * 규격의 RelationPathStep.
	 *
	 * @param from 이 걸음을 떼는 개체
	 * @param to 이 걸음이 닿는 개체
	 * @param relation 건넌 관계. 적힌 방향(출발, 도착)은 저장된 그대로다: 걸어간 방향과 다를 수 있다
	 */
	record Step(int stepNo, EntityViews.Item from, EntityViews.Item to, RelationsAsOfResponse.RelationItem relation) {
	}

	@GetMapping("/api/v1/relation-path")
	@Audited(action = "getRelationPath", params = { "fromEntityId", "toEntityId" })
	Response path(@RequestParam(required = false) String fromEntityId, @RequestParam(required = false) String toEntityId,
			@RequestParam(required = false) String asOf) {
		requireEntityId(fromEntityId, "MISSING_FROM_ENTITY_ID", "fromEntityId", "출발 개체");
		requireEntityId(toEntityId, "MISSING_TO_ENTITY_ID", "toEntityId", "도착 개체");
		LocalDate date = resolveAsOf(asOf);
		AuditTrail.asOf(date);
		// 다른 Pack 의 두 개체는 이어질 수 없다 (관계는 한 Pack 안에만 있다). 없는 경로가 아니라 틀린 입력으로 알린다.
		// 없는 개체는 core 가 어느 쪽인지 알린다.
		Optional<EntityRef> from = this.entities.find(fromEntityId);
		Optional<EntityRef> to = this.entities.find(toEntityId);
		if (from.isPresent() && to.isPresent() && !from.get().packId().equals(to.get().packId())) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "DIFFERENT_PACKS", "toEntityId",
					"두 개체가 서로 다른 Pack 의 것입니다. 같은 Pack 의 두 개체를 넣어 다시 보내세요.");
		}
		RelationPath found = this.paths.shortestPath(fromEntityId, toEntityId, date, this.maxLength, this.maxEntities);
		Map<String, EntityViews.Item> views = this.entityViews.items(found.entityIds(), date);
		InputSourceLabels labels = this.entityViews.inputSourceLabels();
		List<EntityViews.Item> walked = new ArrayList<>();
		for (String id : found.entityIds()) {
			walked.add(EntityViews.require(views, id, "경로의 개체"));
		}
		List<Step> steps = new ArrayList<>();
		for (int i = 0; i < found.relations().size(); i++) {
			Relation relation = found.relations().get(i);
			steps.add(new Step(i + 1, walked.get(i), walked.get(i + 1),
					RelationsAsOfResponse.RelationItem.of(relation, views, labels)));
		}
		return new Response(date.toString(), fromEntityId, toEntityId, found.found(), found.limitReached(), this.maxLength,
				found.found() ? found.relations().size() : null, walked, steps);
	}

	private static void requireEntityId(String entityId, String missingCode, String field, String what) {
		if (entityId == null || entityId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, missingCode, field,
					what + "의 id 가 없습니다. " + field + " 에 개체의 id 를 넣어 다시 보내세요.");
		}
		// 쓸 수 없는 글자(NUL, 깨진 글자)가 든 id 의 개체는 있을 수 없다: 없는 개체가 아니라 틀린 입력이다 (D2 AD-370).
		if (!ActionRecords.isReadableEntityId(entityId)) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_ENTITY_ID", field,
					what + "의 id 에 쓸 수 없는 글자가 있습니다. 서버가 준 id 를 그대로 넣어 다시 보내세요.");
		}
	}

	/**
	 * 조회 시점을 정한다. 요청에 없으면 여기서 한 번만 오늘로 정하고, 응답에 그 날짜를 담는다 (D2 AD-23).
	 * 오늘은 설치 설정의 시간대에서의 날짜다 (D2 AD-40).
	 */
	private LocalDate resolveAsOf(String asOf) {
		if (asOf == null) {
			return LocalDate.now(this.clock.withZone(this.installationZone));
		}
		LocalDate date;
		try {
			date = LocalDate.parse(asOf);
		}
		catch (DateTimeParseException e) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_AS_OF", "asOf",
					"조회 시점의 형식이 틀렸습니다. 2026-03-01 과 같이 적어 다시 보내세요.");
		}
		// 받는 날짜는 0001-01-01 부터 9999-12-31 까지다 (D7 2절, D2 AD-370). 그 밖의 날짜는 엔진이 받지 않는다.
		if (date.getYear() < 1 || date.getYear() > 9999) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_AS_OF", "asOf",
					"조회 시점 " + asOf + " 은(는) 쓸 수 없는 날짜입니다. 0001-01-01 부터 9999-12-31 까지의 날짜를 넣어 다시 보내세요.");
		}
		return date;
	}
}
