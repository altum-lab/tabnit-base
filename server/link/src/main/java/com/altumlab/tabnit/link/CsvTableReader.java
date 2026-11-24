package com.altumlab.tabnit.link;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CSV 파일을 읽어 행 단위로 넘긴다 (보드 C-01, D6 7절 "입력 파일").
 *
 * <p>UTF-8 만 받는다. 맨 앞의 표시(BOM)는 떼고 읽는다. 인코딩을 짐작하지 않는다 (D2 AD-169).
 * 첫 행은 열 이름이다. 칸은 쉼표로 나누고, 쉼표·큰따옴표·줄바꿈이 든 값은 큰따옴표로 감싼다 (RFC 4180 의 적는 법).
 * 열 이름과 값은 앞뒤 빈칸을 떼고 읽는다 (D2 AD-167).
 *
 * <p>파일 수준의 문제는 파일 전체를 거부하고, 행 수준의 문제는 그 행만 건너뛰며 행 번호와 이유를 남긴다.
 * 특정 파일의 열 이름을 알지 못한다. 열이 무엇인지는 매핑이 정한다.
 */
final class CsvTableReader {

    private final String text;

    private List<String> columns;

    private final List<TableRow> rows = new ArrayList<>();

    private final List<SkippedRow> skipped = new ArrayList<>();

    private CsvTableReader(String text) {
        this.text = text;
    }

    /**
     * @param content 파일의 바이트
     * @throws TableFileRejectedException UTF-8 이 아니거나, 열 이름의 줄이 없거나 틀렸거나, 큰따옴표가 닫히지 않았을 때
     */
    static TableFile read(byte[] content) {
        Objects.requireNonNull(content, "파일의 내용은 반드시 있어야 합니다");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        }
        catch (CharacterCodingException e) {
            throw new TableFileRejectedException("NOT_UTF8",
                    "파일이 UTF-8 이 아닙니다. UTF-8 로 저장해 다시 넣으세요 (엑셀에서는 \"CSV UTF-8\" 형식).");
        }
        // 맨 앞의 표시(BOM, U+FEFF). 눈에 보이지 않는 글자라 소스에는 글자 코드로 적는다.
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
            text = text.substring(1);
        }
        return new CsvTableReader(text).parse();
    }

    /**
     * 앞뒤에서 떼는 빈칸인가: 유니코드의 공백 글자(전각 빈칸 포함)와 줄바꿈하지 않는 빈칸(U+00A0).
     * U+00A0 은 엑셀에서 옮긴 자료에 흔하고 눈에 보이지 않아, 떼지 않으면 같은 키가 다른 키가 된다 (D2 AD-313).
     */
    private static boolean isBlank(char c) {
        return Character.isWhitespace(c) || c == '\u00A0';
    }

    /** 칸의 앞뒤 빈칸을 뗀다. 가운데의 빈칸은 그대로 둔다 (D2 AD-167). */
    static String trim(CharSequence cell) {
        int start = 0;
        int end = cell.length();
        while (start < end && isBlank(cell.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(cell.charAt(end - 1))) {
            end--;
        }
        return cell.subSequence(start, end).toString();
    }

    private TableFile parse() {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        int line = 1;
        int rowStartLine = 1;
        int quoteOpenLine = 0;
        boolean inQuotes = false;
        // 이 칸이 큰따옴표로 감싼 값이고 이미 닫혔는가.
        boolean closedQuote = false;
        // 이 행에서 닫는 큰따옴표 뒤에 글자가 이어진 칸이 있었는가.
        boolean textAfterQuote = false;
        // 이 줄에 글자나 쉼표가 하나라도 있었는가. 없으면 빈 줄이다.
        boolean rowHasContent = false;
        // 이 칸에 지금까지 빈칸 말고 다른 글자가 없었는가. 칸의 처음에 나온 큰따옴표인지 가리는 데 쓴다.
        boolean cellBlank = true;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean lineBreak = c == '\n' || c == '\r';
            boolean crLf = c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n';
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    }
                    else {
                        inQuotes = false;
                        closedQuote = true;
                    }
                }
                else if (lineBreak) {
                    // 감싼 값 안의 줄바꿈은 어떻게 적혀 있든 LF 하나로 읽는다.
                    // 같은 내용을 다른 줄바꿈으로 저장한 파일이 다른 값으로 읽히지 않게 한다.
                    cell.append('\n');
                    if (crLf) {
                        i++;
                    }
                    line++;
                }
                else {
                    cell.append(c);
                }
                continue;
            }
            if (lineBreak) {
                if (crLf) {
                    i++;
                }
                if (rowHasContent) {
                    cells.add(trim(cell));
                    emit(rowStartLine, cells, textAfterQuote);
                }
                cells = new ArrayList<>();
                cell.setLength(0);
                closedQuote = false;
                cellBlank = true;
                textAfterQuote = false;
                rowHasContent = false;
                line++;
                rowStartLine = line;
                continue;
            }
            rowHasContent = true;
            if (c == ',') {
                cells.add(trim(cell));
                cell.setLength(0);
                closedQuote = false;
                cellBlank = true;
            }
            else if (closedQuote) {
                // 닫는 큰따옴표 뒤에는 빈칸만 올 수 있다.
                if (!isBlank(c)) {
                    textAfterQuote = true;
                }
            }
            else if (c == '"' && cellBlank) {
                // 칸의 처음(앞의 빈칸은 버린다)에 나온 큰따옴표가 감싼 값을 연다.
                cell.setLength(0);
                inQuotes = true;
                quoteOpenLine = line;
            }
            else {
                // 감싸지 않은 값 가운데의 큰따옴표는 글자 그대로다.
                cell.append(c);
                if (!isBlank(c)) {
                    cellBlank = false;
                }
            }
        }
        if (inQuotes) {
            throw new TableFileRejectedException("UNCLOSED_QUOTE", quoteOpenLine
                    + "행에서 연 큰따옴표가 닫히지 않은 채 파일이 끝났습니다. 그 줄의 큰따옴표를 확인해 고친 뒤 다시 넣으세요.");
        }
        if (rowHasContent) {
            cells.add(trim(cell));
            emit(rowStartLine, cells, textAfterQuote);
        }
        if (columns == null) {
            throw new TableFileRejectedException("NO_HEADER",
                    "파일에 열 이름의 줄이 없습니다. 첫 줄에 열 이름을 쉼표로 나눠 적은 뒤 다시 넣으세요.");
        }
        return new TableFile(columns, rows, skipped);
    }

    /** 줄 하나를 열 이름으로, 또는 행으로 받는다. */
    private void emit(int rowNo, List<String> cells, boolean textAfterQuote) {
        if (columns == null) {
            columns = header(rowNo, cells, textAfterQuote);
            return;
        }
        if (textAfterQuote) {
            skipped.add(new SkippedRow(rowNo, "TEXT_AFTER_QUOTE", null));
            return;
        }
        for (String value : cells) {
            if (value.indexOf('\u0000') >= 0) {
                skipped.add(new SkippedRow(rowNo, "INVALID_CHARACTER", null));
                return;
            }
        }
        if (cells.size() != columns.size()) {
            skipped.add(new SkippedRow(rowNo, "CELL_COUNT_MISMATCH", String.valueOf(cells.size())));
            return;
        }
        rows.add(new TableRow(rowNo, cells));
    }

    private static List<String> header(int rowNo, List<String> names, boolean textAfterQuote) {
        if (textAfterQuote) {
            throw new TableFileRejectedException("INVALID_HEADER", rowNo
                    + "행(열 이름의 줄)에서 닫는 큰따옴표 뒤에 글자가 이어집니다. 열 이름을 고친 뒤 다시 넣으세요.");
        }
        // 이름이 처음 나온 자리. 찾는 데만 쓴다. 순회하지 않는다.
        Map<String, Integer> firstAt = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name.isEmpty()) {
                throw new TableFileRejectedException("BLANK_COLUMN_NAME",
                        (i + 1) + "번째 열의 이름이 비어 있습니다. 열 이름을 적은 뒤 다시 넣으세요.");
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
