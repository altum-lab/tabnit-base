-- 규칙이 볼 수 있는 것을 넓힌다 (D2 AD-289 ~ AD-292, D8 2절·3절, D5 4절·5절).
--
-- pack: 규칙이 걸리는 입력 자료, 규칙이 걸리는 바뀐 속성과 관계 유형, 조건이 값을 견주는 상대(고정 값, 판의 적용일, 다른 대상의 속성).
-- link: 변동 이벤트마다 그 행에서 바뀐 속성과 관계 유형.
-- 이미 올라간 변경 파일(V8, V9)은 고치지 않고 여기서 더한다.

-- ── pack: 규칙이 걸리는 입력 자료 (D2 AD-290). 행이 없는 규칙은 모든 입력 자료에 걸린다.
create table pack_rule_input_source (
    pack_id         text collate "C" not null,
    rule_no         text collate "C" not null,
    input_source_id text collate "C" not null,
    constraint pack_rule_input_source_pk primary key (pack_id, rule_no, input_source_id),
    constraint pack_rule_input_source_rule_fk foreign key (pack_id, rule_no) references pack_rule (pack_id, rule_no),
    -- 그 Pack 의 매핑이 받는 입력 자료여야 한다.
    constraint pack_rule_input_source_mapping_fk foreign key (pack_id, input_source_id)
        references pack_mapping (pack_id, input_source_id)
);

-- ── pack: 규칙이 걸리는 바뀐 것 (D2 AD-291). 두 테이블 모두에 행이 없는 규칙은 무엇이 바뀌든 걸린다.
create table pack_rule_changed_attribute (
    pack_id        text collate "C" not null,
    rule_no        text collate "C" not null,
    attribute_name text collate "C" not null,
    constraint pack_rule_changed_attribute_pk primary key (pack_id, rule_no, attribute_name),
    constraint pack_rule_changed_attribute_rule_fk foreign key (pack_id, rule_no) references pack_rule (pack_id, rule_no),
    constraint pack_rule_changed_attribute_attribute_fk foreign key (pack_id, attribute_name)
        references pack_attribute (pack_id, name)
);

create table pack_rule_changed_relation_type (
    pack_id       text collate "C" not null,
    rule_no       text collate "C" not null,
    relation_type text collate "C" not null,
    constraint pack_rule_changed_relation_type_pk primary key (pack_id, rule_no, relation_type),
    constraint pack_rule_changed_relation_type_rule_fk foreign key (pack_id, rule_no)
        references pack_rule (pack_id, rule_no),
    constraint pack_rule_changed_relation_type_type_fk foreign key (pack_id, relation_type)
        references pack_relation_type (pack_id, name)
);

-- ── pack: 조건이 값을 견주는 상대 (D2 AD-292).
--   VALUE          규칙에 적은 고정 값 (pack_rule_condition_value). 지금까지의 조건은 모두 이것이다
--   EFFECTIVE_DATE 그 변동이 나온 판의 적용일
--   ATTRIBUTE      다른 대상(compare_subject)의 속성(compare_attribute_name)의 값
alter table pack_rule_condition
    add column compare_to             text collate "C" not null default 'VALUE',
    add column compare_subject        text collate "C",
    add column compare_attribute_name text collate "C",
    add constraint pack_rule_condition_compare_to_ck check (compare_to in ('VALUE', 'EFFECTIVE_DATE', 'ATTRIBUTE')),
    add constraint pack_rule_condition_compare_subject_ck
        check (compare_subject is null or compare_subject in ('CHANGE_TARGET', 'IMPACT_TARGET')),
    add constraint pack_rule_condition_compare_attribute_fk foreign key (pack_id, compare_attribute_name)
        references pack_attribute (pack_id, name),
    -- 다른 대상의 속성과 견줄 때만 그 대상과 속성을 적는다. 고정 값이 아닌 것과 견주는 조건은 속성을 보는 조건이다.
    add constraint pack_rule_condition_compare_shape_ck check (
        (compare_to = 'ATTRIBUTE' and compare_subject is not null and compare_attribute_name is not null
            and attribute_name is not null)
        or (compare_to = 'EFFECTIVE_DATE' and compare_subject is null and compare_attribute_name is null
            and attribute_name is not null)
        or (compare_to = 'VALUE' and compare_subject is null and compare_attribute_name is null));

-- ── link: 변동 이벤트의 행에서 바뀐 속성과 관계 유형 (D2 AD-291).
-- 추가된 행은 그 행이 넣은 것 전부, 삭제된 행은 그 행이 넣었던 것 전부다.
-- 이 변경 파일보다 앞서 만들어진 변동 이벤트에는 행이 없다: 그 이벤트가 무엇을 바꿨는지는 알 수 없다.
create table change_event_changed_item (
    change_event_id text collate "C" not null,
    -- ATTRIBUTE 속성, RELATION_TYPE 관계 유형
    item_kind       text collate "C" not null,
    -- 속성이나 관계 유형의 이름
    name            text collate "C" not null,
    constraint change_event_changed_item_pk primary key (change_event_id, item_kind, name),
    constraint change_event_changed_item_event_fk foreign key (change_event_id) references change_event (id),
    constraint change_event_changed_item_kind_ck check (item_kind in ('ATTRIBUTE', 'RELATION_TYPE')),
    constraint change_event_changed_item_name_ck check (name <> '')
);

-- 바뀐 것을 남긴 변동 이벤트인가. 앞서 만들어진 이벤트는 거짓이다: 규칙이 바뀐 것을 물으면 답할 수 없다.
alter table change_event
    add column changed_items_recorded boolean not null default false;
