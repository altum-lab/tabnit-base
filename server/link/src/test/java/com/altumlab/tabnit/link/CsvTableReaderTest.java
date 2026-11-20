package com.altumlab.tabnit.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * C-01 수용 기준: 표 형식 파일을 읽어 행 단위로 넘긴다. 이 시험은 CSV 를 본다 (D2 AD-168).
 *
 * <p>일반 시험을 먼저 둔다 (지어낸 값: 빈 파일, 경계, 큰 입력, 잘못된 입력). 그 위에 저장소에 들어 있는 Pack 의
 * 입력 파일을 모두 읽어 본다. 특정 파일의 열 이름과 수는 시험에 적지 않는다 (D6 7절 "입력 파일").
 */
class CsvTableReaderTest {

    // ── 읽기 ────────────────────────────────────────────────────

    @Test
    void 첫_행은_열_이름이고_나머지는_행이다() {
        TableFile file = read("code,name,count\nA-1,first,3\nA-2,second,\n");

        assertThat(file.columns()).containsExactly("code", "name", "count");
        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1", "first", "3")),
                new TableRow(3, List.of("A-2", "second", "")));
        assertThat(file.skipped()).isEmpty();
    }

    @Test
    void 열_이름으로_칸의_값을_읽는다() {
        TableFile file = read("code,name\nA-1,first\n");

        assertThat(file.value(file.rows().get(0), "name")).isEqualTo("first");
        assertThat(file.hasColumn("code")).isTrue();
        assertThat(file.hasColumn("missing")).isFalse();
        assertThatThrownBy(() -> file.value(file.rows().get(0), "missing")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing");
    }

    @Test
    void 큰따옴표로_감싼_값은_쉼표와_줄바꿈과_큰따옴표를_담는다() {
        TableFile file = read("code,note\nA-1,\"a, b\"\nA-2,\"line one\nline two\"\nA-3,\"say \"\"hi\"\"\"\nA-4,\"\"\n");

        assertThat(file.rows()).extracting(r -> r.values().get(1))
            .containsExactly("a, b", "line one\nline two", "say \"hi\"", "");
    }

    @Test
    void 감싸지_않은_값_가운데의_큰따옴표는_글자_그대로다() {
        TableFile file = read("code,note\nA-1,5\" pipe\n");

        assertThat(file.rows().get(0).values()).containsExactly("A-1", "5\" pipe");
    }

    @Test
    void 줄은_어느_줄바꿈으로_끝나도_되고_마지막_줄바꿈은_없어도_된다() {
        List<List<String>> expected = List.of(List.of("A-1", "x"), List.of("A-2", "y"));

        for (String content : List.of("c,v\nA-1,x\nA-2,y\n", "c,v\r\nA-1,x\r\nA-2,y\r\n", "c,v\rA-1,x\rA-2,y\r",
                "c,v\nA-1,x\r\nA-2,y", "c,v\r\nA-1,x\nA-2,y")) {
            TableFile file = read(content);
            assertThat(file.rows()).as(content.replace("\r", "\\r").replace("\n", "\\n"))
                .extracting(TableRow::values)
                .isEqualTo(expected);
            assertThat(file.rows()).extracting(TableRow::rowNo).containsExactly(2, 3);
        }
    }

    @Test
    void 열_이름과_값의_앞뒤_빈칸을_떼고_가운데의_빈칸은_그대로_둔다() {
        // 빈칸, 탭, 전각 빈칸.
        TableFile file = read(" code ,\tname\t\n  A-1  ,  two words  \n\u3000A-2\u3000,\"  quoted  \"\n");

        assertThat(file.columns()).containsExactly("code", "name");
        assertThat(file.rows()).extracting(TableRow::values)
            .containsExactly(List.of("A-1", "two words"), List.of("A-2", "quoted"));
    }

    @Test
    void 빈칸만_있는_칸은_빈_칸이다() {
        TableFile file = read("code,name\nA-1,   \nA-2,\"  \"\n");

        assertThat(file.rows()).extracting(r -> r.values().get(1)).containsExactly("", "");
    }

    @Test
    void 감싼_값_안의_줄바꿈은_어떻게_적혀_있든_같은_값으로_읽는다() {
        // 같은 내용을 LF, CR LF, CR 로 저장한 세 파일. 값이 다르게 읽히면 판 비교에서 가짜 변경이 된다.
        TableFile lf = read("code,note\nA-1,\"x\ny\"\nA-2,z\n");
        TableFile crLf = read("code,note\r\nA-1,\"x\r\ny\"\r\nA-2,z\r\n");
        TableFile cr = read("code,note\rA-1,\"x\ry\"\rA-2,z\r");

        assertThat(lf.rows().get(0).values()).containsExactly("A-1", "x\ny");
        assertThat(crLf).isEqualTo(lf);
        assertThat(cr).isEqualTo(lf);
        // 줄바꿈이 든 값의 다음 행은 그만큼 뒤의 줄에서 시작한다.
        assertThat(lf.rows()).extracting(TableRow::rowNo).containsExactly(2, 4);
    }

    @Test
    void 큰따옴표를_적는_여러_경우를_정해진_대로_읽는다() {
        TableFile file = read("code,note\n1,  \"q\"\n2,\"a\"\"b\"\n3,\"\"\"\"\n4,\"\"\n5,\"  x \n\"\n");

        // 칸 앞의 빈칸 뒤에 여는 큰따옴표, 값 안의 큰따옴표, 큰따옴표 하나뿐인 값, 빈 값, 감싼 값의 앞뒤 빈칸과 줄바꿈.
        assertThat(file.rows()).extracting(r -> r.values().get(1)).containsExactly("q", "a\"b", "\"", "", "x");
        assertThat(file.skipped()).isEmpty();
    }

    @Test
    void 줄의_끝이_쉼표이면_마지막_칸이_빈_칸이고_파일의_끝이_CR이어도_읽는다() {
        TableFile file = read("code,note\nA-1,\nA-2,x\r");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1", "")), new TableRow(3, List.of("A-2", "x")));
    }

    @Test
    void 열_이름에_줄바꿈이_든_파일도_행_번호가_맞다() {
        TableFile file = read("code,\"two\nlines\"\nA-1,x\n");

        assertThat(file.columns()).containsExactly("code", "two\nlines");
        assertThat(file.rows()).containsExactly(new TableRow(3, List.of("A-1", "x")));
    }

    @Test
    void 줄바꿈이_든_값_뒤에_이어지는_행들의_번호가_맞다() {
        // 1: 열 이름, 2~3: 줄바꿈이 든 값의 행, 4: 칸이 모자란 행, 5: 닫는 큰따옴표 뒤에 글자, 6: 맞는 행
        TableFile file = read("a,b\n\"x\ny\",c\n3\n4,\"q\"r\n5,6\n");

        assertThat(file.rows()).extracting(TableRow::rowNo).containsExactly(2, 6);
        assertThat(file.skipped()).containsExactly(new SkippedRow(4, "CELL_COUNT_MISMATCH", "1"),
                new SkippedRow(5, "TEXT_AFTER_QUOTE", null));
    }

    @Test
    void 맨_앞의_표시는_하나만_떼고_끝에_빈_열이_붙은_열_이름은_거부한다() {
        // 표시가 둘이면 둘째는 첫 열 이름의 글자로 남는다. 파일을 이어 붙인 것이다.
        assertThat(read("\uFEFF\uFEFFcode,note\nA-1,x\n").columns().get(0)).isEqualTo("\uFEFFcode");
        // 빈 열을 가진 표에서 나온 파일이다. 열 이름이 비어 있으므로 파일 전체를 거부한다.
        assertThat(rejection("code,note,\nA-1,x,\n").code()).isEqualTo("BLANK_COLUMN_NAME");
    }

    @Test
    void 큰따옴표가_아주_많은_칸과_열이_아주_많은_파일도_오래_걸리지_않는다() {
        String manyQuotes = "x" + "\"".repeat(400_000);
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            wide.append(i == 0 ? "" : ",").append('c').append(i);
        }

        org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(5), () -> {
            assertThat(read("code,note\nA-1," + manyQuotes + "\n").rows().get(0).values().get(1)).isEqualTo(manyQuotes);
            assertThat(read(wide + "\n").columns()).hasSize(20_000);
        });
    }

    // ── 인코딩 (D2 AD-169) ───────────────────────────────────────

    @Test
    void 맨_앞의_표시가_있는_UTF8_파일을_받는다() {
        byte[] body = "코드,이름\n가-1,첫째\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        TableFile file = CsvTableReader.read(withBom);

        assertThat(file.columns()).containsExactly("코드", "이름");
        assertThat(file.rows().get(0).values()).containsExactly("가-1", "첫째");
        assertThat(CsvTableReader.read(body)).isEqualTo(file);
    }

    @Test
    void UTF8이_아닌_파일은_전체를_거부한다() {
        byte[] cp949 = "코드,이름\n가-1,첫째\n".getBytes(Charset.forName("MS949"));
        byte[] utf16 = "code,name\nA-1,first\n".getBytes(StandardCharsets.UTF_16);
        byte[] broken = { 'c', '\n', 'a', (byte) 0xC3 };

        for (byte[] content : List.of(cp949, utf16, broken)) {
            TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                    () -> CsvTableReader.read(content));
            assertThat(e).isNotNull();
            assertThat(e.code()).isEqualTo("NOT_UTF8");
            assertThat(e).hasMessageContaining("UTF-8");
        }
    }

    // ── 파일 전체를 거부하는 것 ──────────────────────────────────────

    @Test
    void 열_이름의_줄이_없는_파일은_거부한다() {
        for (String content : List.of("", "\n", "\r\n\r\n", "\uFEFF")) {
            assertThat(rejection(content).code()).as("[" + content.length() + "]").isEqualTo("NO_HEADER");
        }
    }

    @Test
    void 열_이름만_있는_파일은_행이_없다() {
        TableFile file = read("code,name\n");

        assertThat(file.columns()).containsExactly("code", "name");
        assertThat(file.rows()).isEmpty();
        assertThat(read("code,name")).isEqualTo(file);
    }

    @Test
    void 열_이름이_비었거나_겹치면_거부하고_어느_열인지_알린다() {
        assertThat(rejection("code,,name\nA,B,C\n").code()).isEqualTo("BLANK_COLUMN_NAME");
        assertThat(rejection("code,   ,name\nA,B,C\n")).hasMessageContaining("2");
        assertThat(rejection("code,name,code\nA,B,C\n").code()).isEqualTo("DUPLICATE_COLUMN_NAME");
        // 앞뒤 빈칸을 떼고 나서 같으면 겹친 것이다.
        assertThat(rejection("code, code \nA,B\n")).hasMessageContaining("code");
        assertThat(rejection("co\u0000de,name\nA,B\n").code()).isEqualTo("INVALID_COLUMN_NAME");
    }

    @Test
    void 큰따옴표가_닫히지_않은_채_끝나면_거부하고_시작한_줄을_알린다() {
        TableFileRejectedException e = rejection("code,note\nA-1,ok\nA-2,\"never closed\nA-3,x\n");

        assertThat(e.code()).isEqualTo("UNCLOSED_QUOTE");
        assertThat(e).hasMessageContaining("3");
    }

    // ── 그 행만 건너뛰는 것 ─────────────────────────────────────────

    @Test
    void 칸_수가_다른_행은_건너뛰고_행_번호와_칸의_수를_남긴다() {
        TableFile file = read("code,name,count\nA-1,first,3\nA-2,second\nA-3,third,3,extra\nA-4,fourth,4\n");

        assertThat(file.rows()).extracting(TableRow::rowNo).containsExactly(2, 5);
        assertThat(file.skipped()).containsExactly(new SkippedRow(3, "CELL_COUNT_MISMATCH", "2"),
                new SkippedRow(4, "CELL_COUNT_MISMATCH", "4"));
    }

    @Test
    void 닫는_큰따옴표_뒤에_글자가_이어지는_행과_받지_않는_글자가_든_행은_건너뛴다() {
        TableFile file = read("code,note\nA-1,\"closed\" trailing\nA-2,ok\nA-3,nul\u0000here\nA-4,ok\n");

        assertThat(file.rows()).extracting(TableRow::rowNo).containsExactly(3, 5);
        assertThat(file.skipped()).extracting(SkippedRow::rowNo, SkippedRow::code)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "TEXT_AFTER_QUOTE"),
                    org.assertj.core.groups.Tuple.tuple(4, "INVALID_CHARACTER"));
    }

    @Test
    void 닫는_큰따옴표_뒤의_빈칸은_글자가_아니다() {
        TableFile file = read("code,note\nA-1,\"closed\"  \n");

        assertThat(file.rows().get(0).values()).containsExactly("A-1", "closed");
        assertThat(file.skipped()).isEmpty();
    }

    // ── 행 번호 (D2 AD-171) ──────────────────────────────────────

    @Test
    void 행_번호는_그_행이_시작하는_줄의_번호이고_빈_줄은_행이_아니지만_줄에는_센다() {
        // 1: 열 이름, 2: 빈 줄, 3: A-1, 4~5: 줄바꿈이 든 A-2, 6: 빈 줄, 7: A-3
        TableFile file = read("code,note\n\nA-1,x\nA-2,\"two\nlines\"\n\nA-3,y\n");

        assertThat(file.rows()).extracting(TableRow::rowNo).containsExactly(3, 4, 7);
        assertThat(file.rows()).extracting(r -> r.values().get(0)).containsExactly("A-1", "A-2", "A-3");
    }

    @Test
    void 열_이름_앞의_빈_줄도_줄에_센다() {
        TableFile file = read("\n\ncode,note\nA-1,x\n");

        assertThat(file.columns()).containsExactly("code", "note");
        assertThat(file.rows()).extracting(TableRow::rowNo).containsExactly(4);
    }

    @Test
    void 쉼표만_있는_줄은_모든_칸이_빈_행이다() {
        TableFile file = read("code,note\n,\nA-1,x\n");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("", "")), new TableRow(3, List.of("A-1", "x")));
    }

    @Test
    void 열이_하나뿐인_파일도_읽는다() {
        TableFile file = read("code\nA-1\n\nA-2\n   \n");

        // 빈칸만 있는 줄은 빈 줄이 아니다. 칸 하나가 빈 행이다.
        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1")), new TableRow(4, List.of("A-2")),
                new TableRow(5, List.of("")));
    }

    // ── 큰 입력, 같은 입력에 같은 결과 ───────────────────────────────

    @Test
    void 행이_많아도_모두_읽고_적힌_차례를_지킨다() {
        int count = 50_000;
        StringBuilder content = new StringBuilder("code,name\n");
        for (int i = 0; i < count; i++) {
            content.append("K-").append(i).append(",이름 ").append(i).append('\n');
        }

        TableFile file = read(content.toString());

        assertThat(file.rows()).hasSize(count);
        assertThat(file.rows().get(0)).isEqualTo(new TableRow(2, List.of("K-0", "이름 0")));
        assertThat(file.rows().get(count - 1)).isEqualTo(new TableRow(count + 1, List.of("K-" + (count - 1), "이름 " + (count - 1))));
    }

    @Test
    void 아주_긴_값과_기본_평면_밖의_글자도_그대로_읽는다() {
        String longValue = "가".repeat(200_000);
        TableFile file = read("code,note\nA-1," + longValue + "\nA-2,\uD83D\uDE00 ok\n");

        assertThat(file.rows().get(0).values().get(1)).isEqualTo(longValue);
        assertThat(file.rows().get(1).values().get(1)).isEqualTo("\uD83D\uDE00 ok");
    }

    @Test
    void 같은_파일을_두_번_읽으면_결과가_같다() {
        String content = "code,name\nA-2,second\nA-1,first\nbad\nA-3,\"x\" y\n";

        assertThat(read(content)).isEqualTo(read(content));
        // 행의 차례는 파일에 적힌 차례다. 정렬하지 않는다.
        assertThat(read(content).rows()).extracting(r -> r.values().get(0)).containsExactly("A-2", "A-1");
    }

    // ── 저장소에 들어 있는 Pack 의 입력 파일 ──────────────────────────────

    @ParameterizedTest
    @MethodSource("repositoryCsvFiles")
    void 저장소의_입력_파일이_건너뛴_행_없이_읽힌다(Path csv) throws IOException {
        byte[] content = Files.readAllBytes(csv);

        TableFile file = CsvTableReader.read(content);

        assertThat(file.columns()).isNotEmpty().doesNotHaveDuplicates();
        assertThat(file.rows()).isNotEmpty();
        assertThat(file.skipped()).isEmpty();
        // 줄바꿈이 든 값이 없는 파일이면 행의 수는 열 이름 다음의 비지 않은 줄의 수와 같다.
        long lines = new String(content, StandardCharsets.UTF_8).lines().filter(l -> !l.isEmpty()).count() - 1;
        assertThat((long) file.rows().size()).isEqualTo(lines);
        assertThat(file.rows()).allSatisfy(r -> assertThat(r.values()).hasSameSizeAs(file.columns()));
    }

    static Stream<Path> repositoryCsvFiles() throws IOException {
        // 작업 폴더는 server/link 다.
        try (Stream<Path> files = Files.walk(Path.of("../../packs"))) {
            return files.filter(p -> p.toString().endsWith(".csv")).sorted().toList().stream();
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private static TableFile read(String content) {
        return CsvTableReader.read(content.getBytes(StandardCharsets.UTF_8));
    }

    private static TableFileRejectedException rejection(String content) {
        TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class, () -> read(content));
        assertThat(e).as("거부돼야 하는 파일이 읽혔다").isNotNull();
        return e;
    }
}
