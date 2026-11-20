package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.ValidityPeriod;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 뼈대 확인용 API. 모듈이 서로 이어졌는지, 조회 시점을 받는지 본다. 실제 API는 D7 을 따른다. */
@RestController
public class PingController {

    private static final ValidityPeriod SAMPLE = new ValidityPeriod(LocalDate.of(2025, 3, 1), LocalDate.of(2026, 9, 1));

    @GetMapping("/api/ping")
    public Map<String, Object> ping(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate at = asOf != null ? asOf : LocalDate.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "tabnit-base");
        body.put("asOf", at.toString());
        body.put("sampleRelationValid", SAMPLE.contains(at));
        return body;
    }
}
