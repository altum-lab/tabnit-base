package com.altumlab.tabnit.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ValidityPeriodTest {

    private final ValidityPeriod p = new ValidityPeriod(LocalDate.of(2025, 3, 1), LocalDate.of(2026, 9, 1));

    @Test
    void 시작일_당일은_유효하다() {
        assertTrue(p.contains(LocalDate.of(2025, 3, 1)));
    }

    @Test
    void 시작일_전날은_유효하지_않다() {
        assertFalse(p.contains(LocalDate.of(2025, 2, 28)));
    }

    @Test
    void 종료일_전날은_유효하다() {
        assertTrue(p.contains(LocalDate.of(2026, 8, 31)));
    }

    @Test
    void 종료일_당일은_유효하지_않다() {
        assertFalse(p.contains(LocalDate.of(2026, 9, 1)));
    }

    @Test
    void 종료일이_없으면_계속_유효하다() {
        assertTrue(new ValidityPeriod(LocalDate.of(2025, 3, 1), null).contains(LocalDate.of(2099, 1, 1)));
    }

    @Test
    void 종료일이_시작일보다_앞서면_거부한다() {
        assertThrows(IllegalArgumentException.class,
                () -> new ValidityPeriod(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 1)));
    }
}
