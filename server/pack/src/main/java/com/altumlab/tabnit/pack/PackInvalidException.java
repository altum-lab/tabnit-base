package com.altumlab.tabnit.pack;

import java.util.List;

/** Pack 이 형식에 맞지 않거나 정의가 서로 맞지 않는다. 아무것도 적재하지 않았다 (D6 9절). */
public class PackInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<PackProblem> problems;

    public PackInvalidException(String packDirectory, List<PackProblem> problems) {
        super(describe(packDirectory, problems));
        this.problems = List.copyOf(problems);
    }

    /** 찾은 문제 전부. 파일, 항목, 문구 순이다. */
    public List<PackProblem> problems() {
        return problems;
    }

    private static String describe(String packDirectory, List<PackProblem> problems) {
        StringBuilder text = new StringBuilder("Pack 을 적재하지 않았습니다. 폴더: " + packDirectory + ". 문제 " + problems.size()
                + "건을 고친 뒤 다시 적재하세요.");
        for (PackProblem problem : problems) {
            text.append("\n- ");
            if (!problem.file().isEmpty()) {
                text.append(problem.file()).append(' ');
            }
            if (!problem.item().isEmpty()) {
                text.append('[').append(problem.item()).append("] ");
            }
            text.append(problem.message());
        }
        return text.toString();
    }
}
