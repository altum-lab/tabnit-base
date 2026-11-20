package com.altumlab.tabnit.core;

import java.time.LocalDate;
import java.util.Objects;

/** 관계의 유효기간. 시작일은 포함하고 종료일은 제외한다. 종료일이 없으면 계속 유효하다. */
public record ValidityPeriod(LocalDate from, LocalDate to) {

    public ValidityPeriod {
        Objects.requireNonNull(from, "시작일은 반드시 있어야 합니다");
        if (to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("종료일은 시작일보다 뒤여야 합니다: " + from + " ~ " + to);
        }
    }

    public boolean contains(LocalDate asOf) {
        Objects.requireNonNull(asOf, "기준 날짜는 반드시 있어야 합니다");
        return !asOf.isBefore(from) && (to == null || asOf.isBefore(to));
    }
}
