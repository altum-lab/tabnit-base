package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.EntityPage;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.EntitySearchQuery;
import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개체를 글자로 찾는 API (규격의 searchEntities, D7 4절 "개체 찾기", D2 AD-411).
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 무엇이 맞는지, 순서, 나누는 일은 core 가 정한다. 여기서는 걸러 낼 유형을
 * Pack 의 정의로 풀고 (상위 유형이면 그 하위 유형까지), 찾은 개체에 유형의 이름표와 조회 시점의 표시 이름을 붙인다.
 */
@RestController
class EntitiesController {

	/** 검색어의 가장 긴 길이. UTF-16 단위로 센다: 기본 평면 밖의 글자(이모지 따위)는 둘로 센다. */
	static final int MAX_QUERY_LENGTH = 200;

	private final EntitySearchQuery search;

	private final PackDefinitions packs;

	private final EntityViews entityViews;

	private final Clock clock;

	private final ZoneId installationZone;

	EntitiesController(EntitySearchQuery search, PackDefinitions packs, EntityViews entityViews, Clock clock,
			ZoneId installationZone) {
		this.search = search;
		this.packs = packs;
		this.entityViews = entityViews;
		this.clock = clock;
		this.installationZone = installationZone;
	}

	/** 규격의 EntityList. */
	record ListResponse(String packId, String asOf, String q, int total, int offset, int limit,
			List<EntityViews.Item> entities) {
	}

	@GetMapping("/api/v1/entities")
	@Audited(action = "searchEntities", params = { "packId", "q" })
	ListResponse search(@RequestParam(required = false) String packId, @RequestParam(required = false) String q,
			@RequestParam(required = false) String asOf, @RequestParam(required = false) String offset,
			@RequestParam(required = false) String limit, HttpServletRequest request) {
		if (packId == null || packId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PACK_ID", "packId",
					"Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		}
		String text = q == null ? "" : q;
		if (text.length() > MAX_QUERY_LENGTH) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "q",
					"검색어가 너무 깁니다. " + MAX_QUERY_LENGTH + "자까지 적어 다시 보내세요.");
		}
		if (!readable(text)) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "q",
					"검색어에 쓸 수 없는 글자가 있습니다. 그 글자를 빼고 다시 보내세요.");
		}
		PageRequest page = PageRequest.of(offset, limit);
		LocalDate date = resolveAsOf(asOf);
		// 날짜를 읽자마자 알린다: 그 뒤에 거부되는 요청(없는 Pack, 없는 유형)의 감사 기록에도 조회 시점이 남는다.
		AuditTrail.asOf(date);
		// 쓸 수 없는 글자가 든 식별자의 Pack 은 있을 수 없다. 저장소에 묻지 않고 없는 것으로 답한다.
		Optional<PackDefinition> found = readable(packId) ? this.packs.find(packId) : Optional.empty();
		PackDefinition pack = found.orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "PACK_NOT_FOUND", "packId",
				"Pack " + packId + " 이 적재되어 있지 않습니다. Pack 의 식별자를 확인하세요."));
		// 유형은 요청의 글자 그대로 읽는다: 빈 값(entityType=)을 없는 항목으로 바꿔 읽지 않는다.
		String[] entityType = request.getParameterValues("entityType");
		TreeSet<String> types = expand(pack, entityType == null ? List.of() : Arrays.asList(entityType));

		// 찾고 나누는 일은 core 가 한다. 여기서는 값을 확인해 넘긴다.
		EntityPage found1 = this.search.search(packId, text, types, date, page.offset(), page.limit());
		List<String> ids = found1.entities().stream().map(EntityRef::id).toList();
		Map<String, EntityViews.Item> views = this.entityViews.items(ids, date);
		List<EntityViews.Item> items = new ArrayList<>();
		for (String id : ids) {
			items.add(EntityViews.require(views, id, "찾은 개체"));
		}
		return new ListResponse(packId, date.toString(), text, found1.total(), page.offset(), page.limit(), items);
	}

	/**
	 * 요청의 유형을 그 하위 유형까지 푼다 (여러 단계 아래도). 요청에 유형이 없으면 비어 있다: 모든 유형이다.
	 *
	 * @throws ApiProblemException 유형의 이름이 비었거나 그 Pack 의 온톨로지에 없을 때 (400)
	 */
	static TreeSet<String> expand(PackDefinition pack, List<String> requested) {
		TreeSet<String> known = new TreeSet<>();
		pack.entityTypes().forEach(type -> known.add(type.name()));
		TreeSet<String> wanted = new TreeSet<>();
		for (String name : requested) {
			if (name == null || name.isEmpty()) {
				throw new ApiProblemException(HttpStatus.BAD_REQUEST, "UNKNOWN_ENTITY_TYPE", "entityType",
						"개체 유형의 이름이 비어 있습니다. entityType 에 유형의 이름을 넣거나 그 항목을 빼고 다시 보내세요.");
			}
			if (!known.contains(name)) {
				throw new ApiProblemException(HttpStatus.BAD_REQUEST, "UNKNOWN_ENTITY_TYPE", "entityType",
						"Pack " + pack.packId() + " 에 그런 개체 유형이 없습니다. 유형의 이름을 확인해 다시 보내세요.");
			}
			wanted.add(name);
		}
		// 상위 유형이 이미 든 유형을 더해 간다. 더할 것이 없을 때까지 돈다: 유형의 수만큼 돌면 끝난다.
		boolean added = !wanted.isEmpty();
		while (added) {
			added = false;
			for (EntityTypeDefinition type : pack.entityTypes()) {
				if (!wanted.contains(type.name()) && type.parents().stream().anyMatch(wanted::contains)) {
					wanted.add(type.name());
					added = true;
				}
			}
		}
		return wanted;
	}

	/** NUL 과 깨진 글자(짝 없는 대리 문자)가 없는가. 그런 글자는 저장소에 있을 수 없다. */
	private static boolean readable(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\u0000') {
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
