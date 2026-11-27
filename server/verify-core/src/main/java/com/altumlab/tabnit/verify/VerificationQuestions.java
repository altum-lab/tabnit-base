package com.altumlab.tabnit.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 검증 질문을 읽고 정답과 견주는 일의 공개 창구 (D2 AD-422, D4 5절). 이 모듈의 밖(api)은 이 클래스와 {@link Product},
 * {@link PagedLists} 만 쓴다.
 *
 * <p>명령줄 실행기(verify)와 같은 코드로 읽고 견준다: 그래서 같은 자료에 같은 길로 물으면 통과와 실패가 같다.
 * 묻는 길({@link Product})과 시나리오의 단계마다의 답은 부르는 쪽이 준다. 이 클래스는 제품을 모른다.
 */
public final class VerificationQuestions {

    private VerificationQuestions() {
    }

    /**
     * 시나리오의 한 단계.
     *
     * @param file Pack 폴더 기준 경로
     */
    public record Step(int step, String inputSource, String file, LocalDate effectiveDate) {
    }

    /**
     * 질문 파일 하나: 시나리오 하나와 그 질문.
     *
     * @param fileName 파일의 이름
     * @param questionCount 질문의 수
     */
    public record Scenario(String fileName, String scenarioId, String title, List<Step> steps, int questionCount) {

        public Scenario {
            steps = List.copyOf(steps);
        }
    }

    /**
     * 질문 하나의 결과.
     *
     * @param kindLabel 종류를 사람이 읽는 말로 (시점 조회, 변동 따위). 모르는 종류이면 종류의 이름 그대로다
     * @param label 어느 날짜나 단계를 물었는가. 골든셋의 번호를 적었으면 함께 있다
     * @param expected 기대한 값을 글자로 적은 것. 묻기 전에 막혔으면 null
     * @param actual 제품의 답을 글자로 적은 것. 묻기 전에 막혔으면 null
     * @param reason 견주지 못한 까닭. 견줬으면 null
     */
    public record Result(String id, String kind, String kindLabel, String label, boolean passed, String expected,
            String actual, String reason) {
    }

    /**
     * Pack 폴더의 questions/ 바로 아래의 질문 파일을 읽는다. 전수 대조(questions/crosscheck/)는 읽지 않는다.
     *
     * @param packFolder Pack 의 폴더
     * @return 읽은 것. 파일 이름의 글자 코드 순. 질문 폴더나 파일이 없으면 비어 있다
     * @throws IllegalArgumentException 파일을 읽지 못하거나 형식에 맞지 않을 때. 어느 파일의 어느 항목인지 담는다
     */
    public static List<Loaded> read(Path packFolder) {
        Path folder = packFolder.resolve("questions");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Path> paths;
        try (Stream<Path> children = Files.list(folder)) {
            paths = children.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".yaml") || path.getFileName().toString().endsWith(".yml"))
                .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString(), Evaluator.CODE_POINT_ORDER))
                .toList();
        }
        catch (IOException e) {
            throw new IllegalArgumentException("질문 폴더를 읽지 못했습니다: questions. 폴더의 읽기 권한을 확인하세요.", e);
        }
        List<Loaded> loaded = new ArrayList<>();
        for (Path path : paths) {
            try {
                loaded.add(new Loaded(QuestionFile.read(path)));
            }
            catch (QuestionFileException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
        return loaded;
    }

    /** 읽은 질문 파일 하나. 안의 모양은 이 모듈의 것이다. */
    public static final class Loaded {

        private final QuestionFile file;

        private Loaded(QuestionFile file) {
            this.file = file;
        }

        public Scenario scenario() {
            List<Step> steps = this.file.steps()
                .stream()
                .map(step -> new Step(step.step(), step.inputSource(), step.file(), step.effectiveDate()))
                .toList();
            return new Scenario(this.file.fileName(), this.file.scenarioId(), this.file.title(), steps,
                    this.file.questions().size());
        }

        /**
         * 질문을 번호 순으로 묻고 정답과 견준다. 질문 하나의 오류로 나머지를 멈추지 않는다.
         *
         * @param packId 질문의 개체를 가리킬 Pack 의 식별자
         * @param product 묻는 길
         * @param steps 시나리오의 단계마다, 그 단계의 판을 올렸을 때의 제품의 답 (또는 저장된 그 판의 답). 단계의 번호로 찾는다
         * @return 질문의 번호 순
         */
        public List<Result> evaluate(String packId, Product product, Map<Integer, Product.Reply> steps) {
            return new Evaluator(packId, product).evaluate(this.file, steps)
                .stream()
                .map(result -> new Result(result.id(), result.kind(), Report.kindLabel(result.kind()), result.label(),
                        result.passed(), result.expected(), result.actual(), result.reason()))
                .toList();
        }
    }
}
