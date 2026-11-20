package com.altumlab.tabnit.link;

import com.altumlab.tabnit.pack.ValueFormat;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 입력 파일의 칸의 값을 속성의 형식에 맞는 정해진 표기로 읽는다 (D6 5절, 7절 "값을 읽는 법").
 *
 * <p>적힌 모양으로만 읽는다. 추측하지 않는다 (D2 AD-94). 값이 같으면 같은 표기가 되게 다듬는다 (D2 AD-176).
 * 날짜는 엄격하게 읽고 언어 설정을 고정한다 (D2 AD-166).
 */
final class ValueParser {

    private static final LocalDate EARLIEST = LocalDate.of(1, 1, 1);

    private static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    /** 모양마다 만든 읽는 장치. 찾는 데만 쓴다. */
    private final Map<String, DateTimeFormatter> formatters = new HashMap<>();

    /**
     * 날짜를 읽는다. 모양이 null 이면 표준 표기(2026-03-01)만 받는다.
     *
     * @return 날짜. 모양에 맞지 않거나, 없는 날짜이거나, 받는 범위 밖이면 null
     */
    LocalDate date(String raw, String pattern) {
        DateTimeFormatter formatter = formatters.computeIfAbsent(pattern == null ? "yyyy-MM-dd" : pattern,
                ValueParser::strict);
        try {
            LocalDate date = LocalDate.parse(raw, formatter);
            return date.isBefore(EARLIEST) || date.isAfter(LATEST) ? null : date;
        }
        catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 값을 그 형식의 정해진 표기로 바꾼다.
     *
     * @param datePattern 날짜의 모양. 속성의 열에 적힌 것, 없으면 매핑의 기본 모양, 그것도 없으면 null
     * @return 정해진 표기. 형식에 맞지 않으면 null
     */
    String canonical(ValueFormat format, String raw, String datePattern, String groupingSeparator, String trueValue,
            String falseValue) {
        return switch (format) {
            case STRING -> raw;
            case INTEGER -> integer(ungroup(raw, groupingSeparator));
            case DECIMAL -> decimal(ungroup(raw, groupingSeparator));
            case DATE -> {
                LocalDate date = date(raw, datePattern);
                yield date == null ? null : date.toString();
            }
            case BOOLEAN -> bool(raw, trueValue, falseValue);
        };
    }

    private static String ungroup(String raw, String groupingSeparator) {
        return groupingSeparator == null ? raw : raw.replace(groupingSeparator, "");
    }

    /** 정수. 앞의 0 과 더하기 부호를 뗀다 (007 은 7, -0 은 0). */
    private static String integer(String text) {
        if (!matches(text, false)) {
            return null;
        }
        return new BigInteger(text.startsWith("+") ? text.substring(1) : text).toString();
    }

    /** 소수. 뒤의 0 을 뗀다 (3.50 은 3.5, 2.0 은 2). 지수 표기는 받지 않는다. */
    private static String decimal(String text) {
        if (!matches(text, true)) {
            return null;
        }
        BigDecimal value = new BigDecimal(text);
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /** 부호 하나와 ASCII 숫자, (소수이면) 마침표 하나로만 이루어졌는가. 숫자가 하나는 있어야 한다. */
    private static boolean matches(String text, boolean allowPoint) {
        int digits = 0;
        int points = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            }
            else if (c == '.' && allowPoint) {
                points++;
            }
            else if (!((c == '+' || c == '-') && i == 0)) {
                return false;
            }
        }
        return digits > 0 && points <= 1;
    }

    /** 참·거짓. 매핑이 참과 거짓으로 볼 글자를 적었으면 그것만, 아니면 true 와 false 만 받는다. */
    private static String bool(String raw, String trueValue, String falseValue) {
        if (raw.equals(trueValue == null ? "true" : trueValue)) {
            return "true";
        }
        if (raw.equals(falseValue == null ? "false" : falseValue)) {
            return "false";
        }
        return null;
    }

    /**
     * 모양으로 엄격하게 읽는 장치를 만든다. 없는 날짜(2월 30일)를 다른 날짜로 고쳐 읽지 않는다.
     * 연의 글자 y 를 u 로 바꾼다. 엄격하게 읽을 때 y 는 연대가 함께 있어야 날짜가 된다.
     */
    private static DateTimeFormatter strict(String pattern) {
        StringBuilder converted = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            }
            converted.append(c == 'y' && !quoted ? 'u' : c);
        }
        return DateTimeFormatter.ofPattern(converted.toString(), Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    }
}
