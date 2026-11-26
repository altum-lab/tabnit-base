package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.Relation;
import com.altumlab.tabnit.core.RelationPage;
import com.altumlab.tabnit.core.RelationQuery;
import com.altumlab.tabnit.rules.ActionRecords;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시점 조회 API. 규격은 docs/api/openapi.yaml 의 getRelationsAsOf, 규칙은 D7 4절이다.
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 어느 관계가 유효한지와 순서는 core 가 정한다.
 * 이 클래스는 목록의 순서를 바꾸지 않는다.
 */
@RestController
class RelationsController {

	private final RelationQuery relationQuery;

	private final EntityViews entityViews;

	private final Clock clock;

	private final ZoneId installationZone;

	private final ListExports exports;

	RelationsController(RelationQuery relationQuery, EntityViews entityViews, Clock clock, ZoneId installationZone,
			ListExports exports) {
		this.exports = exports;
		this.relationQuery = relationQuery;
		this.entityViews = entityViews;
		this.clock = clock;
		this.installationZone = installationZone;
	}

	@GetMapping("/api/v1/relations")
	@Audited(action = "getRelationsAsOf", params = "entityId")
	RelationsAsOfResponse relationsAsOf(@RequestParam(required = false) String entityId,
			@RequestParam(required = false) String asOf, @RequestParam(required = false) String offset,
			@RequestParam(required = false) String limit) {
		if (entityId == null || entityId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_ENTITY_ID", "entityId",
					"개체의 id 가 없습니다. entityId 에 개체의 id 를 넣어 다시 보내세요.");
		}
		// 쓸 수 없는 글자(NUL, 깨진 글자)가 든 id 의 개체는 있을 수 없다: 없는 개체가 아니라 틀린 입력이다 (D2 AD-370).
		// 조치의 API 와 같은 기준이다.
		if (!ActionRecords.isReadableEntityId(entityId)) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_ENTITY_ID", "entityId",
					"개체의 id 에 쓸 수 없는 글자가 있습니다. 서버가 준 id 를 그대로 넣어 다시 보내세요.");
		}
		LocalDate date = resolveAsOf(asOf);
		PageRequest page = PageRequest.of(offset, limit);
		AuditTrail.asOf(date);
		return read(entityId, date, page, page);
	}

	/**
	 * 관계의 목록을 파일로 내보낸다 (규격의 exportRelations, D7 "목록 내보내기", D2 AD-412). 인자와 순서는 위의 목록과
	 * 같고 나누지 않는다: 그 조건의 전체를 담는다.
	 */
	@GetMapping("/api/v1/relation-exports")
	@Audited(action = "exportRelations", params = { "entityId", "format" })
	ResponseEntity<Object> export(@RequestParam(required = false) String entityId,
			@RequestParam(required = false) String asOf, @RequestParam(required = false) String format) {
		if (entityId == null || entityId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_ENTITY_ID", "entityId",
					"개체의 id 가 없습니다. entityId 에 개체의 id 를 넣어 다시 보내세요.");
		}
		if (!ActionRecords.isReadableEntityId(entityId)) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_ENTITY_ID", "entityId",
					"개체의 id 에 쓸 수 없는 글자가 있습니다. 서버가 준 id 를 그대로 넣어 다시 보내세요.");
		}
		ListExports.Format wanted = ListExports.format(format);
		LocalDate date = resolveAsOf(asOf);
		AuditTrail.asOf(date);
		// 한도보다 하나 더 읽어 넘었는지를 본다. 전체 수와 행은 core 가 한 시점의 것으로 읽는다.
		RelationsAsOfResponse all = read(entityId, date, this.exports.whole(), this.exports.answered());
		this.exports.requireWithin(all.total());
		String name = "relations-" + all.asOf();
		if (wanted == ListExports.Format.JSON) {
			return ListExports.json(name, all);
		}
		List<List<String>> rows = new ArrayList<>();
		for (RelationsAsOfResponse.RelationItem relation : all.relations()) {
			rows.add(Arrays.asList(all.asOf(), relation.id(), relation.relationType(), relation.source().entityId(),
					relation.source().entityTypeLabel(), relation.source().entityKey(), relation.source().displayName(),
					relation.target().entityId(), relation.target().entityTypeLabel(), relation.target().entityKey(),
					relation.target().displayName(), relation.validFrom(), relation.validTo(),
					relation.provenance().inputSourceLabel(), String.valueOf(relation.provenance().editionNo()),
					relation.recordedAt()));
		}
		return ListExports.csv(name, RELATION_COLUMNS, rows);
	}

	/** 관계의 CSV 의 머리 행. 화면의 관계 목록의 열에 id 와 조회 시점을 더한 것이다. */
	private static final List<String> RELATION_COLUMNS = List.of("조회 시점", "관계 id", "관계 유형", "출발 개체 id", "출발 유형",
			"출발 키", "출발 이름", "도착 개체 id", "도착 유형", "도착 키", "도착 이름", "시작일", "종료일", "입력 자료", "판", "기록한 때");

	/**
	 * 읽어 응답의 모양으로 옮긴다. 목록과 내보내기가 함께 쓴다: 무엇이 어떤 순서로 나오는지가 같다.
	 *
	 * @param page core 에 넘기는 건너뛸 수와 한 번에 받을 수
	 * @param answered 답에 적는 건너뛴 수와 한 번에 주는 수
	 */
	private RelationsAsOfResponse read(String entityId, LocalDate date, PageRequest page, PageRequest answered) {
		// 나누는 일은 core 가 한다 (D2 AD-347). 여기서는 값을 확인해 넘긴다.
		RelationPage found = relationQuery.relationsAsOf(entityId, date, page.offset(), page.limit());
		// 이 쪽의 관계의 개체를 한 번에 읽어 유형, 키, 조회 시점의 표시 이름을 붙인다 (D2 AD-349).
		TreeSet<String> ids = new TreeSet<>();
		for (Relation relation : found.relations()) {
			ids.add(relation.sourceEntityId());
			ids.add(relation.targetEntityId());
		}
		return RelationsAsOfResponse.of(date, entityId, found.total(), answered, found.relations(),
				this.entityViews.items(ids, date), this.entityViews.inputSourceLabels());
	}

	/**
	 * 조회 시점을 정한다. 요청에 없으면 여기서 한 번만 오늘로 정하고, 응답에 그 날짜를 담는다 (D2 AD-23).
	 * 오늘은 설치 설정의 시간대에서의 날짜다 (D2 AD-40).
	 */
	private LocalDate resolveAsOf(String asOf) {
		if (asOf == null) {
			return LocalDate.now(clock.withZone(installationZone));
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
