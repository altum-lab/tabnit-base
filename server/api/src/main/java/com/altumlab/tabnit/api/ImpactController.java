package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.EntityNotFoundException;
import com.altumlab.tabnit.core.EntityQuery;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.ImpactDirection;
import com.altumlab.tabnit.core.ImpactQuery;
import com.altumlab.tabnit.core.ImpactRequest;
import com.altumlab.tabnit.core.ImpactScope;
import com.altumlab.tabnit.core.ImpactTarget;
import com.altumlab.tabnit.core.Relation;
import com.altumlab.tabnit.core.UnknownImpactDirectionException;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.pack.RelationTypeDefinition;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개체 하나에서 출발해 영향 범위를 계산해 답하는 API (docs/api/openapi.yaml 의 getImpactFromEntity,
 * D7 6절 "출발 개체로 영향을 묻는다", D2 AD-429. 카드 A-21).
 *
 * <p>계산은 core 가 한다 ({@link ImpactQuery}). 여기서는 입력을 확인하고, 그 개체의 Pack 의 정의에서 관계 유형마다의
 * 영향 방향과 최대 깊이를 읽어 넘기고, 응답의 모양으로 옮긴다. 저장하지 않는다: 영향 범위, 조치, 변동 이벤트를 만들지 않는다.
 * 순서와 수는 core 가 준 그대로다. 다시 세거나 정렬하지 않는다.
 */
@RestController
class ImpactController {

	private final ImpactQuery impact;

	private final EntityQuery entities;

	private final PackDefinitions packs;

	private final EntityViews entityViews;

	private final Clock clock;

	private final ZoneId installationZone;

	ImpactController(ImpactQuery impact, EntityQuery entities, PackDefinitions packs, EntityViews entityViews, Clock clock,
			ZoneId installationZone) {
		this.impact = impact;
		this.entities = entities;
		this.packs = packs;
		this.entityViews = entityViews;
		this.clock = clock;
		this.installationZone = installationZone;
	}

	/**
	 * 규격의 EntityImpact. 영향 대상의 모양은 저장된 영향 범위의 답(ChangeImpactScope)과 같다.
	 *
	 * @param asOfPrevious 함께 따라간 전날. 따라가지 않았으면 null 로 적는다. 항목을 빼지 않는다
	 */
	record Response(String entityId, EntityViews.Item entity, String packId, String asOf, String asOfPrevious, int maxDepth,
			boolean truncated, int total, int offset, int limit, List<ChangeImpactScopeResponse.TypeCount> countsByType,
			List<ChangeImpactScopeResponse.DepthCount> countsByDepth, List<ChangeImpactScopeResponse.Target> targets) {
	}

	@GetMapping("/api/v1/impact")
	@Audited(action = "getImpactFromEntity", params = { "entityId", "includePreviousDay", "maxDepth" })
	Response impact(@RequestParam(required = false) String entityId, @RequestParam(required = false) String asOf,
			@RequestParam(required = false) String includePreviousDay, @RequestParam(required = false) String maxDepth,
			@RequestParam(required = false) String offset, @RequestParam(required = false) String limit) {
		if (entityId == null || entityId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_ENTITY_ID", "entityId",
					"출발 개체의 id 가 없습니다. entityId 에 개체의 id 를 넣어 다시 보내세요.");
		}
		if (entityId.indexOf('\u0000') >= 0) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_ENTITY_ID", "entityId",
					"개체의 id 에 쓸 수 없는 글자가 있습니다. 서버가 준 id 를 그대로 넣어 다시 보내세요.");
		}
		LocalDate date = resolveAsOf(asOf);
		AuditTrail.asOf(date);
		boolean withPrevious = flag(includePreviousDay);
		if (withPrevious && date.equals(LocalDate.of(1, 1, 1))) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_AS_OF", "asOf",
					"조회 시점 0001-01-01 의 전날은 받는 날짜의 범위 밖입니다. 더 늦은 날짜를 넣거나 includePreviousDay 를 빼고 다시 보내세요.");
		}
		PageRequest page = PageRequest.of(offset, limit);
		EntityRef start = this.entities.find(entityId).orElseThrow(() -> new EntityNotFoundException(entityId));
		// 영향 방향과 최대 깊이는 그 개체의 Pack 이 정한다. 개체가 있으면 그 Pack 의 정의도 있다 (없으면 저장소가 어긋난 것이다).
		PackDefinition pack = this.packs.find(start.packId()).orElseThrow(() -> new IllegalStateException(
				"개체 " + entityId + " 의 Pack " + start.packId() + " 의 정의가 없습니다. 저장소의 상태를 확인하세요."));
		int depth = depth(maxDepth, pack.defaultMaxDepth());
		Map<String, ImpactDirection> directions = new TreeMap<>();
		for (RelationTypeDefinition type : pack.relationTypes()) {
			directions.put(type.name(), ImpactDirection.valueOf(type.impactDirection().name()));
		}
		// 전날을 함께 보면 그 날짜가 적용일인 변동의 저장된 영향 범위와 같은 방식이다 (D2 AD-100, AD-115).
		List<LocalDate> dates = withPrevious ? List.of(date.minusDays(1), date) : List.of(date);
		ImpactScope scope;
		try {
			scope = this.impact.impactScope(new ImpactRequest(List.of(entityId), dates, directions, depth));
		}
		catch (UnknownImpactDirectionException e) {
			// 저장된 관계의 유형이 지금의 Pack 의 정의에 없다 (정의를 바꿔 다시 적재한 뒤의 옛 자료).
			throw new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_IMPACT_DIRECTION", "entityId",
					"따라가야 할 관계 가운데 지금의 Pack 의 정의에 없는 관계 유형이 있어 영향 범위를 계산하지 못했습니다."
							+ " Pack 의 정의에 그 관계 유형과 영향 방향이 있는지 확인하세요.");
		}
		// 나누는 것은 여기서 한다: 계산은 전체를 하고, 순서는 core 가 준 그대로다.
		List<ImpactTarget> all = scope.targets();
		int from = Math.min(page.offset(), all.size());
		List<ImpactTarget> shown = all.subList(from, Math.min(all.size(), from + page.limit()));
		TreeSet<String> ids = new TreeSet<>();
		ids.add(entityId);
		for (ImpactTarget target : shown) {
			ids.add(target.entityId());
			ids.add(target.path().startEntityId());
			for (Relation relation : target.path().relations()) {
				ids.add(relation.sourceEntityId());
				ids.add(relation.targetEntityId());
			}
		}
		// 표시 이름은 조회 시점의 값이다.
		Map<String, EntityViews.Item> views = this.entityViews.items(ids, date);
		List<ChangeImpactScopeResponse.Target> targets = new ArrayList<>();
		for (ImpactTarget target : shown) {
			List<ChangeImpactScopeResponse.Step> steps = new ArrayList<>();
			int stepNo = 0;
			for (Relation relation : target.path().relations()) {
				steps.add(new ChangeImpactScopeResponse.Step(++stepNo, relation.id(), relation.relationType(),
						relation.sourceEntityId(), relation.targetEntityId(),
						EntityViews.require(views, relation.sourceEntityId(), "근거 경로의 개체"),
						EntityViews.require(views, relation.targetEntityId(), "근거 경로의 개체")));
			}
			targets.add(new ChangeImpactScopeResponse.Target(target.entityId(), target.entityType(),
					EntityViews.require(views, target.entityId(), "영향 대상인 개체"), target.depth(),
					new ChangeImpactScopeResponse.Path(target.path().startEntityId(),
							EntityViews.require(views, target.path().startEntityId(), "경로가 시작한 출발 개체"),
							target.path().asOf(), steps)));
		}
		List<ChangeImpactScopeResponse.TypeCount> byType = new ArrayList<>();
		scope.countsByType().forEach((type, count) -> byType.add(new ChangeImpactScopeResponse.TypeCount(type, count)));
		List<ChangeImpactScopeResponse.DepthCount> byDepth = new ArrayList<>();
		scope.countsByDepth().forEach((level, count) -> byDepth.add(new ChangeImpactScopeResponse.DepthCount(level, count)));
		return new Response(entityId, EntityViews.require(views, entityId, "출발 개체"), start.packId(), date.toString(),
				withPrevious ? date.minusDays(1).toString() : null, depth, scope.truncated(), all.size(), page.offset(),
				page.limit(), byType, byDepth, targets);
	}

	/** 전날을 함께 볼지. 없으면 보지 않는다. true 와 false 만 받는다. */
	private static boolean flag(String value) {
		if (value == null || "false".equals(value)) {
			return false;
		}
		if ("true".equals(value)) {
			return true;
		}
		throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_INCLUDE_PREVIOUS_DAY", "includePreviousDay",
				"includePreviousDay 의 값이 틀렸습니다. true 나 false 를 넣거나 그 항목을 빼고 다시 보내세요.");
	}

	/** 최대 깊이. 없으면 Pack 의 기본값이고, 적었으면 1 부터 그 기본값까지다: Pack 이 정한 한도보다 깊게는 묻지 못한다. */
	private static int depth(String value, int packDefault) {
		if (value == null) {
			return packDefault;
		}
		try {
			int parsed = Integer.parseInt(value);
			if (parsed >= 1 && parsed <= packDefault) {
				return parsed;
			}
		}
		catch (NumberFormatException e) {
			// 아래에서 틀린 값으로 알린다.
		}
		throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_MAX_DEPTH", "maxDepth",
				"최대 깊이의 값이 틀렸습니다. 1 부터 이 Pack 의 최대 깊이의 기본값 " + packDefault + " 까지의 수를 넣거나 그 항목을 빼고 다시 보내세요.");
	}

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
		if (date.getYear() < 1 || date.getYear() > 9999) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_AS_OF", "asOf",
					"조회 시점 " + asOf + " 은(는) 쓸 수 없는 날짜입니다. 0001-01-01 부터 9999-12-31 까지의 날짜를 넣어 다시 보내세요.");
		}
		return date;
	}
}
