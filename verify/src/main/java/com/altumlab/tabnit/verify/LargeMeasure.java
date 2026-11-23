package com.altumlab.tabnit.verify;

import com.altumlab.tabnit.core.ImpactDirection;
import com.altumlab.tabnit.core.ImpactQuery;
import com.altumlab.tabnit.core.ImpactRequest;
import com.altumlab.tabnit.core.ImpactScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * 성능 점검 (보드 A-07, D2 AD-351). 큰 시험 자료를 넣은 제품에서 영향 범위 계산과 그 앞뒤의 한 줄기를 잰다.
 *
 * <p>{@link LargePackRun} 이 넣고 수를 견준 뒤에 부른다. 재는 것:
 * <ul>
 * <li>영향 범위 계산만: core 의 공개 API({@link ImpactQuery})를 변동 대상마다 바로 부른다. 저장과 규칙 실행이 들지 않는다.
 * <li>조회: 영향 범위(첫 쪽, 마지막 쪽, 가장 큰 쪽, 대상 하나, 끝까지), 조치, 관계. HTTP 로 묻는다 (화면이 묻는 길이다).
 * <li>저장소: 테이블의 크기와, 시간을 많이 쓴 질의. 오래 걸린 질의의 실행 계획은 파일로 남긴다.
 * </ul>
 *
 * <p>되풀이해 재어 가운데 값과 가장 큰 값을 적는다. 값은 돌리는 곳마다 다르다: 목표와 견주는 일은 사람이 한다
 * (D2 AD-72). 걸린 시간은 여기(엔진 밖)에서 잰다. 제품의 결과에는 닿지 않는다.
 */
final class LargeMeasure {

    /**
     * 영향 범위 계산 하나의 성능 목표 (D2 AD-355, D5 1절). 영향 대상 1,000개쯤에서 0.1초, 10,000개쯤에서 1초이고
     * 그 사이와 너머는 비례로 본다 (하나에 0.1 ms). 0.1초보다 작게 잡지는 않는다. 가운데 값과 견준다.
     *
     * @param impactTargets 영향 대상의 수
     * @return 목표 (밀리초)
     */
    static double targetMillis(long impactTargets) {
        return Math.max(100.0, impactTargets * 0.1);
    }

    /** 오래 걸린 질의로 보는 기준 (실행 계획을 남긴다). */
    private static final int SLOW_MILLIS = 200;

    private static final int PAGE = 50;

    private static final int LARGEST_PAGE = 200;

    private final LargePack.Spec spec;

    private final int rounds;

    private final Path slowLog;

    /**
     * @param rounds 변동마다 되풀이해 재는 횟수. 1 이상
     * @param slowLog 오래 걸린 질의의 실행 계획(저장소의 기록)을 남길 파일
     */
    LargeMeasure(LargePack.Spec spec, int rounds, Path slowLog) {
        if (rounds < 1) {
            throw new IllegalArgumentException("되풀이해 재는 횟수는 1 이상이어야 합니다. 받은 값: " + rounds + ".");
        }
        this.spec = spec;
        this.rounds = rounds;
        this.slowLog = slowLog;
    }

    /**
     * 재는 동안의 저장소 설정. 질의마다의 시간을 모으고(pg_stat_statements), 오래 걸린 질의의 실행 계획을 기록에 남긴다
     * (auto_explain). 실행 계획은 어림값만 남긴다: 질의마다 실제 값을 재게 하면 재는 일이 시간을 바꾼다.
     */
    static String[] databaseCommand() {
        return new String[] { "postgres", "-c", "shared_preload_libraries=pg_stat_statements,auto_explain", "-c",
                "pg_stat_statements.track=all", "-c", "auto_explain.log_min_duration=" + SLOW_MILLIS + "ms", "-c",
                "auto_explain.log_analyze=off" };
    }

    /**
     * 잰 것을 보고에 더한다.
     *
     * @param eventByKey 변동이 난 행의 키마다 그 변동 이벤트의 id
     * @return 재지 못했거나 계산한 수가 셈한 수와 다른가
     */
    boolean after(List<String> lines, LargePack.Expected expected, Map<String, String> eventByKey, HttpProduct http,
            DataSource dataSource, String jdbcUrl, String user, String password) {
        lines.add("── 재기 (변동 " + expected.changes().size() + "개 x " + this.rounds + "번) ──");
        if (expected.changes().isEmpty()) {
            lines.add("  변동이 없어 영향 범위를 재지 않았습니다. 변동의 수를 1 이상으로 주세요.");
            return false;
        }
        boolean failed = false;
        Map<String, List<Long>> samples = new LinkedHashMap<>();
        ImpactQuery impact = new ImpactQuery(dataSource);
        long computed = -1;
        boolean sameCount = true;
        // 영향 대상의 수마다 계산에 걸린 시간 (마이크로초). 목표가 영향 대상의 수에 달려 있다.
        Map<Long, List<Long>> calculations = new TreeMap<>();
        for (int round = 0; round < this.rounds; round++) {
            for (LargePack.ExpectedChange change : expected.changes()) {
                String scopePath = "/api/v1/impact-scopes?changeEventId=" + HttpProduct.encoded(eventByKey.get(change.itemKey()));
                // 끝까지 받는다. 그 안에서 출발 개체, 기준 날짜, 관계 유형을 얻는다.
                Product.Reply whole = timed(samples, "영향 범위 조회: 끝까지 (한 쪽 " + LARGEST_PAGE + "개씩)",
                        () -> http.impactScope(eventByKey.get(change.itemKey())));
                if (whole.status() != 200 || !(whole.body() instanceof Map<?, ?> scope)
                        || !(scope.get("targets") instanceof List<?> targets)) {
                    lines.add("  실패  영향 범위를 받지 못했습니다: " + change.itemKey() + " HTTP " + whole.status());
                    return true;
                }
                if (targets.isEmpty()) {
                    continue;
                }
                Map<?, ?> first = (Map<?, ?>) targets.get(0);
                Map<?, ?> last = (Map<?, ?>) targets.get(targets.size() - 1);
                String start = String.valueOf(((Map<?, ?>) first.get("path")).get("startEntityId"));
                Map<String, ImpactDirection> directions = new TreeMap<>();
                for (Object target : targets) {
                    for (Object step : (List<?>) ((Map<?, ?>) ((Map<?, ?>) target).get("path")).get("steps")) {
                        // 생성기가 만든 관계 유형은 모두 출발에서 도착으로 퍼진다.
                        directions.put(String.valueOf(((Map<?, ?>) step).get("relationType")), ImpactDirection.SOURCE_TO_TARGET);
                    }
                }
                ImpactRequest request = new ImpactRequest(List.of(start),
                        List.of(LocalDate.parse(String.valueOf(scope.get("asOfPrevious"))),
                                LocalDate.parse(String.valueOf(scope.get("asOfEffective")))),
                        directions, ((Number) scope.get("maxDepth")).intValue());
                String calculation = "영향 범위 계산 (core): 영향 대상 " + change.impactTargets() + "개";
                ImpactScope calculated = timed(samples, calculation, () -> impact.impactScope(request));
                List<Long> taken = samples.get(calculation);
                calculations.computeIfAbsent((long) change.impactTargets(), key -> new ArrayList<>())
                    .add(taken.get(taken.size() - 1));
                computed = calculated.targets().size();
                if (computed != change.impactTargets()) {
                    sameCount = false;
                    lines.add("  실패  영향 범위 계산 (core) 의 영향 대상의 수: " + change.itemKey() + " 셈한 수 "
                            + change.impactTargets() + ", 계산한 수 " + computed);
                }
                long total = change.impactTargets();
                failed |= get(samples, lines, http, "영향 범위 조회: 첫 쪽 " + PAGE + "개", scopePath + "&offset=0&limit=" + PAGE);
                failed |= get(samples, lines, http, "영향 범위 조회: 마지막 쪽 " + PAGE + "개",
                        scopePath + "&offset=" + Math.max(0, total - PAGE) + "&limit=" + PAGE);
                failed |= get(samples, lines, http, "영향 범위 조회: 한 쪽 " + LARGEST_PAGE + "개",
                        scopePath + "&offset=0&limit=" + LARGEST_PAGE);
                failed |= get(samples, lines, http, "영향 범위 조회: 대상 하나",
                        scopePath + "&entityId=" + HttpProduct.encoded(String.valueOf(last.get("entityId"))) + "&offset=0&limit="
                                + PAGE);
                String actionsPath = "/api/v1/actions?packId=" + HttpProduct.encoded(expected.packId()) + "&changeEventId="
                        + HttpProduct.encoded(eventByKey.get(change.itemKey()));
                failed |= get(samples, lines, http, "조치 조회: 첫 쪽 " + PAGE + "개", actionsPath + "&offset=0&limit=" + PAGE);
                failed |= get(samples, lines, http, "조치 조회: 한 쪽 " + LARGEST_PAGE + "개",
                        actionsPath + "&offset=0&limit=" + LARGEST_PAGE);
                String relations = "/api/v1/relations?asOf=" + LargePack.CHANGED_DATE + "&offset=0&limit=" + PAGE + "&entityId=";
                failed |= get(samples, lines, http, "관계 조회: 변동 대상 (첫 쪽 " + PAGE + "개)", relations + HttpProduct.encoded(start));
                // 관계 지도 (A-06): 변동 대상을 가운데 두고 깊이마다. 방향을 보지 않으므로 깊이 3 은 위층으로도 퍼진다.
                for (int depth = 1; depth <= 3; depth++) {
                    failed |= get(samples, lines, http, "관계 지도: 변동 대상을 가운데로 깊이 " + depth,
                            "/api/v1/relation-map?asOf=" + LargePack.CHANGED_DATE + "&depth=" + depth + "&entityId="
                                    + HttpProduct.encoded(start));
                }
                // 깊이 1 의 대상: 들어오는 관계와 나가는 관계가 모두 있다.
                failed |= get(samples, lines, http, "관계 조회: 가운데 층의 개체 (첫 쪽 " + PAGE + "개)",
                        relations + HttpProduct.encoded(String.valueOf(first.get("entityId"))));
            }
        }
        if (sameCount && computed >= 0) {
            lines.add("  같음  영향 범위 계산 (core) 의 영향 대상의 수: " + computed);
        }
        samples.forEach((what, taken) -> lines.add(summary(what, taken)));
        // 목표와 견준다. 넘어도 실패로 끝내지는 않는다: 걸린 시간은 기계와 그날의 사정에 따라 흔들린다 (D2 AD-355).
        for (Map.Entry<Long, List<Long>> entry : calculations.entrySet()) {
            double median = median(entry.getValue()) / 1000.0;
            double target = targetMillis(entry.getKey());
            lines.add(String.format("  %s  영향 범위 계산 (core) 의 가운데 값 %.1f ms, 목표 %.1f ms (영향 대상 %d개)",
                    median <= target ? "목표 안" : "목표 넘음", median, target, entry.getKey()));
        }
        failed |= !sameCount;
        failed |= storage(lines, jdbcUrl, user, password);
        return failed;
    }

    /** 저장소의 기록(오래 걸린 질의의 실행 계획이 들어 있다)을 파일로 남긴다. */
    void keepSlowStatements(String databaseLogs) {
        try {
            Files.writeString(this.slowLog, databaseLogs, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("저장소의 기록을 남기지 못했습니다: " + this.slowLog, e);
        }
    }

    private static boolean get(Map<String, List<Long>> samples, List<String> lines, HttpProduct http, String what, String path) {
        Product.Reply reply = timed(samples, what, () -> http.get(path));
        if (reply.status() != 200) {
            lines.add("  실패  " + what + ": HTTP " + reply.status() + " " + reply.body());
            return true;
        }
        return false;
    }

    private static <T> T timed(Map<String, List<Long>> samples, String what, Supplier<T> work) {
        long started = System.nanoTime();
        T result = work.get();
        samples.computeIfAbsent(what, key -> new ArrayList<>()).add((System.nanoTime() - started) / 1_000);
        return result;
    }

    /** 가운데 값과 가장 큰 값 (마이크로초로 재어 밀리초로 적는다). 잰 횟수가 짝수이면 가운데 둘 가운데 큰 쪽을 적는다. */
    private static String summary(String what, List<Long> micros) {
        long[] sorted = micros.stream().mapToLong(Long::longValue).sorted().toArray();
        return String.format("  %-42s 가운데 %8.1f ms   가장 큼 %8.1f ms   (%d번)", what, median(micros) / 1000.0,
                sorted[sorted.length - 1] / 1000.0, sorted.length);
    }

    /** 가운데 값 (마이크로초). 잰 횟수가 짝수이면 가운데 둘 가운데 큰 쪽이다. */
    private static long median(List<Long> micros) {
        long[] sorted = micros.stream().mapToLong(Long::longValue).sorted().toArray();
        return sorted[sorted.length / 2];
    }

    /** 테이블의 크기와, 시간을 많이 쓴 질의. 이 실행이 띄운 저장소의 통계만 읽는다. */
    private boolean storage(List<String> lines, String jdbcUrl, String user, String password) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                Statement statement = connection.createStatement()) {
            try (ResultSet size = statement.executeQuery("select pg_database_size(current_database())")) {
                size.next();
                lines.add(String.format("── 저장소의 크기: %.1f MB ──", size.getLong(1) / 1_048_576.0));
            }
            // 행의 수는 저장소의 통계에서 읽는다 (어림값이다). 통계를 먼저 새로 고친다: 재기가 모두 끝난 뒤다.
            statement.execute("analyze");
            try (ResultSet tables = statement.executeQuery("""
                    select c.relname, c.reltuples::bigint, pg_total_relation_size(c.oid), pg_indexes_size(c.oid)
                    from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where c.relkind = 'r' and n.nspname = 'public' order by 3 desc, 1 limit 8""")) {
                while (tables.next()) {
                    lines.add(String.format("  %-28s 행 약 %,10d   크기 %8.1f MB (그 가운데 색인 %.1f MB)", tables.getString(1),
                            tables.getLong(2), tables.getLong(3) / 1_048_576.0, tables.getLong(4) / 1_048_576.0));
                }
            }
            statement.execute("create extension if not exists pg_stat_statements");
            lines.add("── 시간을 많이 쓴 질의 (넣기부터 재기까지 모두. 오래 걸린 질의의 실행 계획: " + this.slowLog.getFileName() + ") ──");
            try (ResultSet top = statement.executeQuery("""
                    select calls, total_exec_time, mean_exec_time, max_exec_time, rows, query
                    from pg_stat_statements
                    where query not ilike '%pg_stat%' and query not ilike 'create %' and query not ilike 'analyze%'
                    order by total_exec_time desc, query limit 12""")) {
                while (top.next()) {
                    String query = top.getString(6).replaceAll("\\s+", " ").strip();
                    lines.add(String.format("  모두 %9.0f ms   %,9d번   평균 %8.2f ms   가장 큼 %8.1f ms   행 %,d", top.getDouble(2),
                            top.getLong(1), top.getDouble(3), top.getDouble(4), top.getLong(5)));
                    lines.add("      " + (query.length() > 230 ? query.substring(0, 230) + " …" : query));
                }
            }
            return false;
        }
        catch (SQLException e) {
            lines.add("  실패  저장소의 통계를 읽지 못했습니다: " + e.getMessage());
            return true;
        }
    }

    @Override
    public String toString() {
        return "LargeMeasure" + Arrays.asList(this.spec, this.rounds, this.slowLog);
    }
}
