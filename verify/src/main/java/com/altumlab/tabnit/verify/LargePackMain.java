package com.altumlab.tabnit.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 큰 시험 자료를 만들고 제품에 넣어 수를 견준다 (보드 T-07). bash verify/large.sh 가 부른다.
 *
 * <p>인자: 만들 곳, 개체의 수, 관계의 수, 층의 수, 변동의 수, 그리고 넣어 볼지 (run 이면 넣어 수를 견준다.
 * measure 이면 이어서 영향 범위 계산과 조회를 잰다: 보드 A-07).
 * 만든 Pack 은 저장소에 올리지 않는다 (빌드 폴더 아래에 둔다).
 */
public final class LargePackMain {

    private LargePackMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 6) {
            System.err.println("인자가 여섯이어야 합니다: 만들 곳, 개체의 수, 관계의 수, 층의 수, 변동의 수, run 또는 measure 또는 only");
            System.exit(2);
        }
        Path out = Path.of(args[0]).toAbsolutePath().normalize();
        LargePack.Spec spec;
        try {
            int entities = Integer.parseInt(args[1]);
            int relations = Integer.parseInt(args[2]);
            spec = new LargePack.Spec("large-" + entities + "-" + relations, entities, relations, Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]));
        }
        catch (NumberFormatException e) {
            System.err.println("[실패] 개체의 수, 관계의 수, 층의 수, 변동의 수는 수로 적으세요.");
            System.exit(2);
            return;
        }
        LargePack.Expected expected;
        try {
            Files.createDirectories(out);
            expected = LargePack.generate(spec, out);
        }
        catch (IllegalArgumentException e) {
            System.err.println("[실패] " + e.getMessage());
            System.exit(2);
            return;
        }
        StringBuilder report = new StringBuilder();
        report.append("큰 시험 자료: ").append(out.resolve(spec.packId())).append('\n');
        report.append("개체 ").append(spec.entities()).append(", 관계 ").append(spec.relations()).append(", 층 ")
            .append(spec.layers()).append(", 변동 ").append(spec.changes()).append('\n');
        for (LargePack.ExpectedChange change : expected.changes()) {
            report.append("  셈한 수  ").append(change.itemKey()).append(": 영향 대상 ").append(change.impactTargets())
                .append(", 조치 ").append(change.actions()).append('\n');
        }
        boolean failed = false;
        if ("run".equals(args[5]) || "measure".equals(args[5])) {
            // 재는 횟수: 변동마다 세 번.
            LargeMeasure measure = "measure".equals(args[5])
                    ? new LargeMeasure(spec, 3, out.resolve(spec.packId() + "-slow.log")) : null;
            LargePackRun.Result result = LargePackRun.run(expected, out, measure);
            result.lines().forEach(line -> report.append(line).append('\n'));
            failed = result.failed();
        }
        Files.writeString(out.resolve(spec.packId() + "-report.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.print(report);
        System.out.flush();
        // 띄웠던 것들의 남은 스레드를 기다리지 않고 끝낸다.
        System.exit(failed ? 1 : 0);
    }
}
