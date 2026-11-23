package com.altumlab.tabnit.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 변동 이벤트의 행에서 바뀐 속성과 관계 유형을 읽는다 (D5 5절 change_event_changed_item, D2 AD-291).
 * 변동 이벤트를 읽는 조회들이 함께 쓴다.
 */
final class ChangedItems {

    static final String ATTRIBUTE = "ATTRIBUTE";

    static final String RELATION_TYPE = "RELATION_TYPE";

    /** 변동 이벤트마다의 속성 이름. 이름의 글자 코드 순. */
    private final Map<String, List<String>> attributes = new TreeMap<>(LinkOrder.STRINGS);

    /** 변동 이벤트마다의 관계 유형 이름. 이름의 글자 코드 순. */
    private final Map<String, List<String>> relationTypes = new TreeMap<>(LinkOrder.STRINGS);

    /** 변동 이벤트마다의 바뀐 값. 저장된 차례 순 (D2 AD-367). */
    private final Map<String, List<ValueChange>> values = new TreeMap<>(LinkOrder.STRINGS);

    private ChangedItems() {
    }

    /**
     * 바뀐 것을 읽는다.
     *
     * @param from 어느 변동 이벤트의 것인가. `from change_event_changed_item c` 뒤에 이어 붙는 join 과 where 다.
     *        이 모듈이 적은 고정 글자만 넘긴다
     */
    static ChangedItems read(JdbcTemplate jdbc, String from, Object... args) {
        ChangedItems items = new ChangedItems();
        jdbc.query("select c.change_event_id, c.item_kind, c.name from change_event_changed_item c " + from
                + " order by c.change_event_id, c.item_kind, c.name", rs -> {
                    Map<String, List<String>> into = ATTRIBUTE.equals(rs.getString("item_kind")) ? items.attributes
                            : items.relationTypes;
                    into.computeIfAbsent(rs.getString("change_event_id"), k -> new ArrayList<>()).add(rs.getString("name"));
                }, args);
        // 같은 변동 이벤트들의 바뀐 값. 붙이는 join 과 where 는 위와 같다.
        jdbc.query("select c.change_event_id, c.entity_id, c.attribute_name, c.before_value, c.after_value"
                + " from change_event_changed_value c " + from + " order by c.change_event_id, c.seq", rs -> {
                    items.values.computeIfAbsent(rs.getString("change_event_id"), k -> new ArrayList<>())
                        .add(new ValueChange(rs.getString("entity_id"), rs.getString("attribute_name"),
                                rs.getString("before_value"), rs.getString("after_value")));
                }, args);
        return items;
    }

    /** 그 변동 이벤트에서 바뀐 속성. 바뀐 것을 남기지 않은 이벤트이면 null. */
    List<String> attributes(String changeEventId, boolean recorded) {
        return recorded ? attributes.getOrDefault(changeEventId, List.of()) : null;
    }

    /** 그 변동 이벤트에서 바뀐 관계 유형. 바뀐 것을 남기지 않은 이벤트이면 null. */
    List<String> relationTypes(String changeEventId, boolean recorded) {
        return recorded ? relationTypes.getOrDefault(changeEventId, List.of()) : null;
    }

    /** 그 변동 이벤트에서 바뀐 속성 값. 바뀐 값을 남기지 않은 이벤트이면 null. */
    List<ValueChange> values(String changeEventId, boolean recorded) {
        return recorded ? values.getOrDefault(changeEventId, List.of()) : null;
    }
}
