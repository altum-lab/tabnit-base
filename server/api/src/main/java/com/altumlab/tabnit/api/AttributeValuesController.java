package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.AttributeValue;
import com.altumlab.tabnit.core.AttributeValueQuery;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.pack.ValueFormat;
import com.altumlab.tabnit.rules.ActionRecords;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 속성 값의 시점 조회 API. 규격은 docs/api/openapi.yaml 의 getAttributeValuesAsOf, 규칙은 D7 4절 "속성 값의 시점 조회"다
 * (카드 A-19, D2 AD-420).
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 어느 값이 그 날짜에 유효한지와 순서는 core 가 정한다. 이 클래스는 순서를 바꾸지
 * 않고 값을 고르지 않는다. 이름표와 보일 글자만 Pack 의 정의에서 붙인다.
 */
@RestController
class AttributeValuesController {

	/** 참과 거짓을 화면에 보일 글자 (D2 AD-381). 저장한 글자는 true, false 다 (D6 5절). */
	private static final String TRUE_DISPLAY = "예";

	private static final String FALSE_DISPLAY = "아니오";

	private final AttributeValueQuery values;

	private final PackDefinitions packs;

	private final EntityViews entityViews;

	private final Clock clock;

	private final ZoneId installationZone;

	AttributeValuesController(AttributeValueQuery values, PackDefinitions packs, EntityViews entityViews, Clock clock,
			ZoneId installationZone) {
		this.values = values;
		this.packs = packs;
		this.entityViews = entityViews;
		this.clock = clock;
		this.installationZone = installationZone;
	}

	/** 규격의 AttributeValuesAsOf. values 의 순서는 core 가 준 그대로다 (속성의 이름, 시작일). */
	record Response(String asOf, String entityId, EntityViews.Item entity, List<ValueItem> values) {
	}

	/**
	 * 규격의 AttributeValueAsOf.
	 *
	 * @param validTo 종료일. 없으면 null 로 적는다. 항목을 빼지 않는다
	 */
	record ValueItem(String attributeName, String attributeLabel, String value, String valueDisplay, String validFrom,
			String validTo, RelationsAsOfResponse.ProvenanceItem provenance, String recordedAt) {
	}

	@GetMapping("/api/v1/attribute-values")
	@Audited(action = "getAttributeValuesAsOf", params = "entityId")
	Response valuesAsOf(@RequestParam(required = false) String entityId, @RequestParam(required = false) String asOf) {
		if (entityId == null || entityId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_ENTITY_ID", "entityId",
					"개체의 id 가 없습니다. entityId 에 개체의 id 를 넣어 다시 보내세요.");
		}
		// 쓸 수 없는 글자(NUL, 깨진 글자)가 든 id 의 개체는 있을 수 없다: 없는 개체가 아니라 틀린 입력이다 (D2 AD-370).
		if (!ActionRecords.isReadableEntityId(entityId)) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_ENTITY_ID", "entityId",
					"개체의 id 에 쓸 수 없는 글자가 있습니다. 서버가 준 id 를 그대로 넣어 다시 보내세요.");
		}
		LocalDate date = resolveAsOf(asOf);
		AuditTrail.asOf(date);
		// 없는 개체이면 core 가 알린다 (404).
		List<AttributeValue> found = this.values.valuesAsOf(entityId, date);
		EntityViews.Item entity = EntityViews.require(this.entityViews.items(List.of(entityId), date), entityId, "속성 값의 개체");
		// 이름표와 형식을 이름으로 찾는 데만 쓴다.
		String packId = found.isEmpty() ? null : found.get(0).packId();
		Optional<PackDefinition> pack = packId == null ? Optional.empty() : this.packs.find(packId);
		Map<String, String> labels = new TreeMap<>();
		Map<String, ValueFormat> formats = new TreeMap<>();
		pack.ifPresent(definition -> definition.attributes().forEach(attribute -> {
			labels.put(attribute.name(), attribute.label());
			formats.put(attribute.name(), attribute.valueFormat());
		}));
		InputSourceLabels sources = this.entityViews.inputSourceLabels();
		List<ValueItem> items = found.stream().map(value -> {
			LocalDate to = value.period().to();
			boolean truth = formats.get(value.attributeName()) == ValueFormat.BOOLEAN;
			return new ValueItem(value.attributeName(), labels.getOrDefault(value.attributeName(), value.attributeName()),
					value.value(), display(value.value(), truth), value.period().from().toString(),
					to == null ? null : to.toString(),
					new RelationsAsOfResponse.ProvenanceItem(value.provenance().inputSourceId(),
							sources.label(value.packId(), value.provenance().inputSourceId()), value.provenance().editionNo()),
					value.recordedAt().toString());
		}).toList();
		return new Response(date.toString(), entityId, entity, items);
	}

	private static String display(String value, boolean truth) {
		if (!truth) {
			return value;
		}
		// 저장한 글자가 둘 가운데 하나가 아니면 (정의가 바뀌기 전에 들어간 값) 그대로 보인다.
		return "true".equals(value) ? TRUE_DISPLAY : "false".equals(value) ? FALSE_DISPLAY : value;
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
