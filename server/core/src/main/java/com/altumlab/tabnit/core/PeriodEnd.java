package com.altumlab.tabnit.core;

import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 유효기간을 끝내는 일. 관계와 속성 값이 같은 규칙을 쓴다 (D2 AD-162).
 *
 * <p>종료일이 없는 것에만 종료일을 넣는다. 종료일은 시작일보다 뒤여야 한다. 이미 같은 날짜로 끝나 있으면 그대로 두고,
 * 다른 날짜로 끝나 있으면 거부한다. 한 번 넣은 종료일은 바꾸지 않는다.
 */
enum PeriodEnd {

    RELATION("update relation set valid_to = ? where id = ? and valid_to is null and valid_from < ?",
            "select valid_from, valid_to from relation where id = ?", "관계가", "관계를", "관계"),

    ATTRIBUTE_VALUE("update attribute_value set valid_to = ? where id = ? and valid_to is null and valid_from < ?",
            "select valid_from, valid_to from attribute_value where id = ?", "속성 값이", "속성 값을", "속성 값");

    private final String update;

    private final String select;

    private final String subject;

    private final String object;

    private final String noun;

    PeriodEnd(String update, String select, String subject, String object, String noun) {
        this.update = update;
        this.select = select;
        this.subject = subject;
        this.object = object;
        this.noun = noun;
    }

    /**
     * 끝낸다. 끝냈거나 이미 같은 날짜로 끝나 있으면 null 을, 거부하면 그 까닭을 돌려준다.
     *
     * <p>거부는 저장소의 오류를 내지 않는다. 그래서 부르는 쪽의 트랜잭션이 깨지지 않는다.
     *
     * @throws CoreStorageException 저장소에 쓰지 못했을 때
     */
    String end(JdbcTemplate jdbc, String id, LocalDate endDate) {
        String dateFault = StorableDates.fault(endDate, "종료일");
        if (dateFault != null) {
            return dateFault + " (" + noun + ": " + id + ")";
        }
        try {
            // 열려 있고 종료일이 시작일보다 뒤일 때만 넣는다. 조건을 문장 하나에 담아, 두 곳에서 동시에 끝내도 한 번만 들어간다.
            if (jdbc.update(update, endDate, id, endDate) == 1) {
                return null;
            }
            // 넣지 못했다. 까닭을 가린다.
            List<LocalDate[]> rows = jdbc.query(select,
                    (rs, n) -> new LocalDate[] { rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class) },
                    id);
            if (rows.isEmpty()) {
                return subject + " 없습니다: " + id + ". 식별자를 확인하세요.";
            }
            LocalDate from = rows.get(0)[0];
            LocalDate to = rows.get(0)[1];
            if (endDate.equals(to)) {
                return null;
            }
            if (to != null) {
                return subject + " 이미 " + to + " 에 끝나 있습니다: " + id + ". 한 번 넣은 종료일은 바꾸지 않습니다 (넣으려던 날짜: "
                        + endDate + ").";
            }
            if (!endDate.isAfter(from)) {
                return "종료일은 시작일보다 뒤여야 합니다: " + noun + " " + id + " 의 시작일은 " + from + " 인데 종료일로 " + endDate
                        + " 을(를) 넣으려 했습니다. 날짜를 확인하세요.";
            }
            // 열려 있고 날짜도 맞는데 넣지 못했다. 넣으려던 때와 다시 읽은 때 사이에 저장소가 바뀐 것이다. 까닭을 지어내지 않는다.
            throw new CoreStorageException(object + " 끝내지 못했습니다: " + id
                    + ". 그 사이에 저장소가 바뀌었습니다. 다시 시도하세요.", null);
        }
        catch (DataAccessException e) {
            throw new CoreStorageException(object + " 끝내지 못했습니다: " + id + ". 저장소의 상태를 확인한 뒤 다시 시도하세요.", e);
        }
    }
}
