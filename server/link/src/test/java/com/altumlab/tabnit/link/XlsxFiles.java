package com.altumlab.tabnit.link;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 시험에 쓰는 엑셀 파일을 만든다. 저장소에 엑셀 파일을 두지 않고 시험마다 지어낸 값으로 만든다.
 *
 * <p>두 가지로 만든다. 하나는 라이브러리로 만든 것이고, 하나는 파일의 XML 을 손으로 적은 것이다. 손으로 적은 것은
 * 엑셀이 저장하는 모양(공유 문자열, 계산 결과가 저장된 수식)을 라이브러리의 쓰기와 무관하게 확인하려고 둔다.
 */
final class XlsxFiles {

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    private XlsxFiles() {
    }

    /** 라이브러리로 만든다. 받은 통합 문서에 시트와 칸을 채우면 그 바이트를 돌려준다. */
    static byte[] workbook(Consumer<XSSFWorkbook> fill) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill.accept(workbook);
            workbook.write(out);
            return out.toByteArray();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 손으로 적은 XML 로 만든다. 시트는 하나다.
     *
     * @param sheetName 시트의 이름
     * @param sharedStrings 공유 문자열. 칸은 이 목록의 자리로 가리킨다 (t="s")
     * @param sheetData sheetData 안에 들어갈 row 들의 XML. 서식 1번은 엑셀의 기본 날짜 서식이다 (s="1")
     */
    static byte[] handWritten(String sheetName, String[] sharedStrings, String sheetData) {
        return handWritten(sheetName, sharedStrings, sheetData, 0);
    }

    /**
     * @param padding 시트의 XML 끝에 덧붙일 빈칸의 바이트 수. 작은 파일이 매우 큰 내용으로 풀리는 경우를 만든다
     */
    static byte[] handWritten(String sheetName, String[] sharedStrings, String sheetData, long padding) {
        return handWritten(sheetName, sharedStrings, sheetData, "", 0, padding);
    }

    /**
     * @param repeatedRow sheetData 의 뒤에 되풀이해 적을 row 의 XML. 매우 큰 시트를 메모리에 다 만들지 않고 적는다
     * @param times 되풀이하는 수
     */
    static byte[] handWritten(String sheetName, String[] sharedStrings, String sheetData, String repeatedRow, long times,
            long padding) {
        return handWritten(sheetName, sharedStrings, sheetData, repeatedRow, times, padding, 0);
    }

    /**
     * @param stylesPadding 서식의 XML 끝에 덧붙일 빈칸의 바이트 수. 시트가 아닌 부분이 매우 큰 경우를 만든다
     */
    static byte[] handWritten(String sheetName, String[] sharedStrings, String sheetData, String repeatedRow, long times,
            long padding, long stylesPadding) {
        StringBuilder strings = new StringBuilder("<sst xmlns=\"" + MAIN + "\">");
        for (String text : sharedStrings) {
            strings.append("<si><t xml:space=\"preserve\">").append(text).append("</t></si>");
        }
        strings.append("</sst>");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml",
                        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                                + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>"
                                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                                + "</Types>");
                entry(zip, "_rels/.rels",
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                                + "</Relationships>");
                entry(zip, "xl/workbook.xml", "<workbook xmlns=\"" + MAIN
                        + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                        + "<sheets><sheet name=\"" + sheetName + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
                entry(zip, "xl/_rels/workbook.xml.rels",
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                                + "</Relationships>");
                entry(zip, "xl/styles.xml", "<styleSheet xmlns=\"" + MAIN + "\">"
                        + "<fonts count=\"1\"><font/></fonts><fills count=\"1\"><fill/></fills>"
                        + "<borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs>"
                        + "<cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"14\" applyNumberFormat=\"1\"/></cellXfs>"
                        + " ".repeat((int) stylesPadding) + "</styleSheet>");
                entry(zip, "xl/sharedStrings.xml", strings.toString());
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
                zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"" + MAIN
                        + "\"><sheetData>" + sheetData).getBytes(StandardCharsets.UTF_8));
                byte[] repeated = repeatedRow.getBytes(StandardCharsets.UTF_8);
                for (long i = 0; i < times; i++) {
                    zip.write(repeated);
                }
                zip.write("</sheetData>".getBytes(StandardCharsets.UTF_8));
                byte[] blanks = new byte[64 * 1024];
                java.util.Arrays.fill(blanks, (byte) ' ');
                for (long left = padding; left > 0; left -= blanks.length) {
                    zip.write(blanks, 0, (int) Math.min(blanks.length, left));
                }
                zip.write("</worksheet>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return out.toByteArray();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void entry(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + xml).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
