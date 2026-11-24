package com.altumlab.tabnit.link;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipEntrySource;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 엑셀(xlsx) 파일을 읽는다 (D6 7절 "엑셀 파일", D2 AD-93, AD-373 ~ AD-376).
 *
 * <ul>
 * <li>매핑이 지정한 시트 하나만 읽는다. 숨긴 행과 열도 읽는다.
 * <li>아무것도 없는 행을 지나 처음 나오는 행이 열 이름이다. 행 번호는 시트의 행 번호다.
 * <li>글자가 든 칸은 적힌 그대로 읽는다 (앞뒤 빈칸은 뗀다).
 * <li>수, 날짜, 참·거짓이 든 칸은 칸의 서식과 무관하게 저장된 값을 표준 표기로 읽는다 (D2 AD-374).
 * <li>수식이 든 칸은 파일에 저장된 계산 결과를 읽는다. 다시 계산하지 않는다.
 * <li>합쳐진 칸은 왼쪽 위 칸의 값을 채워 읽는다.
 * <li>계산 결과가 없는 수식 칸과 오류 값인 칸은 값을 읽지 못한 칸으로 표시해 넘긴다. 매핑이 그 칸을 읽을 때 그 행을
 *     건너뛴다 (D2 AD-375).
 * </ul>
 *
 * <p>파일을 통째로 펼쳐 놓지 않고 시트를 앞에서부터 흘려 읽는다. 통째로 펼치면 행이 십만쯤인 파일에서 메모리가 모자랐다
 * (D9 의 C-01 에 잰 값이 있다). 칸의 서식을 글자로 바꾸지 않으므로 서버의 지역 설정에 결과가 달라지지 않는다.
 * 특정 파일의 열 이름을 알지 못한다.
 */
final class XlsxTableReader {

    /**
     * 압축을 푼 크기의 한도. 작은 파일이 매우 큰 내용으로 풀리는 것을 막는다. 넘으면 파일 전체를 거부한다.
     * 올릴 수 있는 파일의 한도(20MB)까지의 보통 파일은 이 안에 든다.
     */
    static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;

    /**
     * 칸의 수의 한도. 셋에 쓴다: 읽는 동안 모은 값이 든 칸, 합쳐진 칸이 채우는 칸, 읽어 내는 표의 칸(행의 수 곱하기 열의 수).
     * 값이 든 칸이 매우 많은 시트, 칸이 드문드문 든 넓은 표, 넓게 합친 칸이 메모리를 다 쓰는 것을 막는다.
     * 512MB 로 재서 정했다: 400만 칸(40만 행, 10열)은 읽었고 1,000만 칸은 읽다가 메모리가 모자랐다 (D9 의 C-01).
     * 올릴 수 있는 파일의 한도(20MB)에 드는 보통 표는 200만 칸쯤이다.
     */
    static final long MAX_CELLS = 2_000_000L;

    /** 엑셀이 수를 다루는 정밀도. 유효 숫자 열다섯 자리다. 그 뒤의 자리는 계산의 오차다 (0.1 + 0.2). */
    private static final System.Logger LOG = System.getLogger(XlsxTableReader.class.getName());

    private static final MathContext PRECISION = new MathContext(15, RoundingMode.HALF_UP);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT);

    /**
     * 수의 자릿수의 한도. 엑셀이 담는 수는 10 의 308 제곱까지다. 그보다 훨씬 큰 지수가 적힌 칸(1E+999999999)은 엑셀이 만든
     * 것이 아니고, 지수 없이 적으면 글자가 수억 자가 된다. 읽지 못하는 칸으로 본다.
     */
    private static final int MAX_EXPONENT = 400;

    /**
     * 시트가 아닌 부분(서식, 관계, 목차)을 푼 크기의 한도와, 공유 문자열을 푼 크기의 한도. 이 부분들은 라이브러리가 통째로
     * 메모리에 올리므로 시트보다 훨씬 작게 잡는다.
     */
    private static final long MAX_PART_BYTES = 16L * 1024 * 1024;

    private static final long MAX_SHARED_STRINGS_BYTES = 128L * 1024 * 1024;

    /** 합쳐진 칸의 묶음의 수의 한도. */
    private static final int MAX_MERGED = 500_000;

    /** 엑셀의 칸 하나에 드는 글자의 수. 넘는 칸은 엑셀이 만든 것이 아니다. 읽지 못하는 칸으로 본다. */
    private static final int MAX_CELL_CHARS = 32_767;

    /** 수가 든 칸에 적힌 글자의 수의 한도. 엑셀은 스무 자 남짓으로 적는다. 매우 긴 숫자는 수로 바꾸는 데만 몇 분이 걸린다. */
    private static final int MAX_NUMBER_CHARS = 64;

    /** 알림에 싣는 값의 길이. */
    private static final int MAX_DETAIL_CHARS = 100;

    /** 엑셀의 시트가 가질 수 있는 행과 열의 수. */
    private static final int MAX_ROWS = 1_048_576;

    private static final int MAX_COLUMNS = 16_384;

    // 칸의 종류.
    private static final byte TEXT = 0;

    private static final byte TYPED = 1;

    private static final byte FORMULA_WITHOUT_RESULT = 2;

    private static final byte CELL_ERROR = 3;

    private XlsxTableReader() {
    }

    /**
     * @param content 파일의 바이트
     * @param sheetName 읽을 시트의 이름. 대소문자를 가리지 않는다 (엑셀은 대소문자만 다른 시트 이름을 두지 못한다)
     * @throws TableFileRejectedException 엑셀 파일이 아니거나, 너무 크거나, 그 시트가 없거나, 열 이름의 행이 없거나 틀렸을 때
     */
    static TableFile read(byte[] content, String sheetName) {
        Objects.requireNonNull(content, "파일의 내용은 반드시 있어야 합니다");
        Objects.requireNonNull(sheetName, "읽을 시트의 이름은 반드시 있어야 합니다");
        Sheet sheet;
        try {
            sheet = parse(content, sheetName);
        }
        catch (TooLarge e) {
            throw tooLarge();
        }
        catch (IOException | OpenXML4JException | SAXException | ParserConfigurationException | RuntimeException e) {
            if (e instanceof TableFileRejectedException rejected) {
                throw rejected;
            }
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof TooLarge) {
                    throw tooLarge();
                }
            }
            // 라이브러리는 파일의 모양이 틀린 것을 여러 종류의 예외로 알린다. 어느 것이든 이 파일을 엑셀로 읽지 못한 것이다.
            // 원인은 사람에게 보이는 문구에 넣지 않고 서버의 기록에 남긴다: 투입 기록에는 코드만 남으므로, 여기서 남기지
            // 않으면 이 코드의 결함으로 읽지 못한 것과 파일이 틀린 것을 가릴 수 없다.
            // 예외의 문구에는 올린 파일의 글자가 들어 있을 수 있다. 통째로 남기지 않고 종류와, 자르고 씻은 문구만 남긴다:
            // 줄바꿈이 든 값으로 기록에 지어낸 줄을 만들거나, 매우 긴 값으로 기록을 불리지 못하게 한다.
            LOG.log(System.Logger.Level.WARNING, "엑셀 파일을 읽지 못해 거부한다 (NOT_XLSX). 원인: {0}", causes(e));
            TableFileRejectedException rejected = new TableFileRejectedException("NOT_XLSX",
                    "엑셀(xlsx) 파일로 읽을 수 없습니다. 옛 형식(xls), CSV, 암호가 걸린 파일은 받지 않습니다."
                            + " 엑셀에서 \"Excel 통합 문서(.xlsx)\"로 암호 없이 저장해 다시 넣으세요.");
            rejected.initCause(e);
            throw rejected;
        }
        return table(sheet);
    }

    private static TableFileRejectedException tooLarge() {
        return new TableFileRejectedException("XLSX_TOO_LARGE", "엑셀 파일의 내용이 너무 큽니다 (압축을 푼 크기나 칸의 수가 한도를 넘습니다)."
                + " 쓰지 않는 행과 열, 넓게 합친 칸을 지우거나 파일을 나눠 다시 넣으세요.");
    }

    // ── 파일을 읽는다 ──────────────────────────────────────────────

    /** 한도를 넘었다는 표시. 파일을 읽는 흐름 안에서 던진다. */
    private static final class TooLarge extends IOException {

        private static final long serialVersionUID = 1L;

        TooLarge() {
            super("압축을 푼 크기나 칸의 수가 한도를 넘었습니다");
        }
    }

    /**
     * 메모리에 든 바이트를 압축 파일로 연다. 안의 파일은 읽을 때 풀고, 미리 풀어 두지 않는다.
     * 푼 바이트를 모두 세어 한도를 넘으면 멈춘다.
     */
    private static final class Entries implements ZipEntrySource {

        private final ZipFile zip;

        private long read;

        /** 지금부터 여는 흐름 하나가 풀 수 있는 크기. 읽는 부분에 따라 바꾼다. */
        private long partLimit = MAX_PART_BYTES;

        private boolean closed;

        Entries(byte[] content) throws IOException {
            this.zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(content)).get();
        }

        @Override
        public Enumeration<? extends ZipArchiveEntry> getEntries() {
            return zip.getEntries();
        }

        @Override
        public ZipArchiveEntry getEntry(String path) {
            return zip.getEntry(path.replace('\\', '/'));
        }

        @Override
        public InputStream getInputStream(ZipArchiveEntry entry) throws IOException {
            // 엑셀은 저장만 하거나 DEFLATE 로 압축한다. 다른 방식은 풀 도구가 들어 있지 않다.
            if (entry.getMethod() != ZipArchiveEntry.STORED && entry.getMethod() != ZipArchiveEntry.DEFLATED) {
                throw new IOException("받지 않는 압축 방식입니다: " + entry.getMethod());
            }
            long limit = partLimit;
            return new FilterInputStream(zip.getInputStream(entry)) {

                private long own;

                private void count(int n) throws TooLarge {
                    own += n;
                    read += n;
                    if (own > limit || read > MAX_UNCOMPRESSED_BYTES) {
                        throw new TooLarge();
                    }
                }

                @Override
                public long skip(long n) throws IOException {
                    // 건너뛰는 것도 푸는 것이다. 읽어서 센다.
                    byte[] buffer = new byte[(int) Math.min(8192, Math.max(1, n))];
                    long skipped = 0;
                    while (skipped < n) {
                        int got = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
                        if (got < 0) {
                            break;
                        }
                        skipped += got;
                    }
                    return skipped;
                }

                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = super.read(buffer, offset, length);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }
            };
        }

        @Override
        public void close() throws IOException {
            closed = true;
            zip.close();
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    /** 읽은 시트. 값이 있는 칸만 든다. */
    private static final class Sheet {

        final String name;

        /** 행의 자리(0 부터)에서 그 행의 칸. 자리 순이다. */
        final TreeMap<Integer, Cells> rows = new TreeMap<>();

        final List<CellRangeAddress> merged = new ArrayList<>();

        Sheet(String name) {
            this.name = name;
        }
    }

    /** 한 행의 칸. 열의 자리 순으로 둔다. 칸이 수백만일 때 메모리를 덜 쓰려고 나란한 배열로 둔다. */
    private static final class Cells {

        int count;

        int[] columns = new int[8];

        /** 칸의 값. 오류 값인 칸은 그 오류의 표기다 (없으면 null). */
        String[] values = new String[8];

        byte[] kinds = new byte[8];

        int find(int column) {
            return Arrays.binarySearch(columns, 0, count, column);
        }

        void put(int column, String value, byte kind) {
            int at = find(column);
            if (at < 0) {
                at = -at - 1;
                if (count == columns.length) {
                    columns = Arrays.copyOf(columns, count * 2);
                    values = Arrays.copyOf(values, count * 2);
                    kinds = Arrays.copyOf(kinds, count * 2);
                }
                System.arraycopy(columns, at, columns, at + 1, count - at);
                System.arraycopy(values, at, values, at + 1, count - at);
                System.arraycopy(kinds, at, kinds, at + 1, count - at);
                count++;
            }
            columns[at] = column;
            values[at] = value;
            kinds[at] = kind;
        }

        void remove(int column) {
            int at = find(column);
            if (at >= 0) {
                System.arraycopy(columns, at + 1, columns, at, count - at - 1);
                System.arraycopy(values, at + 1, values, at, count - at - 1);
                System.arraycopy(kinds, at + 1, kinds, at, count - at - 1);
                count--;
            }
        }
    }

    private static Sheet parse(byte[] content, String sheetName)
            throws IOException, OpenXML4JException, SAXException, ParserConfigurationException {
        try (Entries entries = new Entries(content)) {
            OPCPackage pkg = OPCPackage.open(entries);
            try {
                XSSFReader reader = new XSSFReader(pkg);
                boolean date1904 = date1904(reader);
                // 공유 문자열. 발음 표기(후리가나)는 값이 아니므로 넣지 않는다.
                entries.partLimit = MAX_SHARED_STRINGS_BYTES;
                ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
                entries.partLimit = MAX_PART_BYTES;
                StylesTable styles = reader.getStylesTable();
                // 시트는 흘려 읽으므로 전체의 한도까지 받는다. 읽지 않는 시트는 열기만 하고 닫는다.
                entries.partLimit = MAX_UNCOMPRESSED_BYTES;
                List<String> names = new ArrayList<>();
                Sheet found = null;
                XSSFReader.SheetIterator sheets = reader.getSheetIterator();
                while (sheets.hasNext()) {
                    try (InputStream in = sheets.next()) {
                        String name = sheets.getSheetName();
                        if (names.size() < 10) {
                            names.add(brief(name));
                        }
                        if (found == null && name.equalsIgnoreCase(sheetName)) {
                            found = new Sheet(name);
                            XMLReader xml = XMLHelper.newXMLReader();
                            xml.setContentHandler(new SheetHandler(found, strings, styles, date1904));
                            xml.parse(new InputSource(in));
                        }
                    }
                }
                if (found == null) {
                    throw new TableFileRejectedException("SHEET_NOT_FOUND", sheetName, "파일에 시트 \"" + sheetName
                            + "\" 이(가) 없습니다. 파일에 있는 시트(앞의 열 개까지): " + names + ". 시트의 이름을 고치거나 매핑의 시트 이름을 고친 뒤 다시 넣으세요.");
                }
                return found;
            }
            finally {
                // 읽기만 했다. 저장하지 않고 닫는다.
                pkg.revert();
            }
        }
    }

    /** 날짜를 1904년부터 세는 파일인가. 통합 문서의 설정에 적혀 있다. */
    private static boolean date1904(XSSFReader reader)
            throws IOException, OpenXML4JException, SAXException, ParserConfigurationException {
        boolean[] found = new boolean[1];
        try (InputStream in = reader.getWorkbookData()) {
            XMLReader xml = XMLHelper.newXMLReader();
            xml.setContentHandler(new DefaultHandler() {

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) {
                    if ("workbookPr".equals(local(localName, qName))) {
                        String value = attributes.getValue("date1904");
                        found[0] = "1".equals(value) || "true".equals(value);
                    }
                }
            });
            xml.parse(new InputSource(in));
        }
        return found[0];
    }

    /** 예외와 그 원인을 종류와 짧은 문구로 적는다. 다섯 겹까지다. 줄바꿈 같은 제어 글자는 물음표로 바꾼다. */
    private static String causes(Throwable e) {
        StringBuilder text = new StringBuilder();
        int depth = 0;
        for (Throwable cause = e; cause != null && depth < 5; cause = cause.getCause(), depth++) {
            if (depth > 0) {
                text.append(" <- ");
            }
            text.append(cause.getClass().getName());
            String message = cause.getMessage();
            if (message != null) {
                text.append(": ");
                String shown = brief(message);
                for (int i = 0; i < shown.length(); i++) {
                    char c = shown.charAt(i);
                    text.append(Character.isISOControl(c) || c == '\u2028' || c == '\u2029' ? '?' : c);
                }
            }
            StackTraceElement[] stack = cause.getStackTrace();
            if (stack.length > 0) {
                text.append(" (").append(stack[0]).append(')');
            }
        }
        return text.toString();
    }

    /** 문구와 알림에 싣는 글자를 자른다. */
    private static String brief(String text) {
        return text.length() <= MAX_DETAIL_CHARS ? text : text.substring(0, MAX_DETAIL_CHARS) + "…";
    }

    private static String local(String localName, String qName) {
        if (localName != null && !localName.isEmpty()) {
            return localName;
        }
        return qName.substring(qName.indexOf(':') + 1);
    }

    /** 시트의 XML 을 앞에서부터 읽어 값이 있는 칸을 모은다. */
    private static final class SheetHandler extends DefaultHandler {

        private final Sheet sheet;

        private final ReadOnlySharedStringsTable strings;

        private final StylesTable styles;

        private final boolean date1904;

        /** 서식의 번호에서 그것이 날짜 서식인가. 찾는 데만 쓴다. 순회하지 않는다. */
        private final Map<Integer, Boolean> dateStyles = new HashMap<>();

        private int nextRow;

        private int nextColumn;

        private Cells row;

        private int column;

        private String type;

        private int style;

        /** 시트의 자료(sheetData) 안인가. 밖에도 같은 이름의 원소가 있다 (양식 컨트롤의 자리를 적는 row 등). */
        private boolean inData;

        /** 이 칸의 글자가 한도를 넘었는가. */
        private boolean tooLong;

        private boolean inCell;

        private boolean hasValue;

        private boolean hasFormula;

        private boolean inValue;

        private boolean inInline;

        private boolean inPhonetic;

        private boolean inText;

        private final StringBuilder value = new StringBuilder();

        private final StringBuilder inline = new StringBuilder();

        /** 지금까지 모은 칸의 수. 한도를 넘으면 다 읽기 전에 멈춘다. */
        private long stored;

        SheetHandler(Sheet sheet, ReadOnlySharedStringsTable strings, StylesTable styles, boolean date1904) {
            this.sheet = sheet;
            this.strings = strings;
            this.styles = styles;
            this.date1904 = date1904;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            String name = local(localName, qName);
            if ("sheetData".equals(name)) {
                inData = true;
                return;
            }
            if ("mergeCell".equals(name)) {
                String ref = attributes.getValue("ref");
                if (sheet.merged.size() >= MAX_MERGED) {
                    throw new SAXException(new TooLarge());
                }
                try {
                    sheet.merged.add(CellRangeAddress.valueOf(ref));
                }
                catch (RuntimeException e) {
                    throw new SAXException("합쳐진 칸의 범위를 읽을 수 없습니다: " + ref, e);
                }
                return;
            }
            if (!inData) {
                return;
            }
            switch (name) {
                case "row" -> {
                    String r = attributes.getValue("r");
                    int index = r == null ? nextRow : number(r) - 1;
                    if (index < 0 || index >= MAX_ROWS) {
                        throw new SAXException("행의 자리가 시트의 범위 밖입니다: " + r);
                    }
                    nextRow = index + 1;
                    nextColumn = 0;
                    row = sheet.rows.computeIfAbsent(index, i -> new Cells());
                }
                case "c" -> {
                    if (row == null) {
                        throw new SAXException("행 밖에 칸이 있습니다");
                    }
                    String r = attributes.getValue("r");
                    column = r == null ? nextColumn : columnOf(r);
                    if (column < 0 || column >= MAX_COLUMNS) {
                        throw new SAXException("칸의 자리가 시트의 범위 밖입니다: " + r);
                    }
                    nextColumn = column + 1;
                    type = attributes.getValue("t");
                    String s = attributes.getValue("s");
                    style = s == null ? 0 : number(s);
                    inCell = true;
                    hasValue = false;
                    hasFormula = false;
                    tooLong = false;
                    value.setLength(0);
                    inline.setLength(0);
                }
                case "v" -> {
                    if (inCell) {
                        inValue = true;
                        hasValue = true;
                    }
                }
                case "f" -> hasFormula = inCell;
                case "is" -> inInline = inCell;
                // 발음 표기는 값이 아니다.
                case "rPh" -> inPhonetic = true;
                case "t" -> inText = inInline && !inPhonetic;
                default -> {
                    // 그 밖의 것은 값이 아니다.
                }
            }
        }

        @Override
        public void characters(char[] chars, int start, int length) {
            StringBuilder into = inValue ? value : inText ? inline : null;
            if (into == null || tooLong) {
                return;
            }
            // 풀기 전의 글자로 센다. 적는 법(_x000D_)은 글자 하나를 일곱 자로 적으므로 그만큼 넉넉히 받고, 푼 뒤에 다시 본다.
            if (into.length() + length > 7 * MAX_CELL_CHARS) {
                // 엑셀의 칸에 들 수 없는 길이다. 더 모으지 않는다.
                tooLong = true;
                return;
            }
            into.append(chars, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            String name = local(localName, qName);
            if ("sheetData".equals(name)) {
                inData = false;
                row = null;
                inCell = false;
                return;
            }
            if (!inData) {
                return;
            }
            switch (name) {
                case "v" -> inValue = false;
                case "t" -> inText = false;
                case "rPh" -> inPhonetic = false;
                case "is" -> inInline = false;
                case "c" -> {
                    if (inCell) {
                        int before = row.count;
                        cell();
                        stored += row.count - before;
                        if (stored > MAX_CELLS) {
                            throw new SAXException(new TooLarge());
                        }
                    }
                    inCell = false;
                }
                case "row" -> {
                    if (row != null && row.count == 0) {
                        // 값이 없는 행은 두지 않는다. 서식만 있는 빈 행이 많은 파일이 있다.
                        sheet.rows.remove(nextRow - 1);
                    }
                    row = null;
                }
                default -> {
                    // 그 밖의 것은 값이 아니다.
                }
            }
        }

        /** 칸 하나를 다 읽었다. 값이 있으면 행에 넣는다. */
        private void cell() throws SAXException {
            if (tooLong) {
                row.put(column, null, CELL_ERROR);
                return;
            }
            String raw = value.toString().trim();
            boolean noResult = hasFormula && (!hasValue || raw.isEmpty());
            switch (type == null ? "n" : type) {
                case "s" -> {
                    if (raw.isEmpty() && hasFormula) {
                        row.put(column, null, FORMULA_WITHOUT_RESULT);
                    }
                    else if (!raw.isEmpty()) {
                        String shared;
                        try {
                            shared = strings.getItemAt(number(raw)).getString();
                        }
                        catch (RuntimeException e) {
                            throw new SAXException("공유 문자열에 없는 자리를 가리키는 칸이 있습니다: " + raw, e);
                        }
                        text(shared);
                    }
                }
                // 공유 문자열은 라이브러리가 적는 법(_x000D_ 같은 것)을 풀어 준다. 칸에 바로 적힌 글자도 같게 푼다.
                case "inlineStr" -> text(XSSFRichTextString.utfDecode(inline.toString()));
                case "str" -> {
                    if (hasFormula && !hasValue) {
                        row.put(column, null, FORMULA_WITHOUT_RESULT);
                    }
                    else {
                        text(XSSFRichTextString.utfDecode(value.toString()));
                    }
                }
                case "b" -> {
                    if (noResult) {
                        row.put(column, null, FORMULA_WITHOUT_RESULT);
                    }
                    else if (!raw.isEmpty()) {
                        if ("1".equals(raw) || "true".equals(raw)) {
                            row.put(column, "true", TYPED);
                        }
                        else if ("0".equals(raw) || "false".equals(raw)) {
                            row.put(column, "false", TYPED);
                        }
                        else {
                            row.put(column, brief(raw), CELL_ERROR);
                        }
                    }
                }
                case "e" -> row.put(column, raw.isEmpty() ? null : brief(raw), CELL_ERROR);
                case "n" -> {
                    if (noResult) {
                        row.put(column, null, FORMULA_WITHOUT_RESULT);
                    }
                    else if (!raw.isEmpty()) {
                        number(raw, style);
                    }
                }
                case "d" -> {
                    // 날짜를 글자(ISO 8601)로 저장하는 칸. 드물다.
                    if (noResult) {
                        row.put(column, null, FORMULA_WITHOUT_RESULT);
                    }
                    else if (!raw.isEmpty()) {
                        try {
                            LocalDateTime at = raw.indexOf('T') < 0 ? java.time.LocalDate.parse(raw).atStartOfDay()
                                    : LocalDateTime.parse(raw.endsWith("Z") ? raw.substring(0, raw.length() - 1) : raw);
                            row.put(column, shown(at.withNano(0), true), TYPED);
                        }
                        catch (java.time.DateTimeException e) {
                            // 읽을 수 없는 모양(시간대가 붙은 것 등)이다. 파일 전체가 아니라 그 칸만 읽지 못한 것으로 둔다.
                            row.put(column, brief(raw), CELL_ERROR);
                        }
                    }
                }
                default -> throw new SAXException("모르는 종류의 칸이 있습니다: " + type);
            }
        }

        private void text(String written) {
            if (written.length() > MAX_CELL_CHARS) {
                row.put(column, null, CELL_ERROR);
                return;
            }
            String normalized = CsvTableReader.trim(written.replace("\r\n", "\n").replace('\r', '\n'));
            if (!normalized.isEmpty()) {
                row.put(column, normalized, TEXT);
            }
        }

        private void number(String raw, int styleIndex) {
            if (raw.length() > MAX_NUMBER_CHARS) {
                row.put(column, null, CELL_ERROR);
                return;
            }
            BigDecimal number;
            try {
                number = new BigDecimal(raw);
            }
            catch (NumberFormatException e) {
                // 수가 아닌 것(무한대 등)이 수의 자리에 적혀 있다. 읽지 못하는 칸이다.
                row.put(column, null, CELL_ERROR);
                return;
            }
            if (number.signum() != 0 && Math.abs((long) number.precision() - number.scale()) > MAX_EXPONENT) {
                row.put(column, null, CELL_ERROR);
                return;
            }
            if (isDate(styleIndex)) {
                double serial = number.doubleValue();
                // 0 보다 작은 값은 엑셀에서도 날짜가 아니다. 아래에서 수로 읽는다.
                if (DateUtil.isValidExcelDate(serial)) {
                    LocalDateTime at;
                    try {
                        // 초 아래는 반올림한다. 시각을 소수로 저장하므로 14:30:00 이 14:29:59.999 로 나오는 일이 있다.
                        at = DateUtil.getLocalDateTime(serial, date1904, true);
                    }
                    catch (RuntimeException e) {
                        at = null;
                    }
                    if (at == null || at.getYear() > 9999) {
                        // 날짜로 담을 수 없는 큰 값이다.
                        row.put(column, null, CELL_ERROR);
                        return;
                    }
                    // 반올림해 다음 날이 된 값(0.9999999)도 날짜가 있는 것으로 본다. 날짜를 1904년부터 세는 파일의 0 은
                    // 1904-01-01 이라는 날짜다 (1900년부터 세는 파일의 0 은 없는 날이다).
                    boolean hasDate = Math.floor(serial + 0.5 / 86_400) >= 1
                            || (date1904 && at.toLocalTime().equals(LocalTime.MIDNIGHT));
                    row.put(column, shown(at, hasDate), TYPED);
                    return;
                }
            }
            // 정수는 그대로 둔다. 반올림은 소수 계산의 오차를 지우려는 것이다. 열여섯 자리 번호가 뭉개지지 않는다.
            BigDecimal rounded = number.stripTrailingZeros().scale() <= 0 ? number : number.round(PRECISION);
            row.put(column, rounded.signum() == 0 ? "0" : rounded.stripTrailingZeros().toPlainString(), TYPED);
        }

        private boolean isDate(int styleIndex) {
            return dateStyles.computeIfAbsent(styleIndex, index -> {
                if (styles == null || index < 0 || index >= styles.getNumCellStyles()) {
                    return false;
                }
                XSSFCellStyle cellStyle = styles.getStyleAt(index);
                if (cellStyle == null) {
                    return false;
                }
                int format = cellStyle.getDataFormat();
                // 파일이 그 번호에 모양을 따로 적었으면 그 모양으로 본다.
                if (styles.getNumberFormats().containsKey((short) format)) {
                    return DateUtil.isADateFormat(format, cellStyle.getDataFormatString());
                }
                // 엑셀에 들어 있는 서식 가운데 27 ~ 36 과 50 ~ 58 은 한국어, 일본어, 중국어 엑셀의 날짜와 시각 서식이다
                // (파일 형식의 표준 ECMA-376 의 numFmt). 파일에 모양이 적혀 있지 않아 라이브러리는 날짜로 알아보지 못한다.
                if ((format >= 27 && format <= 36) || (format >= 50 && format <= 58)) {
                    return true;
                }
                return DateUtil.isADateFormat(format, cellStyle.getDataFormatString());
            });
        }

        private static int number(String text) throws SAXException {
            try {
                return Integer.parseInt(text.trim());
            }
            catch (NumberFormatException e) {
                throw new SAXException("수가 있어야 할 자리에 다른 것이 있습니다: " + text, e);
            }
        }

        /** 칸의 주소(B7)에서 열의 자리(0 부터)를 읽는다. */
        private static int columnOf(String reference) throws SAXException {
            int column = 0;
            int letters = 0;
            for (int i = 0; i < reference.length(); i++) {
                char c = reference.charAt(i);
                if (c == '$') {
                    continue;
                }
                if (c >= 'a' && c <= 'z') {
                    c = (char) (c - 'a' + 'A');
                }
                if (c < 'A' || c > 'Z') {
                    break;
                }
                column = column * 26 + (c - 'A' + 1);
                letters++;
                if (letters > 3) {
                    throw new SAXException("칸의 주소를 읽을 수 없습니다: " + reference);
                }
            }
            if (letters == 0) {
                throw new SAXException("칸의 주소를 읽을 수 없습니다: " + reference);
            }
            return column - 1;
        }
    }

    /** 날짜와 시각의 표준 표기. 시각이 0시이면 날짜만, 날짜가 없으면(시각만 든 칸) 시각만 적는다. */
    private static String shown(LocalDateTime at, boolean hasDate) {
        if (!hasDate) {
            return at.toLocalTime().format(TIME);
        }
        return at.toLocalTime().equals(LocalTime.MIDNIGHT) ? at.toLocalDate().toString() : at.format(DATE_TIME);
    }

    // ── 읽은 시트를 표로 만든다 ──────────────────────────────────────

    private static TableFile table(Sheet sheet) {
        fillMerged(sheet);

        List<String> columns = null;
        List<TableRow> rows = new ArrayList<>();
        List<SkippedRow> skipped = new ArrayList<>();
        long cells = 0;
        // 읽은 행은 바로 버린다. 큰 파일에서 같은 내용을 두 벌 들고 있지 않는다.
        for (Map.Entry<Integer, Cells> entry = sheet.rows.pollFirstEntry(); entry != null; entry = sheet.rows
            .pollFirstEntry()) {
            int rowNo = entry.getKey() + 1;
            Cells row = entry.getValue();
            if (row.count == 0) {
                continue;
            }
            if (columns == null) {
                // 값이 있는 첫 행이 열 이름이다. 읽지 못하는 칸은 이름이 될 수 없다.
                int width = row.columns[row.count - 1] + 1;
                String[] names = new String[width];
                Arrays.fill(names, "");
                for (int i = 0; i < row.count; i++) {
                    names[row.columns[i]] = row.kinds[i] == TEXT || row.kinds[i] == TYPED ? row.values[i] : "";
                }
                columns = header(Arrays.asList(names));
                continue;
            }
            String[] values = new String[columns.size()];
            Arrays.fill(values, "");
            Set<Integer> typed = new TreeSet<>();
            Map<Integer, UnreadableCell> unreadable = new TreeMap<>();
            boolean any = false;
            for (int i = 0; i < row.count && row.columns[i] < values.length; i++) {
                int c = row.columns[i];
                any = true;
                switch (row.kinds[i]) {
                    case TEXT -> values[c] = row.values[i];
                    case TYPED -> {
                        values[c] = row.values[i];
                        typed.add(c);
                    }
                    default -> unreadable.put(c, new UnreadableCell(
                            row.kinds[i] == CELL_ERROR ? "CELL_ERROR" : "FORMULA_WITHOUT_RESULT", row.values[i]));
                }
            }
            if (!any) {
                // 열 이름이 있는 열에 아무것도 없는 행은 행으로 보지 않는다.
                continue;
            }
            boolean invalid = false;
            for (String value : values) {
                invalid |= value.indexOf('\u0000') >= 0;
            }
            if (invalid) {
                skipped.add(new SkippedRow(rowNo, "INVALID_CHARACTER", null));
                continue;
            }
            cells += values.length;
            if (cells > MAX_CELLS) {
                throw tooLarge();
            }
            rows.add(new TableRow(rowNo, Arrays.asList(values), typed, unreadable));
        }
        if (columns == null) {
            throw new TableFileRejectedException("NO_HEADER",
                    "시트 \"" + sheet.name + "\" 에 열 이름의 행이 없습니다. 첫 행에 열 이름을 적은 뒤 다시 넣으세요.");
        }
        return new TableFile(columns, rows, skipped);
    }

    /**
     * 합쳐진 칸을 채운다. 묶음의 왼쪽 위 칸에 값이 있으면 묶음의 모든 자리에 그 값을 두고, 없으면 묶음의 모든 자리를 비운다
     * (엑셀은 묶음의 왼쪽 위 칸만 보여 준다). 채우는 칸의 수가 한도를 넘으면 파일 전체를 거부한다.
     */
    private static void fillMerged(Sheet sheet) {
        long filled = 0;
        for (CellRangeAddress region : sheet.merged) {
            int firstRow = region.getFirstRow();
            int lastRow = Math.min(region.getLastRow(), MAX_ROWS - 1);
            int firstColumn = region.getFirstColumn();
            int lastColumn = Math.min(region.getLastColumn(), MAX_COLUMNS - 1);
            if (firstRow < 0 || firstColumn < 0 || (firstRow == lastRow && firstColumn == lastColumn)) {
                continue;
            }
            Cells origin = sheet.rows.get(firstRow);
            int at = origin == null ? -1 : origin.find(firstColumn);
            if (at < 0) {
                // 왼쪽 위가 비었다. 묶음에 가려진 값이 있으면 지운다. 값이 있는 행만 본다.
                for (Cells row : sheet.rows.subMap(firstRow, true, lastRow, true).values()) {
                    // 본 칸도 센다. 묶음이 매우 많은 파일이 끝없이 돌지 않게 한다.
                    filled += 1 + row.count;
                    if (filled > MAX_CELLS) {
                        throw tooLarge();
                    }
                    for (int i = row.count - 1; i >= 0; i--) {
                        if (row.columns[i] >= firstColumn && row.columns[i] <= lastColumn) {
                            row.remove(row.columns[i]);
                        }
                    }
                }
                continue;
            }
            String value = origin.values[at];
            byte kind = origin.kinds[at];
            filled += (long) (lastRow - firstRow + 1) * (lastColumn - firstColumn + 1);
            if (filled > MAX_CELLS) {
                throw tooLarge();
            }
            for (int r = firstRow; r <= lastRow; r++) {
                Cells row = sheet.rows.computeIfAbsent(r, i -> new Cells());
                for (int c = firstColumn; c <= lastColumn; c++) {
                    row.put(c, value, kind);
                }
            }
        }
    }

    private static List<String> header(List<String> names) {
        // 이름이 처음 나온 자리. 찾는 데만 쓴다. 순회하지 않는다.
        Map<String, Integer> firstAt = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name.isEmpty()) {
                throw new TableFileRejectedException("BLANK_COLUMN_NAME", (i + 1)
                        + "번째 열의 이름이 비어 있습니다. 표는 시트의 첫 열부터 적고, 열 이름을 빠짐없이 적은 뒤 다시 넣으세요.");
            }
            if (name.indexOf('\u0000') >= 0) {
                throw new TableFileRejectedException("INVALID_COLUMN_NAME",
                        (i + 1) + "번째 열의 이름에 쓸 수 없는 글자(NUL)가 있습니다. 그 글자를 뺀 뒤 다시 넣으세요.");
            }
            Integer earlier = firstAt.putIfAbsent(name, i);
            if (earlier != null) {
                throw new TableFileRejectedException("DUPLICATE_COLUMN_NAME", "열 이름 \"" + name + "\" 이(가) "
                        + (earlier + 1) + "번째와 " + (i + 1) + "번째에 겹쳐 있습니다. 열 이름을 다르게 고친 뒤 다시 넣으세요.");
            }
        }
        return List.copyOf(names);
    }
}
