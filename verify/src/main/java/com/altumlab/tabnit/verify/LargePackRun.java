package com.altumlab.tabnit.verify;

import com.altumlab.tabnit.api.TabnitApiApplication;
import com.altumlab.tabnit.common.Role;
import com.altumlab.tabnit.common.UserAccounts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 큰 시험 자료를 제품에 넣어, 넣은 뒤의 수가 생성기가 셈한 수와 같은지 본다 (보드 T-07, D2 AD-350).
 *
 * <p>빈 저장소로 제품을 띄우고 HTTP 로만 넣고 묻는다 (검증 질문 실행기와 같은 길이다). 검증 질문과 달리 목록의
 * 내용과 순서를 정답과 견주지 않는다: 수만 본다. 맞는지를 보는 일은 예시 Pack 의 검증 질문이 맡는다.
 * 단계마다 걸린 시간을 함께 적는다. 목표와 견주는 일은 성능 점검(A-07)이 한다.
 */
final class LargePackRun {

    private LargePackRun() {
    }

    /**
     * 한 번 돌린 결과.
     *
     * @param lines 보고의 줄. 단계마다 한 줄, 견준 수마다 한 줄
     * @param failed 수가 다르거나 넣지 못한 것이 있는가
     */
    record Result(List<String> lines, boolean failed) {

        Result {
            lines = List.copyOf(lines);
        }
    }

    /**
     * @param expected 생성기가 셈한 수와 올릴 파일
     * @param packsDir 만든 Pack 폴더가 든 폴더
     */
    static Result run(LargePack.Expected expected, Path packsDir) {
        return run(expected, packsDir, null);
    }

    /**
     * @param measure 수를 견준 뒤에 이어서 잴 것 (A-07). 없으면 재지 않는다
     */
    static Result run(LargePack.Expected expected, Path packsDir, LargeMeasure measure) {
        List<String> lines = new ArrayList<>();
        try (PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
            if (measure != null) {
                database.withCommand(LargeMeasure.databaseCommand());
            }
            database.start();
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("spring.datasource.url", database.getJdbcUrl());
            settings.put("spring.datasource.username", database.getUsername());
            settings.put("spring.datasource.password", database.getPassword());
            settings.put("server.port", "0");
            settings.put("tabnit.packs-dir", packsDir.toString());
            settings.put("spring.main.banner-mode", "off");
            settings.put("logging.level.root", "WARN");
            try (ConfigurableApplicationContext product = new SpringApplicationBuilder(TabnitApiApplication.class)
                .properties(settings)
                // 큰 파일을 올린다. 파일 크기의 한도(기본 20MB)는 이 실행에서만 넉넉히 둔다. 제품의 설정 파일에 적힌 값을
                // 이기도록 실행 인자로 넘긴다.
                .run("--spring.servlet.multipart.max-file-size=512MB", "--spring.servlet.multipart.max-request-size=513MB")) {
                // 첫 관리자는 common 의 공개 API 로 만든다. 이 계정은 이 실행의 저장소와 함께 사라진다.
                String loginId = "large-admin";
                String password = UUID.randomUUID().toString();
                product.getBean(UserAccounts.class).create(loginId, password, Role.ADMIN, Instant.parse("2026-07-01T00:00:00Z"));
                int port = Integer.parseInt(product.getEnvironment().getProperty("local.server.port"));
                HttpProduct http = new HttpProduct(port);
                if (http.login(loginId, password).status() != 200) {
                    return failed(lines, "제품에 로그인하지 못했습니다.");
                }
                Map<String, String> eventByKey = new LinkedHashMap<>();
                boolean failed = check(expected, packsDir.resolve(expected.packId()), http, lines, eventByKey);
                if (!failed && measure != null) {
                    failed = measure.after(lines, expected, eventByKey, http, product.getBean(DataSource.class),
                            database.getJdbcUrl(), database.getUsername(), database.getPassword());
                    measure.keepSlowStatements(database.getLogs());
                }
                lines.add(failed ? "결과: 실패" : "결과: 통과 (넣은 뒤의 수가 셈한 수와 같습니다)");
                return new Result(lines, failed);
            }
        }
        catch (IOException | RuntimeException e) {
            return failed(lines, "제품을 띄우거나 자료를 넣지 못했습니다: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static boolean check(LargePack.Expected expected, Path pack, HttpProduct http, List<String> lines,
            Map<String, String> eventByKey) throws IOException {
        long started = System.nanoTime();
        Product.Reply loaded = http.loadPack(expected.packId());
        lines.add(String.format("%8d ms  Pack 적재", millisSince(started)));
        if (loaded.status() != 200) {
            lines.add("  실패  Pack 을 적재하지 못했습니다: HTTP " + loaded.status() + " " + loaded.body());
            return true;
        }
        boolean failed = false;
        long entities = 0;
        long relations = 0;
        Map<?, ?> changed = null;
        for (LargePack.Step step : expected.steps()) {
            byte[] content = Files.readAllBytes(pack.resolve(step.file()));
            started = System.nanoTime();
            Product.Reply reply = http.submitEdition(expected.packId(), step.inputSource(), step.effectiveDate(),
                    pack.resolve(step.file()).getFileName().toString(), content);
            long took = millisSince(started);
            if (reply.status() != 200 || !(reply.body() instanceof Map<?, ?> body)) {
                lines.add("  실패  판을 올리지 못했습니다: " + step.file() + " HTTP " + reply.status() + " " + reply.body());
                return true;
            }
            long items = body.get("itemCount") instanceof Number count ? count.longValue() : -1;
            int notices = body.get("notices") instanceof List<?> list ? list.size() : -1;
            lines.add(String.format("%8d ms  판 올리기 %s  항목 %d개, 알림 %d건", took, step.file(), items, notices));
            if (notices != 0) {
                failed = true;
                lines.add("  실패  알림이 없어야 하는데 " + notices + "건 있습니다: " + step.file());
            }
            if (step.effectiveDate().equals(LargePack.CHANGED_DATE)) {
                changed = body;
                // 바뀐 판을 올리는 요청 하나가 판 비교, 변동마다의 영향 범위 계산과 저장, 규칙 실행을 모두 한다.
                lines.add(String.format("%8.1f ms  변동 하나에 (바뀐 판 올리기 %d ms 를 변동 %d개로 나눈 값. 판 비교가 들어 있다)",
                        took / (double) expected.changes().size(), took, expected.changes().size()));
            }
            else if (step.inputSource().startsWith("layer-")) {
                entities += items;
            }
            else {
                relations += items;
            }
        }
        failed |= differs(lines, "개체의 수", expected.entities(), entities);
        failed |= differs(lines, "관계의 수", expected.relations(), relations);
        // 변동마다: 항목의 키로 찾아 영향 대상의 수와 조치의 수를 견준다.
        if (changed != null && changed.get("changes") instanceof List<?> changes) {
            for (Object change : changes) {
                if (change instanceof Map<?, ?> one) {
                    eventByKey.put(String.valueOf(one.get("itemKey")), String.valueOf(one.get("changeEventId")));
                    Object status = one.get("processing") instanceof Map<?, ?> processing ? processing.get("status") : null;
                    if (!"PROCESSED".equals(status)) {
                        failed = true;
                        lines.add("  실패  처리되지 않은 변동이 있습니다: " + one.get("itemKey") + " (" + status + ")");
                    }
                }
            }
        }
        failed |= differs(lines, "변동의 수", expected.changes().size(), eventByKey.size());
        for (LargePack.ExpectedChange change : expected.changes()) {
            String eventId = eventByKey.get(change.itemKey());
            if (eventId == null) {
                failed = true;
                lines.add("  실패  변동이 나오지 않았습니다: " + change.itemKey());
                continue;
            }
            started = System.nanoTime();
            Product.Reply scope = http.impactScope(eventId);
            long scopeTook = millisSince(started);
            started = System.nanoTime();
            Product.Reply actions = http.actions(expected.packId(), eventId);
            long actionsTook = millisSince(started);
            lines.add(String.format("%8d ms  영향 범위 읽기 %s (조치 읽기 %d ms)", scopeTook, change.itemKey(), actionsTook));
            failed |= differs(lines, change.itemKey() + " 의 영향 대상의 수", change.impactTargets(), total(scope));
            failed |= differs(lines, change.itemKey() + " 의 조치의 수", change.actions(), total(actions));
        }
        return failed;
    }

    private static long total(Product.Reply reply) {
        return reply.status() == 200 && reply.body() instanceof Map<?, ?> body && body.get("total") instanceof Number total
                ? total.longValue() : -1;
    }

    private static boolean differs(List<String> lines, String what, long expected, long actual) {
        if (expected == actual) {
            lines.add("  같음  " + what + ": " + actual);
            return false;
        }
        lines.add("  실패  " + what + ": 셈한 수 " + expected + ", 넣은 뒤의 수 " + actual);
        return true;
    }

    private static Result failed(List<String> lines, String reason) {
        lines.add("  실패  " + reason);
        lines.add("결과: 실패");
        return new Result(lines, true);
    }

    private static long millisSince(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
