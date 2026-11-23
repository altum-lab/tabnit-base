package com.altumlab.tabnit.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 저장소에 담을 수 있는 날짜와 시각인지 본다.
 *
 * <p>받는 범위는 0001-01-01 부터 9999-12-31 까지다. 끝없는 날짜는 받지 않는다 (D2 AD-75).
 * 범위 밖의 값을 저장소까지 보내면 저장소의 오류가 되어 부르는 쪽의 트랜잭션을 깬다. 그래서 여기서 먼저 거른다.
 */
final class StorableDates {

    static final LocalDate EARLIEST = LocalDate.of(1, 1, 1);

    static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    private StorableDates() {
    }

    /** 담을 수 있으면 null, 아니면 무엇이 틀렸고 어떻게 하면 되는지. date 가 null 이면(종료일 없음) 담을 수 있다. */
    static String fault(LocalDate date, String what) {
        if (date == null) {
            return null;
        }
        if (LocalDate.MAX.equals(date) || LocalDate.MIN.equals(date)) {
            return "끝없는 날짜는 " + what + "(으)로 쓸 수 없습니다. 종료일이 없으면 종료일을 비우고, 아니면 실제 날짜를 넣어 다시 보내세요.";
        }
        if (date.isBefore(EARLIEST) || date.isAfter(LATEST)) {
            return what + " " + date + " 은(는) 쓸 수 없는 날짜입니다. " + EARLIEST + " 부터 " + LATEST
                    + " 까지의 날짜를 넣어 다시 보내세요.";
        }
        return null;
    }

    /**
     * 조회의 기준 날짜를 확인한다. 반드시 있어야 하고 받는 범위 안이어야 한다 (D5 3절, D2 AD-370).
     * 범위 밖의 날짜로 물으면 저장소의 오류가 되거나, 끝없는 날짜는 오류 없이 엉뚱한 답(열려 있는 것만)을 낸다.
     *
     * @throws NullPointerException 날짜가 없을 때
     * @throws IllegalArgumentException 받는 범위 밖일 때. 무엇이 틀렸고 어떻게 하면 되는지 담는다
     */
    static LocalDate requireAsOf(LocalDate asOf) {
        java.util.Objects.requireNonNull(asOf, "기준 날짜는 반드시 있어야 합니다");
        String fault = fault(asOf, "기준 날짜");
        if (fault != null) {
            throw new IllegalArgumentException(fault);
        }
        return asOf;
    }

    /** 기록한 때로 담을 수 있으면 null, 아니면 까닭. */
    static String fault(Instant recordedAt) {
        Instant earliest = EARLIEST.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant latest = LATEST.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        if (recordedAt.isBefore(earliest) || !recordedAt.isBefore(latest)) {
            return "기록한 때 " + recordedAt + " 은(는) 쓸 수 없는 시각입니다. " + EARLIEST + " 부터 " + LATEST
                    + " 까지의 시각을 넣어 다시 보내세요.";
        }
        return null;
    }
}
