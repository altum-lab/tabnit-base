package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.AttributeValue;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.PackData;
import com.altumlab.tabnit.core.PackDataQuery;
import com.altumlab.tabnit.core.Relation;
import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.ConstraintChecker;
import com.altumlab.tabnit.pack.ConstraintData;
import com.altumlab.tabnit.pack.ConstraintViolation;
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
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제약의 위반을 보는 API (규격의 listConstraintViolations, D7 "제약의 위반", D2 AD-390, AD-391, AD-395).
 *
 * <p>받고, 넘기고, 돌려준다. Pack 의 자료를 조회 시점으로 읽는 일은 core 가, 그 자료에 제약을 거는 일은 pack 이 한다.
 * 여기서는 core 가 읽은 것을 pack 의 검사기에 넘기고, 위반의 개체에 유형, 키, 표시 이름을 붙여 나눠 돌려준다.
 * 위반을 저장하지 않는다: 부를 때마다 검사한다.
 */
@RestController
class ConstraintViolationsController {

	private final PackDataQuery packData;

	private final PackDefinitions packs;

	private final EntityViews entityViews;

	private final Clock clock;

	private final ZoneId installationZone;

	/** 한 번에 검사하는 자료의 한도: 개체, 관계, 속성 값의 수를 더한 것. */
	private final int maxRows;

	ConstraintViolationsController(PackDataQuery packData, PackDefinitions packs, EntityViews entityViews, Clock clock,
			ZoneId installationZone, @Value("${tabnit.constraints.max-rows:" + DEFAULT_MAX_ROWS + "}") int maxRows) {
		if (maxRows < 1) {
			throw new IllegalStateException("tabnit.constraints.max-rows 는 1 이상이어야 합니다: " + maxRows
					+ ". 한 번에 검사할 자료의 한도를 정해 다시 실행하세요.");
		}
		this.packData = packData;
		this.packs = packs;
		this.entityViews = entityViews;
		this.clock = clock;
		this.installationZone = installationZone;
		this.maxRows = maxRows;
	}

	/** 한도의 기본값. 검사기만 재어 보고 잡은 값이다: 저장소에서 읽는 것과 함께 묻는 경우는 재지 않았다 (D9 의 B-17). */
	static final int DEFAULT_MAX_ROWS = 200_000;

	/** 규격의 ConstraintViolationList. */
	record ListResponse(String packId, String asOf, boolean checked, int maxRows, int entityCount, int relationCount,
			int valueCount, int total, int offset, int limit, List<Item> violations) {
	}

	/** 규격의 ConstraintViolation. */
	record Item(String severity, EntityViews.Item entity, String pathKind, String pathName, String pathLabel, String value,
			EntityViews.Item valueEntity, String constraintKind, String constraintName, String message) {
	}

	@GetMapping("/api/v1/constraint-violations")
	@Audited(action = "listConstraintViolations", params = "packId")
	ListResponse list(@RequestParam(required = false) String packId, @RequestParam(required = false) String asOf,
			@RequestParam(required = false) String offset, @RequestParam(required = false) String limit) {
		if (packId == null || packId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		}
		PageRequest page = PageRequest.of(offset, limit);
		LocalDate date = resolveAsOf(asOf);
		// NUL 이 든 식별자의 Pack 은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		Optional<PackDefinition> found = packId.indexOf('\u0000') >= 0 ? Optional.empty() : this.packs.find(packId);
		PackDefinition pack = found.orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "packId",
				"Pack " + packId + " 이 적재되어 있지 않습니다. Pack 의 식별자를 확인하세요."));
		AuditTrail.asOf(date);

		PackData data = this.packData.packDataAsOf(packId, date, this.maxRows);
		if (!data.complete()) {
			// 일부만 검사하면 없는 위반을 없다고 잘못 알리게 된다. 검사하지 않았다고 답한다.
			return new ListResponse(packId, date.toString(), false, this.maxRows, data.entityCount(), data.relationCount(),
					data.valueCount(), 0, page.offset(), page.limit(), List.of());
		}
		List<ConstraintViolation> violations = ConstraintChecker.check(pack, constraintData(data));

		// 그 쪽의 위반만 옮긴다. 순서와 전체 수는 pack 이 정한 것이다.
		int from = Math.min(page.offset(), violations.size());
		int to = (int) Math.min((long) from + page.limit(), violations.size());
		List<ConstraintViolation> shown = violations.subList(from, to);
		TreeSet<String> ids = new TreeSet<>();
		for (ConstraintViolation violation : shown) {
			if (violation.entityId() != null) {
				ids.add(violation.entityId());
			}
			if (violation.valueEntityId() != null) {
				ids.add(violation.valueEntityId());
			}
		}
		Map<String, EntityViews.Item> entities = this.entityViews.items(ids, date);
		Map<String, String> labels = new TreeMap<>();
		for (AttributeDefinition attribute : pack.attributes()) {
			labels.put(attribute.name(), attribute.label());
		}
		for (RelationTypeDefinition relationType : pack.relationTypes()) {
			labels.put(relationType.name(), relationType.label());
		}
		List<Item> items = new ArrayList<>();
		for (ConstraintViolation violation : shown) {
			boolean named = violation.pathKind() == ConstraintViolation.PathKind.ATTRIBUTE
					|| violation.pathKind() == ConstraintViolation.PathKind.RELATION;
			items.add(new Item(violation.severity().name(),
					violation.entityId() == null ? null : EntityViews.require(entities, violation.entityId(), "제약을 어긴 개체"),
					violation.pathKind().name(), violation.pathName(), named ? labels.get(violation.pathName()) : null,
					violation.value(),
					violation.valueEntityId() == null ? null
							: EntityViews.require(entities, violation.valueEntityId(), "제약을 어긴 값의 개체"),
					violation.constraintKind(), violation.constraintName(), violation.message()));
		}
		return new ListResponse(packId, date.toString(), true, this.maxRows, data.entityCount(), data.relationCount(),
				data.valueCount(), violations.size(), page.offset(), page.limit(), items);
	}

	/** core 가 읽은 자료를 pack 의 검사기가 받는 꼴로 옮긴다. 값을 바꾸지 않는다. */
	private static ConstraintData constraintData(PackData data) {
		List<ConstraintData.Entity> entities = new ArrayList<>();
		for (EntityRef entity : data.entities()) {
			entities.add(new ConstraintData.Entity(entity.id(), entity.entityType()));
		}
		List<ConstraintData.Link> relations = new ArrayList<>();
		for (Relation relation : data.relations()) {
			relations.add(new ConstraintData.Link(relation.sourceEntityId(), relation.targetEntityId(), relation.relationType()));
		}
		List<ConstraintData.Value> values = new ArrayList<>();
		for (AttributeValue value : data.values()) {
			values.add(new ConstraintData.Value(value.entityId(), value.attributeName(), value.value()));
		}
		return new ConstraintData(entities, relations, values);
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
