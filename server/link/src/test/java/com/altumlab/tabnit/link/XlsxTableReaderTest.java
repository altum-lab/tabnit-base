package com.altumlab.tabnit.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * C-01 수용 기준 가운데 엑셀: 표 형식 파일을 읽어 행 단위로 넘긴다 (D6 7절 "엑셀 파일", D2 AD-93, AD-373 ~ AD-376).
 *
 * <p>Pack 과 무관한 일반 시험이다. 파일은 시험이 지어낸 값으로 만든다.
 */
class XlsxTableReaderTest {

    // ── 열 이름과 행 ──────────────────────────────────────────────

    @Test
    void 첫_행은_열_이름이고_행_번호는_시트의_행_번호다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("자료");
            strings(sheet, 0, "code", "name");
            strings(sheet, 1, "A-1", "첫째");
            strings(sheet, 2, "A-2", "둘째");
        });

        TableFile file = XlsxTableReader.read(content, "자료");

        assertThat(file.columns()).containsExactly("code", "name");
        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1", "첫째")),
                new TableRow(3, List.of("A-2", "둘째")));
        assertThat(file.skipped()).isEmpty();
    }

    @Test
    void 열_이름과_값의_앞뒤_빈칸을_떼고_칸_안의_줄바꿈은_LF_하나로_읽는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "  code ", "name ");
            strings(sheet, 1, " A-1　", " 첫 줄\r\n둘째 줄 ");
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.columns()).containsExactly("code", "name");
        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1", "첫 줄\n둘째 줄")));
    }

    @Test
    void 빈_행은_행으로_보지_않지만_행_번호에는_센다_열_이름의_앞에_있어도_같다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            sheet.createRow(0);
            strings(sheet, 2, "code", "name");
            strings(sheet, 3, "A-1", "x");
            strings(sheet, 4, "", " ");
            strings(sheet, 6, "A-2", "y");
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).containsExactly(new TableRow(4, List.of("A-1", "x")),
                new TableRow(7, List.of("A-2", "y")));
        assertThat(file.skipped()).isEmpty();
    }

    @Test
    void 빈_칸은_빈_글자이고_열_이름이_없는_열의_칸은_읽지_않는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "name", "", "");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("A-1");
            // 이름이 없는 열에 적힌 메모.
            row.createCell(3).setCellValue("메모");
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.columns()).containsExactly("code", "name");
        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1", "")));
    }

    // ── 수 ──────────────────────────────────────────────────────

    @Test
    void 수가_든_칸은_서식과_무관하게_저장된_값을_읽는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "amount");
            number(wb, sheet, 1, 0.725, "0.0");
            number(wb, sheet, 2, 1234567, "#,##0");
            number(wb, sheet, 3, 0.7, "0%");
            number(wb, sheet, 4, 3.0, "0.00");
            number(wb, sheet, 5, 0.1 + 0.2, null);
            number(wb, sheet, 6, -0.0, null);
            number(wb, sheet, 7, 1.0e21, "0.00E+00");
            number(wb, sheet, 8, -12.5, null);
            number(wb, sheet, 9, 0.000001, null);
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).extracting(row -> row.values().get(1))
            .containsExactly("0.725", "1234567", "0.7", "3", "0.3", "0", "1000000000000000000000", "-12.5",
                    "0.000001");
        assertThat(file.rows()).allSatisfy(row -> {
            assertThat(file.typed(row, "amount")).isTrue();
            assertThat(file.typed(row, "code")).isFalse();
        });
    }

    @Test
    void 글자로_적힌_수는_적힌_그대로이고_형식을_아는_칸이_아니다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "amount");
            strings(sheet, 1, "007", "1,234.50");
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("007", "1,234.50")));
        assertThat(file.typed(file.rows().get(0), "amount")).isFalse();
    }

    // ── 날짜 ────────────────────────────────────────────────────

    @Test
    void 날짜가_든_칸은_서식과_무관하게_표준_표기로_읽는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "since");
            // 14 는 엑셀의 기본 날짜 서식이다. 파일에 모양이 적혀 있지 않고 보는 PC 의 설정을 따른다.
            date(wb, sheet, 1, LocalDateTime.parse("2026-03-01T00:00:00"), 14, null);
            date(wb, sheet, 2, LocalDateTime.parse("2026-03-01T00:00:00"), -1, "yyyy\"년\" m\"월\" d\"일\"");
            date(wb, sheet, 3, LocalDateTime.parse("2026-03-01T00:00:00"), -1, "dd/mm/yy");
            date(wb, sheet, 4, LocalDateTime.parse("2026-12-31T14:30:00"), 22, null);
            date(wb, sheet, 5, LocalDateTime.parse("1999-12-31T23:59:59"), -1, "yyyy-mm-dd hh:mm:ss");
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).extracting(row -> row.values().get(1))
            .containsExactly("2026-03-01", "2026-03-01", "2026-03-01", "2026-12-31T14:30:00", "1999-12-31T23:59:59");
        assertThat(file.rows()).allSatisfy(row -> assertThat(file.typed(row, "since")).isTrue());
    }

    @Test
    void 시각만_든_칸은_시각으로_읽는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "since");
            Cell cell = sheet.createRow(1).createCell(1);
            cell.setCellValue(0.5);
            cell.setCellStyle(style(wb, 20, null));
            sheet.getRow(1).createCell(0).setCellValue("A-1");
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1", "12:00:00"), Set.of(1)));
    }

    @Test
    void 날짜를_1904년부터_세는_파일도_같은_날짜로_읽는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            // 새 통합 문서에는 이 설정의 자리가 이미 있다. 하나 더 만들면 앞의 것만 읽힌다.
            (wb.getCTWorkbook().isSetWorkbookPr() ? wb.getCTWorkbook().getWorkbookPr()
                    : wb.getCTWorkbook().addNewWorkbookPr()).setDate1904(true);
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "since");
            Cell cell = sheet.createRow(1).createCell(1);
            // 1904년부터 세면 1 은 1904-01-02 이다.
            cell.setCellValue(1.0);
            cell.setCellStyle(style(wb, 14, null));
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows().get(0).values().get(1)).isEqualTo("1904-01-02");
    }

    @Test
    void 한국어_엑셀에_들어_있는_날짜_서식의_칸도_날짜로_읽는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "since");
            // 31 은 한국어 엑셀의 "2026년 03월 01일", 57 도 동아시아 엑셀의 날짜 서식이다. 파일에는 번호만 적힌다.
            date(wb, sheet, 1, LocalDateTime.parse("2026-03-01T00:00:00"), 31, null);
            date(wb, sheet, 2, LocalDateTime.parse("2026-03-02T00:00:00"), 57, null);
            // 그 사이의 번호(37 은 수의 서식)는 날짜가 아니다.
            date(wb, sheet, 3, LocalDateTime.parse("2026-03-01T00:00:00"), 37, null);
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).extracting(row -> row.values().get(1)).containsExactly("2026-03-01", "2026-03-02", "46082");
    }

    @Test
    void 지수가_터무니없이_큰_수와_읽을_수_없는_날짜_글자는_그_칸만_읽지_못한_것으로_둔다() {
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code", "amount", "A-1" },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>1E+999999999</v></c></row>"
                        + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>2</v></c><c r=\"B3\"><v>1E-999999999</v></c></row>"
                        + "<row r=\"4\"><c r=\"A4\" t=\"s\"><v>2</v></c><c r=\"B4\" t=\"d\"><v>2026-03-01T09:00:00+09:00</v></c></row>"
                        + "<row r=\"5\"><c r=\"A5\" t=\"s\"><v>2</v></c><c r=\"B5\" t=\"d\"><v>2026-03-01</v></c></row>"
                        + "<row r=\"6\"><c r=\"A6\" t=\"s\"><v>2</v></c><c r=\"B6\"><v>1E+300</v></c></row>");

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).extracting(row -> file.unreadable(row, "amount")).containsExactly(
                new UnreadableCell("CELL_ERROR", null), new UnreadableCell("CELL_ERROR", null),
                new UnreadableCell("CELL_ERROR", "2026-03-01T09:00:00+09:00"), null, null);
        assertThat(file.rows().get(3).values().get(1)).isEqualTo("2026-03-01");
        assertThat(file.rows().get(4).values().get(1)).hasSize(301).startsWith("1000");
    }

    @Test
    void 날짜_서식의_경계_값을_읽는다() {
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code", "since", "K" },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + dated(2, "59") + dated(3, "61") + dated(4, "-1") + dated(5, "0") + dated(6, "0.99999999")
                        + dated(7, "2958465") + dated(8, "2958466") + dated(9, "1E10"));

        TableFile file = XlsxTableReader.read(content, "s");

        // 59 는 1900-02-28, 61 은 1900-03-01 이다 (엑셀은 없는 날인 1900-02-29 를 60 으로 센다).
        // 0 보다 작은 값과 9999-12-31 을 넘는 값은 엑셀에서도 날짜가 아니다. 수로 읽는다 (날짜 속성이면 값이 틀린 행이 된다).
        // 0 은 날짜 없이 시각만 든 것이다. 반올림해 다음 날이 되는 값은 그 날짜다.
        assertThat(file.rows()).extracting(row -> row.values().get(1))
            .containsExactly("1900-02-28", "1900-03-01", "-1", "00:00:00", "1900-01-01", "9999-12-31", "2958466",
                    "10000000000");
        assertThat(file.rows()).allSatisfy(row -> assertThat(file.unreadable(row, "since")).isNull());
    }

    private static String dated(int rowNo, String value) {
        return "<row r=\"" + rowNo + "\"><c r=\"A" + rowNo + "\" t=\"s\"><v>2</v></c><c r=\"B" + rowNo
                + "\" s=\"1\"><v>" + value + "</v></c></row>";
    }

    @Test
    void 정수는_자릿수가_많아도_그대로_읽는다() {
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code" },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\"><v>1234567890123456</v></c></row>"
                        + "<row r=\"3\"><c r=\"A3\"><v>1234567890123457</v></c></row>"
                        + "<row r=\"4\"><c r=\"A4\"><v>1001.0</v></c></row>"
                        + "<row r=\"5\"><c r=\"A5\"><v>1.2345678901234567</v></c></row>");

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).extracting(row -> row.values().get(0))
            .containsExactly("1234567890123456", "1234567890123457", "1001", "1.23456789012346");
    }

    @Test
    void 수식의_결과가_빈_글자이면_빈_칸이고_참_거짓의_자리에_다른_것이_있으면_읽지_못한_칸이다() {
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code", "name", "K" },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\" t=\"str\"><f>\"\"</f><v></v></c></row>"
                        + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>2</v></c><c r=\"B3\" t=\"str\"><f>A1</f></c></row>"
                        + "<row r=\"4\"><c r=\"A4\" t=\"s\"><v>2</v></c><c r=\"B4\" t=\"b\"><v>7</v></c></row>"
                        + "<row r=\"5\"><c r=\"A5\" t=\"s\"><v>2</v></c><c r=\"B5\" t=\"b\"><v>0</v></c></row>");

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("K", "")),
                new TableRow(3, List.of("K", ""), Set.of(), Map.of(1, new UnreadableCell("FORMULA_WITHOUT_RESULT", null))),
                new TableRow(4, List.of("K", ""), Set.of(), Map.of(1, new UnreadableCell("CELL_ERROR", "7"))),
                new TableRow(5, List.of("K", "false"), Set.of(1)));
    }

    // ── 참·거짓 ──────────────────────────────────────────────────

    @Test
    void 참_거짓이_든_칸은_true_와_false_로_읽는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "flag");
            sheet.createRow(1).createCell(1).setCellValue(true);
            sheet.createRow(2).createCell(1).setCellValue(false);
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("", "true"), Set.of(1)),
                new TableRow(3, List.of("", "false"), Set.of(1)));
    }

    // ── 수식 ────────────────────────────────────────────────────

    @Test
    void 수식이_든_칸은_저장된_계산_결과를_읽는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "name", "amount", "since", "flag");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("A-1");
            row.createCell(1).setCellFormula("A2&\"-이름\"");
            row.createCell(2).setCellFormula("1.5*2");
            Cell since = row.createCell(3);
            since.setCellFormula("DATE(2026,3,1)");
            since.setCellStyle(style(wb, 14, null));
            row.createCell(4).setCellFormula("1=1");
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows())
            .containsExactly(new TableRow(2, List.of("A-1", "A-1-이름", "3", "2026-03-01", "true"), Set.of(2, 3, 4)));
        assertThat(file.skipped()).isEmpty();
    }

    @Test
    void 계산_결과가_저장되지_않은_수식과_오류_값인_칸은_읽지_못한_칸으로_표시하고_행은_넘긴다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "amount", "name");
            strings(sheet, 1, "A-1", "1");
            Row row = sheet.createRow(2);
            row.createCell(0).setCellValue("A-2");
            // 계산하지 않고 저장한다. 결과가 파일에 없다.
            row.createCell(1).setCellFormula("1+1");
            row.createCell(2).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.NA.getCode());
            Row third = sheet.createRow(3);
            third.createCell(0).setCellValue("A-3");
            third.createCell(1).setCellFormula("1/0");
            wb.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(third.getCell(1));
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1", "1", "")),
                new TableRow(3, List.of("A-2", "", ""), Set.of(),
                        Map.of(1, new UnreadableCell("FORMULA_WITHOUT_RESULT", null), 2,
                                new UnreadableCell("CELL_ERROR", "#N/A"))),
                new TableRow(4, List.of("A-3", "", ""), Set.of(), Map.of(1, new UnreadableCell("CELL_ERROR", "#DIV/0!"))));
        assertThat(file.skipped()).isEmpty();
        assertThat(file.unreadable(file.rows().get(1), "amount")).isEqualTo(new UnreadableCell("FORMULA_WITHOUT_RESULT", null));
        assertThat(file.unreadable(file.rows().get(1), "code")).isNull();
    }

    @Test
    void 읽지_못한_칸만_든_행도_행으로_넘긴다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "note");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("", ""), Set.of(),
                Map.of(0, new UnreadableCell("FORMULA_WITHOUT_RESULT", null))));
    }

    // ── 합쳐진 칸, 숨긴 것, 시트 ──────────────────────────────────

    @Test
    void 합쳐진_칸은_왼쪽_위_칸의_값을_채워_읽는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "name", "amount");
            strings(sheet, 1, "A-1", "묶음");
            strings(sheet, 2, "A-2", "");
            strings(sheet, 3, "A-3", "");
            sheet.getRow(1).createCell(2).setCellValue(7);
            sheet.addMergedRegion(new CellRangeAddress(1, 3, 1, 2));
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1", "묶음", "묶음")),
                new TableRow(3, List.of("A-2", "묶음", "묶음")), new TableRow(4, List.of("A-3", "묶음", "묶음")));
    }

    @Test
    void 열_이름의_행이_합쳐진_칸이면_같은_이름이_겹쳐_거부한다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "name");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 1, 2));
            strings(sheet, 1, "A-1", "x", "y");
        });

        TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                () -> XlsxTableReader.read(content, "s"));

        assertThat(e.code()).isEqualTo("DUPLICATE_COLUMN_NAME");
    }

    @Test
    void 값이_든_칸이_한도보다_많으면_다_읽기_전에_거부한다() {
        // 한 행에 칸 16,000 개씩 든 행을 한도를 넘길 만큼 적는다.
        StringBuilder row = new StringBuilder("<row>");
        for (int c = 0; c < 16_000; c++) {
            row.append("<c><v>1</v></c>");
        }
        row.append("</row>");
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code" },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>", row.toString(),
                XlsxTableReader.MAX_CELLS / 16_000 + 1, 0);

        TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                () -> XlsxTableReader.read(content, "s"));

        assertThat(e.code()).isEqualTo("XLSX_TOO_LARGE");
    }

    @Test
    void 숨긴_행과_열도_읽는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "name");
            strings(sheet, 1, "A-1", "보임");
            strings(sheet, 2, "A-2", "숨김");
            sheet.getRow(2).setZeroHeight(true);
            sheet.setColumnHidden(1, true);
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1", "보임")),
                new TableRow(3, List.of("A-2", "숨김")));
    }

    @Test
    void 매핑이_지정한_시트_하나만_읽는다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            strings(wb.createSheet("표지"), 0, "이 시트는 읽지 않는다");
            Sheet sheet = wb.createSheet("자료");
            strings(sheet, 0, "code");
            strings(sheet, 1, "A-1");
            strings(wb.createSheet("뒤"), 0, "code");
        });

        TableFile file = XlsxTableReader.read(content, "자료");

        assertThat(file.columns()).containsExactly("code");
        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1")));
    }

    // ── 파일 전체를 거부하는 것 ────────────────────────────────────

    @Test
    void 지정한_시트가_없으면_파일_전체를_거부하고_시트_이름을_알린다() {
        byte[] content = XlsxFiles.workbook(wb -> strings(wb.createSheet("다른 것"), 0, "code"));

        TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                () -> XlsxTableReader.read(content, "자료"));

        assertThat(e.code()).isEqualTo("SHEET_NOT_FOUND");
        assertThat(e.detail()).isEqualTo("자료");
        assertThat(e).hasMessageContaining("자료").hasMessageContaining("다른 것");
    }

    @Test
    void 엑셀_파일이_아니면_파일_전체를_거부한다() {
        for (byte[] content : List.of(new byte[0], "code,name\nA-1,x\n".getBytes(StandardCharsets.UTF_8),
                new byte[] { 'P', 'K', 3, 4, 0, 0, 0, 0 },
                // 옛 형식(xls)과 암호가 걸린 파일의 머리.
                new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0,
                        0, 0 })) {
            TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                    () -> XlsxTableReader.read(content, "s"));

            assertThat(e).isNotNull();
            assertThat(e.code()).isEqualTo("NOT_XLSX");
        }
    }

    @Test
    void 빈_시트는_열_이름의_행이_없어_거부한다() {
        byte[] content = XlsxFiles.workbook(wb -> wb.createSheet("s").createRow(3).createCell(2).setCellValue("  "));

        TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                () -> XlsxTableReader.read(content, "s"));

        assertThat(e.code()).isEqualTo("NO_HEADER");
    }

    @Test
    void 열_이름이_가운데에서_비었거나_겹치면_거부한다() {
        byte[] blank = XlsxFiles.workbook(wb -> strings(wb.createSheet("s"), 0, "code", "", "name"));
        byte[] leading = XlsxFiles.workbook(wb -> strings(wb.createSheet("s"), 0, "", "code"));
        byte[] duplicate = XlsxFiles.workbook(wb -> strings(wb.createSheet("s"), 0, "code", "name", "code"));

        assertThat(catchThrowableOfType(TableFileRejectedException.class, () -> XlsxTableReader.read(blank, "s"))
            .code()).isEqualTo("BLANK_COLUMN_NAME");
        assertThat(catchThrowableOfType(TableFileRejectedException.class, () -> XlsxTableReader.read(leading, "s"))
            .code()).isEqualTo("BLANK_COLUMN_NAME");
        assertThat(
                catchThrowableOfType(TableFileRejectedException.class, () -> XlsxTableReader.read(duplicate, "s"))
                    .code())
            .isEqualTo("DUPLICATE_COLUMN_NAME");
    }

    @Test
    void 열_이름의_행에_수나_날짜가_들어_있어도_그_표기가_이름이_된다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("code");
            row.createCell(1).setCellValue(2026);
            strings(sheet, 1, "A-1", "x");
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.columns()).containsExactly("code", "2026");
    }

    // ── 한도 ────────────────────────────────────────────────────

    @Test
    void 합쳐진_칸에_가려진_값은_읽지_않는다_왼쪽_위가_비었으면_묶음_전체가_빈_칸이다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "name", "amount");
            strings(sheet, 1, "A-1", "", "가려진 값");
            strings(sheet, 2, "A-2", "보이는 값", "가려진 값");
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 1, 2));
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 1, 2));
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1", "", "")),
                new TableRow(3, List.of("A-2", "보이는 값", "보이는 값")));
    }

    @Test
    void 시트_전체에_걸쳐_합친_칸은_한도를_넘어_파일_전체를_거부한다() {
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code", "x" },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row><row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c></row>"
                        + "</sheetData><mergeCells count=\"1\"><mergeCell ref=\"A2:XFD1048576\"/></mergeCells><sheetData>");

        TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                () -> XlsxTableReader.read(content, "s"));

        assertThat(e.code()).isEqualTo("XLSX_TOO_LARGE");
    }

    @Test
    void 칸이_드문드문_든_넓은_표가_칸의_한도를_넘으면_파일_전체를_거부한다() {
        int width = 4_000;
        int count = (int) (XlsxTableReader.MAX_CELLS / width) + 1;
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            Row head = sheet.createRow(0);
            for (int c = 0; c < width; c++) {
                head.createCell(c).setCellValue("c" + c);
            }
            for (int i = 0; i < count; i++) {
                sheet.createRow(i + 1).createCell(0).setCellValue("K-" + i);
            }
        });

        TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                () -> XlsxTableReader.read(content, "s"));

        assertThat(e.code()).isEqualTo("XLSX_TOO_LARGE");
    }

    @Test
    void 작은_파일이_한도보다_큰_내용으로_풀리면_파일_전체를_거부한다() {
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code" },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>", XlsxTableReader.MAX_UNCOMPRESSED_BYTES + 1);

        assertThat(content.length).isLessThan(2 * 1024 * 1024);
        TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                () -> XlsxTableReader.read(content, "s"));

        assertThat(e.code()).isEqualTo("XLSX_TOO_LARGE");
    }

    @Test
    void 행과_칸의_자리가_적혀_있지_않은_파일은_적힌_차례로_읽는다() {
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code", "name", "A-1" },
                "<row><c t=\"s\"><v>0</v></c><c t=\"s\"><v>1</v></c></row>"
                        + "<row><c t=\"s\"><v>2</v></c><c><v>5</v></c></row>");

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.columns()).containsExactly("code", "name");
        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1", "5"), Set.of(1)));
    }

    @Test
    void 안이_틀린_파일은_엑셀이_아닌_것으로_거부한다() {
        // 공유 문자열에 없는 자리를 가리키는 칸, 시트의 범위 밖의 행.
        byte[] badString = XlsxFiles.handWritten("s", new String[] { "code" },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>7</v></c></row>");
        byte[] badRow = XlsxFiles.handWritten("s", new String[] { "code" },
                "<row r=\"9999999\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>");

        for (byte[] content : List.of(badString, badRow)) {
            TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                    () -> XlsxTableReader.read(content, "s"));

            assertThat(e.code()).isEqualTo("NOT_XLSX");
            assertThat(e.getCause()).isNotNull();
        }
    }

    @Test
    void 시트가_아닌_부분이_그_한도를_넘으면_파일_전체를_거부한다() {
        // 서식의 부분이 17MB 로 풀린다. 전체의 한도(512MB)보다는 훨씬 작다.
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code" },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>", "", 0, 0, 17L * 1024 * 1024);

        TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                () -> XlsxTableReader.read(content, "s"));

        assertThat(e.code()).isEqualTo("XLSX_TOO_LARGE");
    }

    @Test
    void 엑셀의_칸에_들_수_없는_긴_글자와_긴_숫자는_그_칸만_읽지_못한_것으로_둔다() {
        String longText = "가".repeat(40_000);
        String longDigits = "7".repeat(400_000);
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code", "name", "K", longText },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\" t=\"inlineStr\"><is><t>" + longText
                        + "</t></is></c></row>"
                        + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>2</v></c><c r=\"B3\" t=\"s\"><v>3</v></c></row>"
                        + "<row r=\"4\"><c r=\"A4\" t=\"s\"><v>2</v></c><c r=\"B4\"><v>" + longDigits + "</v></c></row>"
                        + "<row r=\"5\"><c r=\"A5\" t=\"s\"><v>2</v></c><c r=\"B5\" t=\"inlineStr\"><is><t>"
                        + "나".repeat(32_767) + "</t></is></c></row>");

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).extracting(row -> file.unreadable(row, "name")).containsExactly(
                new UnreadableCell("CELL_ERROR", null), new UnreadableCell("CELL_ERROR", null),
                new UnreadableCell("CELL_ERROR", null), null);
        // 엑셀의 칸에 드는 가장 긴 글자는 읽는다.
        assertThat(file.rows().get(3).values().get(1)).hasSize(32_767);
    }

    @Test
    void 시트의_자료_밖에_있는_같은_이름의_원소는_행이나_칸으로_읽지_않는다() {
        // 양식 컨트롤(단추 등)을 넣은 시트는 자료의 뒤에 그 자리를 row 와 col 이라는 이름으로 적는다.
        // 시트의 마지막 행에 서식이 걸려 있으면 그 뒤의 row 가 범위 밖이 되어 파일 전체가 거부됐다.
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code", "A-1" },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row><row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"1048576\" s=\"1\" customFormat=\"1\"/></sheetData>"
                        + "<controls><control><controlPr><anchor><from xmlns:xdr=\"urn:x\"><xdr:col>1</xdr:col>"
                        + "<xdr:row>2</xdr:row></from></anchor></controlPr></control></controls>"
                        + "<extLst><ext><x xmlns:xm=\"urn:y\"><xm:f>A1</xm:f><c><v>9</v></c></x></ext></extLst><sheetData>");

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.columns()).containsExactly("code");
        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("A-1")));
    }

    @Test
    void 칸에_바로_적힌_글자도_공유_문자열과_같게_푼다() {
        byte[] content = XlsxFiles.handWritten("s", new String[] { "code", "name", "첫 줄_x000D_\n둘째 줄" },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c>"
                        + "<c r=\"B2\" t=\"inlineStr\"><is><t>첫 줄_x000D_\n둘째 줄</t></is></c></row>"
                        + "<row r=\"3\"><c r=\"A3\" t=\"str\"><f>X</f><v>첫 줄_x000D_\n둘째 줄</v></c>"
                        + "<c r=\"B3\" t=\"s\"><f>X</f></c></row>");

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).containsExactly(new TableRow(2, List.of("첫 줄\n둘째 줄", "첫 줄\n둘째 줄")),
                new TableRow(3, List.of("첫 줄\n둘째 줄", ""), Set.of(),
                        Map.of(1, new UnreadableCell("FORMULA_WITHOUT_RESULT", null))));
    }

    // ── 엑셀이 저장하는 모양 ───────────────────────────────────────

    @Test
    void 손으로_적은_파일도_같게_읽는다_공유_문자열과_칸에_바로_적은_글자와_저장된_계산_결과() {
        byte[] content = XlsxFiles.handWritten("자료", new String[] { "code", "since", "A-1", "A-2", " A-3 " },
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c>"
                        + "<c r=\"C1\" t=\"inlineStr\"><is><t>amount</t></is></c>"
                        + "<c r=\"D1\" t=\"inlineStr\"><is><t>name</t></is></c></row>"
                        // 46082 는 2026-03-01 이다.
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\" s=\"1\"><v>46082</v></c>"
                        + "<c r=\"C2\"><f>1+1</f><v>2</v></c><c r=\"D2\" t=\"str\"><f>A2&amp;\"!\"</f><v>A-1!</v></c></row>"
                        // 계산 결과가 없는 수식.
                        + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>3</v></c><c r=\"B3\" s=\"1\"><v>46083.5</v></c>"
                        + "<c r=\"C3\"><f>A1*2</f></c></row>"
                        // 건너뛴 행 번호(4)와 오류.
                        + "<row r=\"5\"><c r=\"A5\" t=\"s\"><v>4</v></c><c r=\"C5\" t=\"e\"><f>1/0</f><v>#DIV/0!</v></c></row>"
                        + "<row r=\"6\"><c r=\"A6\" t=\"s\"><v>4</v></c><c r=\"C6\"><v>0.30000000000000004</v></c>"
                        + "<c r=\"D6\" t=\"b\"><v>1</v></c></row>");

        TableFile file = XlsxTableReader.read(content, "자료");

        assertThat(file.columns()).containsExactly("code", "since", "amount", "name");
        assertThat(file.rows()).containsExactly(
                new TableRow(2, List.of("A-1", "2026-03-01", "2", "A-1!"), Set.of(1, 2)),
                new TableRow(3, List.of("A-2", "2026-03-02T12:00:00", "", ""), Set.of(1),
                        Map.of(2, new UnreadableCell("FORMULA_WITHOUT_RESULT", null))),
                new TableRow(5, List.of("A-3", "", "", ""), Set.of(), Map.of(2, new UnreadableCell("CELL_ERROR", "#DIV/0!"))),
                new TableRow(6, List.of("A-3", "", "0.3", "true"), Set.of(2, 3)));
        assertThat(file.skipped()).isEmpty();
    }

    // ── 큰 입력, 같은 결과 ─────────────────────────────────────────

    @Test
    void 행이_많은_파일도_차례대로_읽는다() {
        int count = 5_000;
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "amount");
            for (int i = 0; i < count; i++) {
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue("K-" + i);
                row.createCell(1).setCellValue(i);
            }
        });

        TableFile file = XlsxTableReader.read(content, "s");

        assertThat(file.rows()).hasSize(count);
        assertThat(file.rows().get(0)).isEqualTo(new TableRow(2, List.of("K-0", "0"), Set.of(1)));
        assertThat(file.rows().get(count - 1))
            .isEqualTo(new TableRow(count + 1, List.of("K-" + (count - 1), String.valueOf(count - 1)), Set.of(1)));
    }

    @Test
    void 같은_파일을_두_번_읽은_결과가_같다() {
        byte[] content = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            strings(sheet, 0, "code", "amount", "since");
            for (int i = 0; i < 50; i++) {
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue("K-" + i);
                row.createCell(1).setCellValue(i / 7.0);
                Cell since = row.createCell(2);
                since.setCellValue(46000 + i);
                since.setCellStyle(style(wb, 14, null));
            }
        });

        assertThat(XlsxTableReader.read(content, "s")).isEqualTo(XlsxTableReader.read(content, "s"));
    }

    // ── 도우미 ───────────────────────────────────────────────────

    private static void strings(Sheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            if (!values[i].isEmpty()) {
                row.createCell(i).setCellValue(values[i]);
            }
        }
    }

    /** 둘째 열에 수를 그 서식으로 적는다. */
    private static void number(XSSFWorkbook wb, Sheet sheet, int rowIndex, double value, String format) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue("K-" + rowIndex);
        Cell cell = row.createCell(1);
        cell.setCellValue(value);
        if (format != null) {
            cell.setCellStyle(style(wb, -1, format));
        }
    }

    /** 둘째 열에 날짜를 그 서식으로 적는다. */
    private static void date(XSSFWorkbook wb, Sheet sheet, int rowIndex, LocalDateTime value, int builtin,
            String format) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue("K-" + rowIndex);
        Cell cell = row.createCell(1);
        cell.setCellValue(value);
        cell.setCellStyle(style(wb, builtin, format));
    }

    /** 엑셀에 들어 있는 서식의 번호, 또는 적어 준 서식으로 칸의 서식을 만든다. */
    private static CellStyle style(XSSFWorkbook wb, int builtin, String format) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(format == null ? (short) builtin : wb.createDataFormat().getFormat(format));
        return style;
    }
}
