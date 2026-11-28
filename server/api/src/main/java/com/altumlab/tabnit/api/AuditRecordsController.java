package com.altumlab.tabnit.api;

import com.altumlab.tabnit.common.AuditFilter;
import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditOutcome;
import com.altumlab.tabnit.common.AuditPage;
import com.altumlab.tabnit.common.AuditRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 감사 기록을 보는 API (규격의 listAuditRecords, D7 "감사 기록 보기", D2 AD-409, AD-414). 관리자만 부른다:
 * 역할은 {@link SecurityConfiguration} 이 확인한다.
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 어느 줄이 맞는지와 순서, 나누는 일은 common 이 한다. 줄을 그대로 준다:
 * 요청 하나의 두 줄(시작, 결과)을 묶지 않는다. 고치거나 지우는 길은 없다. 보는 것도 감사 기록에 남는다.
 */
@RestController
class AuditRecordsController {

	static final String PATH = "/api/v1/audit-records";

	/** 조건으로 받는 글자의 길이의 한도. 감사 기록이 담는 길이와 같다 (D5 7절). */
	private static final int MAX_ACTOR_LENGTH = 200;

	private static final int MAX_ACTION_LENGTH = 100;

	private final AuditLog audit;

	private final ZoneId installationZone;

	AuditRecordsController(AuditLog audit, ZoneId installationZone) {
		this.audit = audit;
		this.installationZone = installationZone;
	}

	/** 규격의 AuditRecordList. 거르는 조건은 답에 쓴 값이다 (없었으면 null). */
	record ListResponse(String actor, String action, String outcome, String from, String to, Long maxId, int total, int offset,
			int limit, List<Item> records) {
	}

	/** 규격의 AuditRecord. 시각은 협정 세계시, 날짜는 2026-03-01 의 모양이다. */
	record Item(long id, String occurredAt, String actor, String action, String target, String asOf, String outcome,
			Integer httpStatus, String detail, Long startedId) {

		static Item of(AuditRecord record) {
			return new Item(record.id(), record.occurredAt().toString(), record.actor(), record.action(), record.target(),
					record.asOf() == null ? null : record.asOf().toString(), record.outcome().name(), record.httpStatus(),
					record.detail(), record.startedId());
		}
	}

	@GetMapping(PATH)
	@Audited(action = "listAuditRecords", params = { "actor", "action", "outcome", "from", "to" })
	ListResponse list(@RequestParam(required = false) String actor, @RequestParam(required = false) String action,
			@RequestParam(required = false) String outcome, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String maxId,
			@RequestParam(required = false) String offset,
			@RequestParam(required = false) String limit) {
		requireText(actor, MAX_ACTOR_LENGTH, "INVALID_ACTOR", "actor", "누가(actor)");
		requireText(action, MAX_ACTION_LENGTH, "INVALID_ACTION", "action", "무엇을(action)");
		AuditOutcome wanted = outcome == null ? null : outcome(outcome);
		LocalDate fromDate = from == null ? null : date(from, "INVALID_FROM", "from", "시작 날짜(from)");
		LocalDate toDate = to == null ? null : date(to, "INVALID_TO", "to", "끝 날짜(to)");
		if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_TO", "to",
					"끝 날짜가 시작 날짜보다 앞섭니다. 끝 날짜를 시작 날짜와 같거나 뒤의 날짜로 넣어 다시 보내세요.");
		}
		Long upTo = maxId == null ? null : maxId(maxId);
		PageRequest page = PageRequest.of(offset, limit);
		// 날짜는 설치 설정의 시간대의 하루다 (D2 AD-40). 시작 날짜의 첫 순간부터, 끝 날짜의 다음 날의 첫 순간 앞까지다:
		// 끝 날짜의 하루를 모두 담는다.
		Instant fromInstant = fromDate == null ? null : fromDate.atStartOfDay(this.installationZone).toInstant();
		Instant toInstant = toDate == null ? null : toDate.plusDays(1).atStartOfDay(this.installationZone).toInstant();
		// 거르고 나누는 일은 common 이 한다.
		AuditPage found = this.audit.page(new AuditFilter(actor, action, wanted, fromInstant, toInstant, upTo), page.offset(),
				page.limit());
		return new ListResponse(actor, action, wanted == null ? null : wanted.name(),
				fromDate == null ? null : fromDate.toString(), toDate == null ? null : toDate.toString(), upTo, found.total(),
				page.offset(), page.limit(), found.records().stream().map(Item::of).toList());
	}

	/** 항목이 있는데 비었거나, 너무 길거나, 저장소가 담지 못하는 글자가 들었으면 틀린 값이다. 조용히 거르지 않는 것으로 보지 않는다. */
	private static void requireText(String value, int maxLength, String code, String field, String what) {
		if (value == null) {
			return;
		}
		if (value.isEmpty() || value.codePointCount(0, value.length()) > maxLength || value.indexOf('\u0000') >= 0) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, code, field, what + "의 값이 비었거나 너무 길거나 쓸 수 없는 글자가"
					+ " 있습니다. " + maxLength + "자까지의 값을 넣거나 그 항목을 빼고 다시 보내세요.");
		}
	}

	/** 일련번호의 위쪽 끝 (D2 AD-424). 1 이상의 정수다. */
	private static long maxId(String value) {
		try {
			long parsed = Long.parseLong(value);
			if (parsed >= 1) {
				return parsed;
			}
		}
		catch (NumberFormatException e) {
			// 아래에서 틀린 값으로 알린다.
		}
		throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_MAX_ID", "maxId",
				"일련번호의 위쪽 끝(maxId)의 값이 틀렸습니다. 첫 쪽의 맨 위 줄의 id 를 그대로 넣거나 그 항목을 빼고 다시 보내세요.");
	}

	private static AuditOutcome outcome(String value) {
		for (AuditOutcome candidate : AuditOutcome.values()) {
			if (candidate.name().equals(value)) {
				return candidate;
			}
		}
		throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_OUTCOME", "outcome",
				"결과의 값이 틀렸습니다. STARTED, SUCCESS, DENIED, FAILED 가운데 하나를 넣어 다시 보내세요.");
	}

	private static LocalDate date(String value, String code, String field, String what) {
		LocalDate date;
		try {
			date = LocalDate.parse(value);
		}
		catch (DateTimeParseException e) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, code, field,
					what + "의 형식이 틀렸습니다. 2026-03-01 과 같이 적어 다시 보내세요.");
		}
		// 받는 날짜는 0001-01-01 부터 9999-12-31 까지다 (D7 2절). 끝 날짜에 하루를 더해도 담을 수 있는 범위다.
		if (date.getYear() < 1 || date.getYear() > 9999) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, code, field,
					what + " " + value + " 은(는) 쓸 수 없는 날짜입니다. 0001-01-01 부터 9999-12-31 까지의 날짜를 넣어 다시 보내세요.");
		}
		return date;
	}
}
