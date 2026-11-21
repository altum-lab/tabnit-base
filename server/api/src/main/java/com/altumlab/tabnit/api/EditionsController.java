package com.altumlab.tabnit.api;

import com.altumlab.tabnit.link.Edition;
import com.altumlab.tabnit.link.EditionRecords;
import com.altumlab.tabnit.link.IngestOutcome;
import com.altumlab.tabnit.link.IngestResult;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 판을 올리는 API. 규격은 docs/api/openapi.yaml 의 submitEdition, 규칙은 D7 5절이다.
 *
 * <p>받고, 확인하고, 넘기고, 돌려준다. 투입과 변동의 처리는 {@link EditionProcessing} 이 순서를 잡아 각 모듈에 맡긴다.
 * 판의 투입은 반드시 그 서비스로 한다 (D2 AD-235).
 */
@RestController
class EditionsController {

	/** `2026-03-01` 모양만 받는다. 없는 날짜(2월 30일)는 받지 않는다. */
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
		.withResolverStyle(ResolverStyle.STRICT);

	private static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";

	private final EditionProcessing processing;

	private final EditionRecords editions;

	EditionsController(EditionProcessing processing, EditionRecords editions) {
		this.processing = processing;
		this.editions = editions;
	}

	@PostMapping(path = "/api/v1/editions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	EditionSubmissionResponse submit(@RequestParam(required = false) String packId,
			@RequestParam(required = false) String inputSourceId, @RequestParam(required = false) String effectiveDate,
			@RequestParam(required = false) MultipartFile file) {
		require(packId, "MISSING_PACK_ID", "packId", "Pack 의 식별자가 없습니다. packId 에 Pack 의 식별자를 넣어 다시 보내세요.");
		require(inputSourceId, "MISSING_INPUT_SOURCE_ID", "inputSourceId",
				"입력 자료의 식별자가 없습니다. inputSourceId 에 입력 자료의 식별자를 넣어 다시 보내세요.");
		require(effectiveDate, "MISSING_EFFECTIVE_DATE", "effectiveDate",
				"적용일이 없습니다. effectiveDate 에 2026-03-01 과 같이 적어 다시 보내세요.");
		LocalDate date;
		try {
			date = LocalDate.parse(effectiveDate, DATE);
		}
		catch (DateTimeParseException e) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_EFFECTIVE_DATE", "effectiveDate",
					"적용일의 형식이 틀렸습니다. 2026-03-01 과 같이 적어 다시 보내세요.");
		}
		if (file == null) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_FILE", "file",
					"파일이 없습니다. file 에 올릴 파일을 넣어 다시 보내세요.");
		}
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException e) {
			throw new IllegalStateException("올린 파일을 읽지 못했습니다.", e);
		}
		EditionProcessingResult result = this.processing.submit(packId, inputSourceId, fileName(file), content, date);
		IngestResult ingest = result.ingest();
		if (ingest.outcome() == IngestOutcome.REJECTED) {
			if (FILE_TOO_LARGE.equals(ingest.reasonCode())) {
				throw new EditionRejectedException(ingest.reasonCode(), ingest.reasonDetail(), true,
						"파일이 너무 큽니다. 받는 크기의 한도는 " + this.processing.maxFileBytes()
								+ " 바이트입니다. 파일을 나누거나 줄여 다시 올리세요.");
			}
			throw new EditionRejectedException(ingest.reasonCode(), ingest.reasonDetail(), false,
					EditionRejections.message(ingest.reasonCode(), ingest.reasonDetail()));
		}
		Edition edition = this.editions.findEdition(ingest.editionId())
			.orElseThrow(() -> new IllegalStateException("투입한 판 " + ingest.editionId() + " 을 읽지 못했습니다."));
		return EditionSubmissionResponse.of(result, edition, result.editionChanges());
	}

	private static void require(String value, String code, String field, String detail) {
		if (value == null || value.isBlank()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, code, field, detail);
		}
	}

	/** 올린 파일의 이름. 브라우저에 따라 폴더까지 붙여 보내므로 마지막 부분만 남긴다. 없으면 null. */
	private static String fileName(MultipartFile file) {
		String name = file.getOriginalFilename();
		if (name == null) {
			return null;
		}
		name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
		return name.isEmpty() ? null : name;
	}
}
