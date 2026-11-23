package com.altumlab.tabnit.verify;

import com.altumlab.tabnit.api.TabnitApiApplication;
import com.altumlab.tabnit.common.Role;
import com.altumlab.tabnit.common.UserAccounts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 검증 질문 실행기 (D4 5절). 명령은 {@code bash verify/run.sh} 다.
 *
 * <p>저장소의 Pack 을 모두 찾아, Pack 마다 시나리오마다: 빈 저장소로 제품을 띄우고, Pack 을 적재하고, 시나리오대로 판을
 * 올린 뒤, 질문을 번호 순으로 물어 정답과 견준다 (D2 AD-110). 같은 일을 한 번 더 해 두 번의 결과가 같은지 본다.
 * 질문별 통과와 실패의 목록을 낸다. Pack 을 가리지 않는다: 질문과 정답은 Pack 안에만 있다.
 *
 * <p>묻는 길은 모두 HTTP 다 (D2 AD-279): Pack 적재, 판 올리기, 네 종류의 질문. 제품이 실제로 내놓는 값을 본다.
 */
public final class VerifyMain {

    /** 제품의 시계가 시작하는 때. 실행한 날에 따라 결과가 달라지지 않게 고정한다 (D4 3절). 읽을 때마다 1초씩 간다. */
    private static final Instant START = Instant.parse("2026-07-01T00:00:00Z");

    /** 시나리오의 단계는 이만큼 이상이다 (D2 AD-112). */
    private static final int MIN_STEPS = 3;

    private VerifyMain() {
    }

    /**
     * @param args 저장소의 Pack 폴더(packs/), 보고를 적을 파일
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("쓰는 법: VerifyMain <Pack 폴더> <보고를 적을 파일>. 보통은 bash verify/run.sh 로 돌린다.");
            System.exit(2);
        }
        Path packs = Path.of(args[0]).toRealPath();
        StringBuilder report = new StringBuilder();
        boolean failed = false;
        int asked = 0;
        int passed = 0;
        int checksAsked = 0;
        int checksPassed = 0;
        List<String> empty = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        for (Path pack : packFolders(packs)) {
            String packId = pack.getFileName().toString();
            PackQuestions read = readPack(pack);
            if (!read.hasContent()) {
                // 폴더만 있고 정의도 질문도 없다. 실패로 치지 않고 알리기만 한다 (D2 AD-281).
                empty.add(packId);
                continue;
            }
            ran.add(packId);
            List<String> problems = read.problems();
            if (!problems.isEmpty()) {
                failed = true;
                report.append("Pack: ").append(packId).append("   질문의 문제 ").append(problems.size()).append("건\n");
                for (String problem : problems) {
                    report.append("  실패  ").append(problem).append('\n');
                }
            }
            // 정의가 없으면 적재할 수 없다. 위에서 알렸다.
            List<QuestionFile> files = Files.isRegularFile(pack.resolve("pack.ttl")) ? read.files() : List.of();
            for (QuestionFile file : files) {
                // 전수 대조가 있으면 같은 실행에서 함께 묻는다: 시나리오를 한 번 더 넣지 않는다 (D2 AD-368).
                QuestionFile checks = read.checks().get(file.fileName());
                Run first = runOnce(packs, pack, file, checks);
                Run second = runOnce(packs, pack, file, checks);
                List<String> differences = Report.differences(first.questions(), second.questions());
                report.append(Report.scenario(packId, file.scenarioId(), first.questions(), differences));
                asked += first.questions().size();
                passed += (int) first.questions().stream().filter(Evaluator.Result::passed).count();
                failed |= !differences.isEmpty() || first.questions().stream().anyMatch(result -> !result.passed());
                if (checks != null) {
                    List<String> checkDifferences = Report.differences(first.checks(), second.checks());
                    report.append(Report.checks(packId, file.scenarioId(), first.checks(), checkDifferences));
                    checksAsked += first.checks().size();
                    checksPassed += (int) first.checks().stream().filter(Evaluator.Result::passed).count();
                    failed |= !checkDifferences.isEmpty() || first.checks().stream().anyMatch(result -> !result.passed());
                }
            }
            report.append('\n');
        }
        report.append("전체: 통과 ").append(passed).append(" / ").append(asked).append("   돌린 Pack ").append(ran.size())
            .append("개").append(ran.isEmpty() ? "" : " (" + String.join(", ", ran) + ")").append('\n');
        // 대조는 질문과 따로 센다: 확정한 정답이 아니라 따로 계산한 값과 견준 것이다 (D4 3절 "전수 대조").
        if (checksAsked > 0) {
            report.append("대조: 통과 ").append(checksPassed).append(" / ").append(checksAsked)
                .append("   (엔진과 따로 계산한 값과 견준 것이다. 질문의 수에 넣지 않는다)\n");
        }
        if (!empty.isEmpty()) {
            report.append("아직 내용이 없는 Pack ").append(empty.size()).append("개 (").append(String.join(", ", empty))
                .append("): 돌리지 않았습니다. 출시 기준은 모든 Pack 의 통과입니다.\n");
        }
        report.append("묻는 길: 모두 HTTP (Pack 적재, 판 올리기, 시점 조회, 변동, 영향 범위, 조치, 관계 지도)\n");
        if (ran.isEmpty()) {
            failed = true;
            report.append("실패  돌릴 Pack 이 하나도 없습니다: ").append(packs).append('\n');
        }
        report.append(failed ? "결과: 실패\n" : "결과: 통과\n");
        Path out = Path.of(args[1]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.print(report);
        System.out.flush();
        // 띄웠던 것들의 남은 스레드를 기다리지 않고 끝낸다.
        System.exit(failed ? 1 : 0);
    }

    /**
     * 한 번 돌린 결과.
     *
     * @param questions 질문의 결과. 번호 순
     * @param checks 전수 대조의 결과. 번호 순. 대조 파일이 없으면 비어 있다
     */
    record Run(List<Evaluator.Result> questions, List<Evaluator.Result> checks) {
    }

    /**
     * 빈 저장소로 제품을 띄워 시나리오를 한 번 돌린다.
     *
     * @param checks 같은 자료에 함께 물을 전수 대조. 없으면 null
     */
    private static Run runOnce(Path packs, Path pack, QuestionFile file, QuestionFile checks) {
        String packId = pack.getFileName().toString();
        try (PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))) {
            database.start();
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("spring.datasource.url", database.getJdbcUrl());
            settings.put("spring.datasource.username", database.getUsername());
            settings.put("spring.datasource.password", database.getPassword());
            settings.put("server.port", "0");
            settings.put("tabnit.packs-dir", packs.toString());
            settings.put("spring.main.banner-mode", "off");
            settings.put("logging.level.root", "WARN");
            AtomicLong seconds = new AtomicLong();
            Clock fixed = new TickingClock(seconds);
            try (ConfigurableApplicationContext product = new SpringApplicationBuilder(TabnitApiApplication.class)
                .properties(settings)
                // 제품의 시계를 고정한 것으로 바꾼다. 실행한 날에 결과가 달라지지 않는다.
                .initializers(context -> context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                    @Override
                    public Object postProcessAfterInitialization(Object bean, String beanName) {
                        return bean instanceof Clock ? fixed : bean;
                    }
                }))
                .run()) {
                // 첫 관리자는 common 의 공개 API 로 만든다. 이 계정은 이 실행의 저장소와 함께 사라진다.
                String loginId = "verify-admin";
                // 비밀번호는 실행마다 새로 만든다. 제품의 규칙(12자 이상, 72바이트 이하)에 맞는 길이다.
                String password = UUID.randomUUID().toString();
                product.getBean(UserAccounts.class).create(loginId, password, Role.ADMIN, START);
                int port = Integer.parseInt(product.getEnvironment().getProperty("local.server.port"));
                HttpProduct http = new HttpProduct(port);
                Product.Reply login = http.login(loginId, password);
                if (login.status() != 200) {
                    return allFailed(file, checks, "제품에 로그인하지 못했습니다: HTTP " + login.status());
                }
                Product.Reply loaded = http.loadPack(packId);
                if (loaded.status() != 200) {
                    return allFailed(file, checks, "Pack 을 적재하지 못했습니다: HTTP " + loaded.status() + " " + loaded.body());
                }
                Map<Integer, Product.Reply> steps = new LinkedHashMap<>();
                for (QuestionFile.Step step : file.steps()) {
                    Path data = pack.resolve(step.file()).normalize();
                    if (!data.startsWith(pack) || !Files.isRegularFile(data)) {
                        return allFailed(file, checks,
                                "시나리오 " + step.step() + "단계의 파일이 Pack 폴더 안에 없습니다: " + step.file());
                    }
                    steps.put(step.step(), http.submitEdition(packId, step.inputSource(), step.effectiveDate(),
                            data.getFileName().toString(), Files.readAllBytes(data)));
                }
                Evaluator evaluator = new Evaluator(packId, http);
                return new Run(evaluator.evaluate(file, steps),
                        checks == null ? List.of() : evaluator.evaluate(checks, steps));
            }
        }
        catch (IOException | RuntimeException e) {
            return allFailed(file, checks,
                    "제품을 띄우거나 시나리오를 돌리지 못했습니다: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 질문을 묻기 전에 막혔다. 건너뛰지 않고 모든 질문과 대조를 실패로 낸다. */
    private static Run allFailed(QuestionFile file, QuestionFile checks, String reason) {
        return new Run(allFailed(file, reason), checks == null ? List.of() : allFailed(checks, reason));
    }

    private static List<Evaluator.Result> allFailed(QuestionFile file, String reason) {
        List<QuestionFile.Question> ordered = new ArrayList<>(file.questions());
        ordered.sort(java.util.Comparator.comparing(QuestionFile.Question::id, Evaluator.CODE_POINT_ORDER));
        List<Evaluator.Result> results = new ArrayList<>();
        for (QuestionFile.Question question : ordered) {
            results.add(new Evaluator.Result(question.id(), question.kind(), "", false, null, null, reason));
        }
        return results;
    }

    /**
     * Pack 폴더 하나에서 읽은 것.
     *
     * @param hasContent 정의 파일이나 질문 파일이 하나라도 있는가. 없으면 아직 내용이 없는 Pack 이다
     * @param files 읽은 질문 파일. 이름의 글자 코드 순
     * @param problems 질문의 문제: 읽지 못한 파일, 겹친 번호, 짧은 시나리오, 최소 수에 모자란 것. 하나라도 있으면 실패다
     * @param checks 전수 대조. 짝이 되는 질문 파일의 이름으로 찾는다. 없는 Pack 은 비어 있다 (D2 AD-368)
     */
    record PackQuestions(boolean hasContent, List<QuestionFile> files, List<String> problems, Map<String, QuestionFile> checks) {
    }

    /** Pack 폴더의 질문 파일을 모두 읽고 문제를 모은다. Pack 을 가리지 않는다. */
    static PackQuestions readPack(Path pack) throws IOException {
        List<Path> paths = questionFiles(pack);
        boolean defined = Files.isRegularFile(pack.resolve("pack.ttl"));
        if (!defined && paths.isEmpty()) {
            return new PackQuestions(false, List.of(), List.of(), Map.of());
        }
        List<String> problems = new ArrayList<>();
        if (!defined) {
            problems.add("질문 파일은 있는데 Pack 의 정의 파일(pack.ttl)이 없습니다. 적재할 수 없어 질문을 돌리지 못합니다.");
        }
        List<QuestionFile> files = new ArrayList<>();
        for (Path path : paths) {
            try {
                files.add(QuestionFile.read(path));
            }
            catch (QuestionFileException e) {
                problems.add(e.getMessage());
            }
        }
        List<QuestionFile.Question> all = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (QuestionFile file : files) {
            // 시나리오에는 판이 셋 이상 있어야 현재와 과거 두 시점을 물을 수 있다 (D2 AD-112).
            if (file.steps().size() < MIN_STEPS) {
                problems.add(file.fileName() + ": 시나리오의 단계가 " + file.steps().size() + "개입니다. " + MIN_STEPS
                        + "개 이상이어야 합니다.");
            }
            problems.addAll(Report.editionShortfalls(file));
            for (QuestionFile.Question question : file.questions()) {
                if (!ids.add(question.id())) {
                    problems.add("질문 번호 " + question.id() + " 이(가) 이 Pack 의 여러 파일에 있습니다 (" + file.fileName()
                            + "). 번호는 Pack 안에서 겹치지 않아야 합니다.");
                }
                all.add(question);
            }
        }
        // 질문이 없거나 최소 수에 모자란 Pack 은 실패다 (D2 AD-113, AD-281).
        problems.addAll(Report.shortfalls(all));
        // 시점 조회는 현재와 과거 두 시점, 셋 이상의 날짜를 묻는다 (D2 AD-112, AD-332). Pack 의 모든 파일을 합쳐 센다.
        problems.addAll(Report.timePointShortfalls(all));
        // 전수 대조: questions/crosscheck/ 에 질문 파일과 같은 이름으로 둔다. 틀린 파일은 건너뛰지 않고 알린다.
        Map<String, QuestionFile> checks = new LinkedHashMap<>();
        for (Path path : checkFiles(pack)) {
            String name = path.getFileName().toString();
            String shown = "crosscheck/" + name;
            QuestionFile parent = files.stream().filter(file -> file.fileName().equals(name)).findFirst().orElse(null);
            if (parent == null) {
                problems.add(shown + ": 이름이 같은 질문 파일이 없습니다 (읽지 못했거나 없다). 대조 파일은 questions/ 의 질문 파일과 "
                        + "같은 이름으로 둡니다.");
                continue;
            }
            try {
                checks.put(name, QuestionFile.readChecks(path, shown, parent));
            }
            catch (QuestionFileException e) {
                problems.add(e.getMessage());
            }
        }
        return new PackQuestions(true, List.copyOf(files), List.copyOf(problems), java.util.Collections.unmodifiableMap(checks));
    }

    /** 바로 아래의 폴더. 이름의 글자 코드 순. */
    static List<Path> packFolders(Path parent) throws IOException {
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(Files::isDirectory)
                .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString(), Evaluator.CODE_POINT_ORDER))
                .toList();
        }
    }

    /** Pack 의 questions/ 에 있는 질문 파일 (yaml, yml). 이름의 글자 코드 순. 폴더가 없으면 비어 있다. */
    private static List<Path> questionFiles(Path pack) throws IOException {
        return yamlFiles(pack.resolve("questions"));
    }

    /** Pack 의 questions/crosscheck/ 에 있는 전수 대조의 파일. 이름의 글자 코드 순. 폴더가 없으면 비어 있다. */
    private static List<Path> checkFiles(Path pack) throws IOException {
        return yamlFiles(pack.resolve("questions").resolve("crosscheck"));
    }

    private static List<Path> yamlFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(folder)) {
            return children.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".yaml") || path.getFileName().toString().endsWith(".yml"))
                .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString(), Evaluator.CODE_POINT_ORDER))
                .toList();
        }
    }

    /** 읽을 때마다 1초씩 가는 시계. 받은 때가 올린 차례대로 늘어난다. */
    private static final class TickingClock extends Clock {

        private final AtomicLong seconds;

        TickingClock(AtomicLong seconds) {
            this.seconds = seconds;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return START.plusSeconds(this.seconds.incrementAndGet());
        }
    }
}
