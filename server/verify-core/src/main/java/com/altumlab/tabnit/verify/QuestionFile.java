package com.altumlab.tabnit.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * 질문 파일 하나: 시나리오 하나와 그 질문들 (D4 3절).
 *
 * @param fileName 파일의 이름
 * @param scenarioId 시나리오의 번호
 * @param title 시나리오의 이름
 * @param steps 투입하는 차례
 * @param questions 질문. 파일에 적힌 차례 그대로다
 */
record QuestionFile(String fileName, String scenarioId, String title, List<Step> steps, List<Question> questions) {

    /**
     * 시나리오의 한 단계: 어느 입력 자료의 어느 파일을 어떤 적용일로 투입하는가.
     *
     * @param file Pack 폴더 기준 경로
     */
    record Step(int step, String inputSource, String file, LocalDate effectiveDate) {
    }

    /**
     * 질문 하나.
     *
     * @param body 질문에 적힌 항목 전부 (정답 expect 포함)
     */
    record Question(String id, String kind, String title, Map<String, Object> body) {
    }

    /**
     * @throws QuestionFileException 파일을 읽지 못하거나 형식에 맞지 않을 때. 어느 파일의 어느 항목인지 알린다
     */
    static QuestionFile read(Path path) {
        String name = path.getFileName().toString();
        Object loaded;
        try {
            loaded = yaml().load(Files.readString(path, StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            // 파일을 열지 못했다. 예외의 문구에는 파일의 전체 경로가 들어 있다: 밖으로 내보내는 문구에 담지 않는다.
            throw new QuestionFileException(name + ": 질문 파일을 읽지 못했습니다. 파일이 있는지와 읽기 권한을 확인하세요. ("
                    + e.getClass().getSimpleName() + ")", e);
        }
        catch (RuntimeException e) {
            throw new QuestionFileException(name + ": 질문 파일을 읽지 못했습니다. YAML 형식인지 확인하세요. (" + e.getMessage() + ")", e);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new QuestionFileException(name + ": 질문 파일이 비었거나 형식이 틀렸습니다. scenario 와 questions 를 적으세요.");
        }
        Map<String, Object> scenario = map(root.get("scenario"), name, "scenario");
        String scenarioId = text(scenario.get("id"), name, "scenario 의 id");
        List<Step> steps = new ArrayList<>();
        Set<Integer> stepNumbers = new LinkedHashSet<>();
        for (Object item : list(scenario.get("steps"), name, "scenario 의 steps")) {
            Map<String, Object> step = map(item, name, "steps 의 항목");
            int number = integer(step.get("step"), name + ": steps 의 항목의 step (단계의 번호)");
            if (!stepNumbers.add(number)) {
                throw new QuestionFileException(name + ": step " + number + " 이 두 번 적혀 있습니다. 단계의 번호는 겹치지 않아야 합니다.");
            }
            steps.add(new Step(number, text(step.get("inputSource"), name, "step " + number + " 의 inputSource"),
                    text(step.get("file"), name, "step " + number + " 의 file"),
                    date(step.get("effectiveDate"), name + ": step " + number + " 의 effectiveDate")));
        }
        if (steps.isEmpty()) {
            throw new QuestionFileException(name + ": scenario 의 steps 가 비어 있습니다. 투입할 판을 차례대로 적으세요.");
        }
        List<Question> questions = questions(list(root.get("questions"), name, "questions"), name, "질문");
        Object title = scenario.get("title");
        return new QuestionFile(name, scenarioId, title == null ? "" : String.valueOf(title), List.copyOf(steps),
                List.copyOf(questions));
    }

    /** 질문의 목록을 읽는다. 대조 파일의 목록도 같은 모양이다. */
    private static List<Question> questions(List<?> items, String name, String what) {
        List<Question> questions = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : items) {
            Map<String, Object> question = map(item, name, what + "의 항목");
            String id = text(question.get("id"), name, what + "의 id");
            if (!ids.add(id)) {
                throw new QuestionFileException(name + ": " + what + " 번호 " + id + " 이(가) 두 번 적혀 있습니다. 번호는 겹치지 않아야 합니다.");
            }
            String kind = text(question.get("kind"), name, what + " " + id + " 의 kind");
            if (!(question.get("expect") instanceof Map<?, ?>)) {
                throw new QuestionFileException(name + ": " + what + " " + id + " 에 정답(expect)이 없습니다.");
            }
            Object title = question.get("title");
            questions.add(new Question(id, kind, title == null ? "" : String.valueOf(title), question));
        }
        return questions;
    }

    /**
     * 전수 대조의 파일을 읽는다 (D4 3절 "전수 대조", D2 AD-368). 시나리오의 단계는 짝이 되는 질문 파일의 것이다:
     * 같은 자료에 묻는다. 대조의 모양은 질문과 같다.
     *
     * @param shownName 문제를 알릴 때 적는 이름 (questions/ 기준 경로)
     * @param parent 짝이 되는 질문 파일
     * @throws QuestionFileException 읽지 못하거나, 다른 시나리오의 것이거나, 대조가 없을 때
     */
    static QuestionFile readChecks(Path path, String shownName, QuestionFile parent) {
        Object loaded;
        try {
            loaded = yaml().load(Files.readString(path, StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            // 예외의 문구에는 파일의 전체 경로가 들어 있다: 밖으로 내보내는 문구에 담지 않는다.
            throw new QuestionFileException(shownName + ": 대조 파일을 읽지 못했습니다. 파일이 있는지와 읽기 권한을 확인하세요. ("
                    + e.getClass().getSimpleName() + ")", e);
        }
        catch (RuntimeException e) {
            throw new QuestionFileException(shownName + ": 대조 파일을 읽지 못했습니다. YAML 형식인지 확인하세요. (" + e.getMessage() + ")", e);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new QuestionFileException(shownName + ": 대조 파일이 비었거나 형식이 틀렸습니다. scenario 와 checks 를 적으세요.");
        }
        String scenarioId = text(root.get("scenario"), shownName, "scenario (시나리오의 번호)");
        if (!scenarioId.equals(parent.scenarioId())) {
            throw new QuestionFileException(shownName + ": 시나리오 " + scenarioId + " 의 대조인데, 이름이 같은 질문 파일 "
                    + parent.fileName() + " 은 시나리오 " + parent.scenarioId() + " 입니다. 같은 시나리오의 것이어야 합니다.");
        }
        List<Question> checks = questions(list(root.get("checks"), shownName, "checks"), shownName, "대조");
        if (checks.isEmpty()) {
            throw new QuestionFileException(shownName + ": checks 가 비어 있습니다. 대조할 것이 없으면 파일을 두지 않습니다.");
        }
        return new QuestionFile(shownName, parent.scenarioId(), parent.title(), parent.steps(), List.copyOf(checks));
    }

    /**
     * 질문 파일을 읽는 YAML. 자료로만 읽는다: 파일이 자바의 객체를 만들게 하지 않는다.
     *
     * <p>값은 적힌 글자 그대로 읽는다. YAML 의 기본 해석은 따옴표 없는 007 을 수 7 로, yes 를 참으로, 2026-01-01 을
     * 시각으로 바꿔 읽는다. 키와 유형의 이름은 글자이므로 그렇게 읽으면 Pack 에 따라 식별자가 어긋난다.
     * 비어 있음(null, ~)만 그대로 둔다. 수와 날짜가 필요한 자리는 읽는 쪽이 글자에서 푼다.
     */
    private static Yaml yaml() {
        LoaderOptions loading = new LoaderOptions();
        DumperOptions dumping = new DumperOptions();
        return new Yaml(new SafeConstructor(loading), new Representer(dumping), dumping, loading, new Resolver() {
            @Override
            protected void addImplicitResolvers() {
                addImplicitResolver(Tag.NULL, NULL, "~nN\0");
                addImplicitResolver(Tag.NULL, EMPTY, null);
            }
        });
    }

    /** 수로 적어야 하는 자리의 값을 읽는다. */
    static int integer(Object value, String where) {
        try {
            return Integer.parseInt(String.valueOf(value).strip());
        }
        catch (NumberFormatException e) {
            throw new QuestionFileException(where + " 이(가) 정수가 아닙니다: " + value);
        }
    }

    /** 날짜를 읽는다. 2026-03-01 의 꼴이다. */
    static LocalDate date(Object value, String where) {
        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof String text) {
            try {
                return LocalDate.parse(text);
            }
            catch (DateTimeParseException e) {
                // 아래에서 알린다.
            }
        }
        throw new QuestionFileException(where + " 가 날짜가 아닙니다: " + value + ". 2026-03-01 과 같이 적으세요.");
    }

    private static Map<String, Object> map(Object value, String file, String what) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new QuestionFileException(file + ": " + what + " 이(가) 없거나 항목의 묶음이 아닙니다.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<?> list(Object value, String file, String what) {
        if (!(value instanceof List<?> items)) {
            throw new QuestionFileException(file + ": " + what + " 이(가) 없거나 목록이 아닙니다.");
        }
        return items;
    }

    private static String text(Object value, String file, String what) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new QuestionFileException(file + ": " + what + " 이(가) 없습니다.");
        }
        return String.valueOf(value);
    }
}
