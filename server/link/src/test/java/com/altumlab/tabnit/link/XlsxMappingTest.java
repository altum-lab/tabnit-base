package com.altumlab.tabnit.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.altumlab.tabnit.core.AttributeValue;
import com.altumlab.tabnit.core.AttributeValueQuery;
import com.altumlab.tabnit.core.RelationQuery;
import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.MappedAttribute;
import com.altumlab.tabnit.pack.MappedEntity;
import com.altumlab.tabnit.pack.MappedEntityRef;
import com.altumlab.tabnit.pack.MappedRelation;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.MappingFileFormat;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackImpactDirection;
import com.altumlab.tabnit.pack.RelationTypeDefinition;
import com.altumlab.tabnit.pack.ValueFormat;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * C-01 의 엑셀을 매핑 실행까지 이은 시험: 엑셀로 받는 입력 자료가 CSV 와 같은 것으로 적재된다 (D6 7절).
 *
 * <p>지어낸 이름의 매핑과 자료만 쓴다. 시험마다 다른 Pack 식별자를 쓴다.
 */
class XlsxMappingTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final LocalDate EFFECTIVE = LocalDate.parse("2026-03-01");

    private static final MappedEntityRef BASE = new MappedEntityRef("Base", "code");

    private final MappingExecutor executor = new MappingExecutor(LinkTestDatabase.dataSource());

    private final RelationQuery relations = new RelationQuery(LinkTestDatabase.dataSource());

    private final AttributeValueQuery values = new AttributeValueQuery(LinkTestDatabase.dataSource());

    @Test
    void 엑셀로_받은_것과_같은_내용의_CSV_로_받은_것이_같게_적재된다() {
        PackDefinition xlsxPack = pack("xl-same-x", mapping(MappingFileFormat.XLSX, "자료", null, null, null, null));
        PackDefinition csvPack = pack("xl-same-c", mapping(MappingFileFormat.CSV, null, null, null, null, null));
        byte[] xlsx = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("자료");
            header(sheet);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("B-1");
            row.createCell(1).setCellValue("첫째");
            row.createCell(2).setCellValue(12);
            cell(wb, row, 3, 0.725, "0.0");
            date(wb, row, 4, "2026-02-01T00:00:00");
            row.createCell(5).setCellValue(true);
            row.createCell(6).setCellValue("O-1");
            date(wb, row, 7, "2026-04-01T00:00:00");
        });
        String csv = "code,title,count,ratio,since,active,other,from\nB-1,첫째,12,0.725,2026-02-01,true,O-1,2026-04-01\n";

        MappingOutcome fromXlsx = executor.execute(xlsxPack, "src", xlsx, 1, EFFECTIVE, RECORDED_AT);
        MappingOutcome fromCsv = executor.execute(csvPack, "src", csv.getBytes(StandardCharsets.UTF_8), 1, EFFECTIVE,
                RECORDED_AT);

        assertThat(fromXlsx.notices()).isEmpty();
        assertThat(fromCsv.notices()).isEmpty();
        LocalDate asOf = LocalDate.parse("2026-05-01");
        assertThat(valuesOf("xl-same-x|Base|B-1", asOf)).isEqualTo(valuesOf("xl-same-c|Base|B-1", asOf))
            .containsExactly(Map.entry("active", "true"), Map.entry("count", "12"), Map.entry("ratio", "0.725"),
                    Map.entry("since", "2026-02-01"), Map.entry("title", "첫째"));
        // 관계의 시작일은 from 열의 날짜다.
        assertThat(relations.relationsAsOf("xl-same-x|Base|B-1", LocalDate.parse("2026-03-31"))).isEmpty();
        assertThat(relations.relationsAsOf("xl-same-x|Base|B-1", LocalDate.parse("2026-04-01"))).singleElement()
            .satisfies(r -> assertThat(r.targetEntityId()).isEqualTo("xl-same-x|Other|O-1"));
        assertThat(relations.relationsAsOf("xl-same-c|Base|B-1", LocalDate.parse("2026-04-01"))).hasSize(1);
    }

    @Test
    void 엑셀이_형식을_아는_칸은_매핑에_적힌_모양과_무관하게_읽고_글자로_적힌_칸은_매핑의_모양을_따른다() {
        // 매핑은 글자로 적힌 값의 모양을 적었다: 날짜는 01.03.2026, 수의 자릿수 구분은 마침표, 참·거짓은 예와 아니오.
        PackDefinition pack = pack("xl-typed", mapping(MappingFileFormat.XLSX, "s", "dd.MM.yyyy", ".", "예", "아니오"));
        byte[] xlsx = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            header(sheet);
            // 형식을 아는 칸으로 적은 행.
            Row typed = sheet.createRow(1);
            typed.createCell(0).setCellValue("B-1");
            typed.createCell(2).setCellValue(1234);
            typed.createCell(3).setCellValue(1234.5);
            date(wb, typed, 4, "2026-02-01T00:00:00");
            typed.createCell(5).setCellValue(false);
            date(wb, typed, 7, "2026-04-01T00:00:00");
            // 같은 값을 글자로 적은 행.
            Row text = sheet.createRow(2);
            text.createCell(0).setCellValue("B-2");
            text.createCell(2).setCellValue("1.234");
            text.createCell(4).setCellValue("01.02.2026");
            text.createCell(5).setCellValue("아니오");
            text.createCell(7).setCellValue("01.04.2026");
        });

        MappingOutcome outcome = executor.execute(pack, "src", xlsx, 1, EFFECTIVE, RECORDED_AT);

        assertThat(outcome.notices()).isEmpty();
        assertThat(valuesOf("xl-typed|Base|B-1", EFFECTIVE)).containsExactly(Map.entry("active", "false"),
                Map.entry("count", "1234"), Map.entry("ratio", "1234.5"), Map.entry("since", "2026-02-01"));
        assertThat(valuesOf("xl-typed|Base|B-2", EFFECTIVE)).containsExactly(Map.entry("active", "false"),
                Map.entry("count", "1234"), Map.entry("since", "2026-02-01"));
    }

    @Test
    void 수로_적은_날짜와_수로_적은_참_거짓은_매핑에_적힌_모양으로_읽는다() {
        PackDefinition pack = pack("xl-numeric", mapping(MappingFileFormat.XLSX, "s", "yyyyMMdd", null, "1", "0"));
        byte[] xlsx = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            header(sheet);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("B-1");
            // 날짜를 수 20260201 로, 참을 수 1 로 적은 자료. 엑셀에서 흔하다.
            row.createCell(4).setCellValue(20260201);
            row.createCell(5).setCellValue(1);
            row.createCell(6).setCellValue("O-1");
            row.createCell(7).setCellValue(20260401);
            // 같은 열에 날짜 칸과 참·거짓 칸으로 적은 행도 읽는다.
            Row typed = sheet.createRow(2);
            typed.createCell(0).setCellValue("B-2");
            date(wb, typed, 4, "2026-02-01T00:00:00");
            typed.createCell(5).setCellValue(false);
        });

        MappingOutcome outcome = executor.execute(pack, "src", xlsx, 1, EFFECTIVE, RECORDED_AT);

        assertThat(outcome.notices()).isEmpty();
        assertThat(valuesOf("xl-numeric|Base|B-1", EFFECTIVE)).containsExactly(Map.entry("active", "true"),
                Map.entry("since", "2026-02-01"));
        assertThat(valuesOf("xl-numeric|Base|B-2", EFFECTIVE)).containsExactly(Map.entry("active", "false"),
                Map.entry("since", "2026-02-01"));
        assertThat(relations.relationsAsOf("xl-numeric|Base|B-1", LocalDate.parse("2026-03-31"))).isEmpty();
        assertThat(relations.relationsAsOf("xl-numeric|Base|B-1", LocalDate.parse("2026-04-01"))).hasSize(1);
    }

    @Test
    void 시각이_붙은_날짜_칸은_날짜_속성으로_읽지_못해_그_행을_건너뛰고_읽은_표기를_알린다() {
        PackDefinition pack = pack("xl-time", mapping(MappingFileFormat.XLSX, "s", null, null, null, null));
        byte[] xlsx = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            header(sheet);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("B-1");
            date(wb, row, 4, "2026-02-01T09:30:00");
        });

        MappingOutcome outcome = executor.execute(pack, "src", xlsx, 1, EFFECTIVE, RECORDED_AT);

        assertThat(outcome.notices())
            .containsExactly(new IngestNotice("SKIPPED_ROW", 2, "INVALID_VALUE", "since", "2026-02-01T09:30:00"));
    }

    @Test
    void 매핑이_쓰는_열의_읽지_못하는_칸은_건너뛴_행으로_알리고_어느_열인지_적는다() {
        PackDefinition pack = pack("xl-formula", mapping(MappingFileFormat.XLSX, "s", null, null, null, null));
        byte[] xlsx = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            header(sheet);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("B-1");
            row.createCell(2).setCellFormula("1+1");
            Row good = sheet.createRow(2);
            good.createCell(0).setCellValue("B-2");
            good.createCell(2).setCellValue(2);
        });

        MappingOutcome outcome = executor.execute(pack, "src", xlsx, 1, EFFECTIVE, RECORDED_AT);

        assertThat(outcome.notices())
            .containsExactly(new IngestNotice("SKIPPED_ROW", 2, "FORMULA_WITHOUT_RESULT", "count", null));
        assertThat(valuesOf("xl-formula|Base|B-2", EFFECTIVE)).containsExactly(Map.entry("count", "2"));
    }

    @Test
    void 매핑이_쓰지_않는_열의_읽지_못하는_칸은_행을_건너뛰게_하지_않는다() {
        PackDefinition pack = pack("xl-unused", mapping(MappingFileFormat.XLSX, "s", null, null, null, null));
        byte[] xlsx = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            header(sheet);
            sheet.getRow(0).createCell(8).setCellValue("memo");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("B-1");
            row.createCell(2).setCellValue(5);
            row.createCell(8).setCellFormula("1/0");
        });

        MappingOutcome outcome = executor.execute(pack, "src", xlsx, 1, EFFECTIVE, RECORDED_AT);

        assertThat(outcome.notices()).isEmpty();
        assertThat(valuesOf("xl-unused|Base|B-1", EFFECTIVE)).containsExactly(Map.entry("count", "5"));
    }

    @Test
    void 읽지_못하는_칸_때문에_건너뛴_행은_앞_판의_항목을_그대로_이어_둔다_키의_칸이_그러면_삭제가_된다() {
        PackDefinition pack = pack("xl-carry", mapping(MappingFileFormat.XLSX, "s", null, null, null, null));
        EditionIngest ingest = new EditionIngest(LinkTestDatabase.dataSource());
        EditionRecords records = new EditionRecords(LinkTestDatabase.dataSource());
        byte[] first = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            header(sheet);
            for (int i = 1; i <= 3; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue("B-" + i);
                row.createCell(2).setCellValue(i);
            }
        });
        byte[] second = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            header(sheet);
            Row one = sheet.createRow(1);
            one.createCell(0).setCellValue("B-1");
            // 계산 결과가 없는 수식.
            one.createCell(2).setCellFormula("10*2");
            Row two = sheet.createRow(2);
            two.createCell(0).setCellValue("B-2");
            two.createCell(2).setCellValue(2);
            Row three = sheet.createRow(3);
            // 키의 칸이 계산 결과가 없는 수식이다. 어느 항목인지 알 수 없다.
            three.createCell(0).setCellFormula("\"B-\"&3");
            three.createCell(2).setCellValue(3);
        });
        Instant at = Instant.parse("2026-01-01T00:00:00Z");

        ingest.ingest(pack, "src", "첫 판.xlsx", first, LocalDate.parse("2026-03-01"), at);
        IngestResult result = ingest.ingest(pack, "src", "둘째 판.xlsx", second, LocalDate.parse("2026-04-01"),
                at.plusSeconds(1));

        assertThat(result.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(result.mapping().notices()).containsExactly(
                new IngestNotice("SKIPPED_ROW", 2, "FORMULA_WITHOUT_RESULT", "count", null),
                new IngestNotice("SKIPPED_ROW", 4, "FORMULA_WITHOUT_RESULT", "code", null));
        // B-1 은 이어 둔다: 변동이 없고 값이 그대로다. B-3 은 키를 읽지 못해 없는 행이 되어 삭제다.
        assertThat(records.changeEvents("xl-carry|src|2")).extracting(ChangeEvent::itemKey, ChangeEvent::kind)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("B-3", ChangeKind.REMOVED));
        assertThat(valuesOf("xl-carry|Base|B-1", LocalDate.parse("2026-04-01"))).containsExactly(Map.entry("count", "1"));
    }

    @Test
    void 표_위에_제목_행이_있는_엑셀도_적재된다() {
        PackDefinition pack = pack("xl-title", mapping(MappingFileFormat.XLSX, "s", null, null, null, null));
        byte[] xlsx = XlsxFiles.workbook(wb -> {
            Sheet sheet = wb.createSheet("s");
            sheet.createRow(0).createCell(0).setCellValue("지어낸 바탕 목록");
            sheet.createRow(1).createCell(0).setCellValue("단위: 개");
            Row names = sheet.createRow(3);
            String[] columns = { "code", "title", "count", "ratio", "since", "active", "other", "from" };
            for (int i = 0; i < columns.length; i++) {
                names.createCell(i).setCellValue(columns[i]);
            }
            Row row = sheet.createRow(4);
            row.createCell(0).setCellValue("B-1");
            row.createCell(2).setCellValue(7);
        });

        MappingOutcome outcome = executor.execute(pack, "src", xlsx, 1, EFFECTIVE, RECORDED_AT);

        assertThat(outcome.notices()).isEmpty();
        assertThat(outcome.items()).singleElement().satisfies(item -> assertThat(item.rowNo()).isEqualTo(5));
        assertThat(valuesOf("xl-title|Base|B-1", EFFECTIVE)).containsExactly(Map.entry("count", "7"));
    }

    @Test
    void 반드시_있어야_하는_열이_없는_엑셀은_CSV_와_같이_거부한다() {
        PackDefinition pack = pack("xl-missing", mapping(MappingFileFormat.XLSX, "s", null, null, null, null));
        byte[] xlsx = XlsxFiles.workbook(wb -> wb.createSheet("s").createRow(0).createCell(0).setCellValue("code"));

        TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                () -> executor.execute(pack, "src", xlsx, 1, EFFECTIVE, RECORDED_AT));

        assertThat(e.code()).isEqualTo("MISSING_COLUMN");
        assertThat(e.detail()).isEqualTo("active, count, from, other, ratio, since, title");
    }

    @Test
    void 매핑이_정한_종류와_다른_파일이_오면_거부한다() {
        PackDefinition xlsxPack = pack("xl-kind-x", mapping(MappingFileFormat.XLSX, "s", null, null, null, null));
        PackDefinition csvPack = pack("xl-kind-c", mapping(MappingFileFormat.CSV, null, null, null, null, null));
        byte[] xlsx = XlsxFiles.workbook(wb -> header(wb.createSheet("s")));
        byte[] csv = "code,title,count,ratio,since,active,other,from\n".getBytes(StandardCharsets.UTF_8);

        assertThat(catchThrowableOfType(TableFileRejectedException.class,
                () -> executor.execute(xlsxPack, "src", csv, 1, EFFECTIVE, RECORDED_AT))
            .code()).isEqualTo("NOT_XLSX");
        assertThat(catchThrowableOfType(TableFileRejectedException.class,
                () -> executor.execute(csvPack, "src", xlsx, 1, EFFECTIVE, RECORDED_AT))
            .code()).isEqualTo("NOT_CSV");
    }

    // ── 도우미 ───────────────────────────────────────────────────

    private Map<String, String> valuesOf(String entityId, LocalDate asOf) {
        Map<String, String> found = new LinkedHashMap<>();
        for (AttributeValue v : values.valuesAsOf(entityId, asOf)) {
            found.put(v.attributeName(), v.value());
        }
        return found;
    }

    private static void header(Sheet sheet) {
        Row row = sheet.createRow(0);
        String[] names = { "code", "title", "count", "ratio", "since", "active", "other", "from" };
        for (int i = 0; i < names.length; i++) {
            row.createCell(i).setCellValue(names[i]);
        }
    }

    private static void cell(XSSFWorkbook wb, Row row, int column, double value, String format) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat(format));
        cell.setCellStyle(style);
    }

    /** 엑셀의 기본 날짜 서식(14)으로 날짜를 적는다. */
    private static void date(XSSFWorkbook wb, Row row, int column, String value) {
        Cell cell = row.createCell(column);
        cell.setCellValue(LocalDateTime.parse(value));
        CellStyle style = wb.createCellStyle();
        style.setDataFormat((short) 14);
        cell.setCellStyle(style);
    }

    /** 열: code, title, count, ratio, since, active, other, from. from 은 관계의 시작일이다. */
    private static MappingDefinition mapping(MappingFileFormat format, String sheet, String datePattern,
            String groupingSeparator, String trueValue, String falseValue) {
        return new MappingDefinition("src", format, sheet, datePattern, List.of(), List.of("code"), BASE,
                List.of(new MappedEntity("Base", "code", List.of(
                        new MappedAttribute("title", "title", null, null, null, null, null, null),
                        new MappedAttribute("count", "count", null, null, null, groupingSeparator, null, null),
                        new MappedAttribute("ratio", "ratio", null, null, null, groupingSeparator, null, null),
                        new MappedAttribute("since", "since", null, null, null, null, null, null),
                        new MappedAttribute("active", "active", null, null, null, null, trueValue, falseValue)))),
                List.of(new MappedRelation("links", BASE, new MappedEntityRef("Other", "other"), "from", null)));
    }

    private static PackDefinition pack(String packId, MappingDefinition mapping) {
        return new PackDefinition(packId, "이름표", 1, RECORDED_AT,
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null)),
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET)),
                List.of(new AttributeDefinition("active", "쓰는가", "Base", ValueFormat.BOOLEAN),
                        new AttributeDefinition("count", "수", "Base", ValueFormat.INTEGER),
                        new AttributeDefinition("ratio", "비율", "Base", ValueFormat.DECIMAL),
                        new AttributeDefinition("since", "시작", "Base", ValueFormat.DATE),
                        new AttributeDefinition("title", "제목", "Base", ValueFormat.STRING)),
                List.of(), List.of(mapping));
    }
}
