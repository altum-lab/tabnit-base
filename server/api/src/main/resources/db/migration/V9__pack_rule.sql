-- pack 의 테이블: 규칙 정의 (D5 4절, D8 2절 ~ 3절, D2 AD-213 ~ AD-216).
-- 규칙을 실행하는 것은 rules 모듈이다. 여기에는 읽어서 검증한 정의만 담는다.
-- 조건은 Turtle 에서 차례가 없으므로, 읽은 뒤의 정해진 차례(대상, 유형, 속성, 비교, 값)로 번호를 매긴다.

create table pack_rule (
    pack_id      text collate "C" not null,
    -- 규칙 번호. 글자다. Pack 안에서 겹치지 않고 자릿수가 모두 같다 (pack 의 검증이 지킨다, D2 AD-215).
    rule_no      text collate "C" not null,
    label        text             not null,
    -- 우선순위. 수가 작을수록 먼저다 (D2 AD-103).
    priority     integer          not null,
    -- 어떤 종류의 변동에 걸리는가. 규칙에 적지 않았으면 셋 다 참이다.
    on_added     boolean          not null,
    on_changed   boolean          not null,
    on_removed   boolean          not null,
    -- 조치의 대상이 영향 대상인가 변동 대상인가 (D2 AD-102).
    applies_to   text collate "C" not null,
    target_type  text collate "C" not null,
    action_text  text             not null,
    due_in_days  integer          not null,
    constraint pack_rule_pk primary key (pack_id, rule_no),
    constraint pack_rule_pack_fk foreign key (pack_id) references pack_definition (pack_id),
    constraint pack_rule_target_type_fk foreign key (pack_id, target_type) references pack_entity_type (pack_id, name),
    constraint pack_rule_not_blank_ck check (rule_no <> '' and label <> '' and action_text <> ''),
    constraint pack_rule_priority_ck check (priority >= 1),
    constraint pack_rule_due_ck check (due_in_days >= 0),
    constraint pack_rule_kind_ck check (on_added or on_changed or on_removed),
    constraint pack_rule_applies_to_ck check (applies_to in ('CHANGE_TARGET', 'IMPACT_TARGET'))
);

-- 규칙의 조건. 유형을 보는 조건이거나 속성 값을 보는 조건이다.
create table pack_rule_condition (
    pack_id        text collate "C" not null,
    rule_no        text collate "C" not null,
    position       integer          not null,
    subject        text collate "C" not null,
    type_name      text collate "C",
    attribute_name text collate "C",
    operator       text collate "C",
    constraint pack_rule_condition_pk primary key (pack_id, rule_no, position),
    constraint pack_rule_condition_rule_fk foreign key (pack_id, rule_no) references pack_rule (pack_id, rule_no),
    constraint pack_rule_condition_type_fk foreign key (pack_id, type_name)
        references pack_entity_type (pack_id, name),
    constraint pack_rule_condition_attribute_fk foreign key (pack_id, attribute_name)
        references pack_attribute (pack_id, name),
    constraint pack_rule_condition_position_ck check (position >= 1),
    constraint pack_rule_condition_subject_ck check (subject in ('CHANGE_TARGET', 'IMPACT_TARGET')),
    constraint pack_rule_condition_operator_ck check (operator is null or operator in (
        'EQUALS', 'NOT_EQUALS', 'GREATER_THAN', 'GREATER_THAN_OR_EQUAL', 'LESS_THAN', 'LESS_THAN_OR_EQUAL',
        'IN', 'HAS_VALUE', 'HAS_NO_VALUE')),
    -- 유형을 보는 조건에는 유형만, 속성을 보는 조건에는 속성과 비교만 있다.
    constraint pack_rule_condition_shape_ck check (
        (type_name is not null and attribute_name is null and operator is null)
        or (type_name is null and attribute_name is not null and operator is not null))
);

-- 조건이 견주는 값. 속성의 형식에 맞는 정해진 표기다. 값의 글자 코드 순으로 번호를 매긴다.
create table pack_rule_condition_value (
    pack_id        text collate "C" not null,
    rule_no        text collate "C" not null,
    position       integer          not null,
    value_position integer          not null,
    value          text collate "C" not null,
    constraint pack_rule_condition_value_pk primary key (pack_id, rule_no, position, value_position),
    constraint pack_rule_condition_value_condition_fk foreign key (pack_id, rule_no, position)
        references pack_rule_condition (pack_id, rule_no, position),
    constraint pack_rule_condition_value_position_ck check (value_position >= 1),
    constraint pack_rule_condition_value_not_blank_ck check (value <> '')
);
