-- pack 의 테이블: Pack 의 정의 (D5 4절, D6 4절 ~ 5절). Pack 자신, 개체 유형, 상위 유형, 관계 유형, 속성.
-- 주인 모듈은 pack 이다. 다른 모듈은 pack 의 공개 API 로만 읽는다 (D2 AD-18, AD-46).
-- 매핑, 규칙, 검증 질문의 테이블은 그 작업에서 더한다 (D2 AD-125).
-- 이름은 글자 코드 순으로 비교한다. 정렬 규칙을 "C" 로 둔다 (D5 1절 순서).

-- Pack 자신의 정보 (D6 4절)
create table pack_definition (
    pack_id           text collate "C" not null,
    label             text             not null,
    -- 영향 범위의 최대 깊이 기본값. 1 이상 (D2 AD-35).
    default_max_depth integer          not null,
    -- 적재한 때는 밖에서 받아 넘긴다. 기본값을 두지 않는다.
    loaded_at         timestamptz      not null,
    constraint pack_definition_pk primary key (pack_id),
    constraint pack_definition_not_blank_ck check (pack_id <> '' and label <> ''),
    constraint pack_definition_max_depth_ck check (default_max_depth >= 1)
);

-- 개체 유형 (D6 5절)
create table pack_entity_type (
    pack_id                text collate "C" not null,
    name                   text collate "C" not null,
    label                  text             not null,
    -- 표시 이름으로 쓸 속성. 유형이 스스로 적은 값이다. 없을 수 있다 (D2 AD-70).
    display_name_attribute text collate "C",
    constraint pack_entity_type_pk primary key (pack_id, name),
    constraint pack_entity_type_pack_fk foreign key (pack_id) references pack_definition (pack_id),
    constraint pack_entity_type_not_blank_ck check (name <> '' and label <> '')
);

-- 상위 유형. 여럿일 수 있다 (D2 AD-88).
create table pack_entity_type_parent (
    pack_id     text collate "C" not null,
    name        text collate "C" not null,
    parent_name text collate "C" not null,
    constraint pack_entity_type_parent_pk primary key (pack_id, name, parent_name),
    constraint pack_entity_type_parent_child_fk foreign key (pack_id, name)
        references pack_entity_type (pack_id, name),
    constraint pack_entity_type_parent_parent_fk foreign key (pack_id, parent_name)
        references pack_entity_type (pack_id, name),
    constraint pack_entity_type_parent_not_self_ck check (name <> parent_name)
);

-- 속성 (D6 5절)
create table pack_attribute (
    pack_id      text collate "C" not null,
    name         text collate "C" not null,
    label        text             not null,
    -- 이 속성을 갖는 개체 유형. 그 하위 유형도 갖는다.
    entity_type  text collate "C" not null,
    -- 값의 형식 (D2 AD-71).
    value_format text collate "C" not null,
    constraint pack_attribute_pk primary key (pack_id, name),
    constraint pack_attribute_entity_type_fk foreign key (pack_id, entity_type)
        references pack_entity_type (pack_id, name),
    constraint pack_attribute_not_blank_ck check (name <> '' and label <> ''),
    constraint pack_attribute_value_format_ck
        check (value_format in ('STRING', 'INTEGER', 'DECIMAL', 'DATE', 'BOOLEAN'))
);

-- 개체 유형과 속성은 서로를 가리킨다. 한 번의 적재 안에서 차례와 무관하게 넣을 수 있도록 끝에서 확인한다.
alter table pack_entity_type
    add constraint pack_entity_type_display_name_fk foreign key (pack_id, display_name_attribute)
        references pack_attribute (pack_id, name) deferrable initially deferred;

-- 관계 유형 (D6 5절)
create table pack_relation_type (
    pack_id          text collate "C" not null,
    name             text collate "C" not null,
    label            text             not null,
    -- 출발 유형과 도착 유형은 하나씩이다 (D2 AD-91).
    source_type      text collate "C" not null,
    target_type      text collate "C" not null,
    -- 영향이 퍼지는 방향 (D2 AD-33).
    impact_direction text collate "C" not null,
    constraint pack_relation_type_pk primary key (pack_id, name),
    constraint pack_relation_type_source_fk foreign key (pack_id, source_type)
        references pack_entity_type (pack_id, name),
    constraint pack_relation_type_target_fk foreign key (pack_id, target_type)
        references pack_entity_type (pack_id, name),
    constraint pack_relation_type_not_blank_ck check (name <> '' and label <> ''),
    constraint pack_relation_type_direction_ck
        check (impact_direction in ('SOURCE_TO_TARGET', 'TARGET_TO_SOURCE', 'BOTH', 'NONE'))
);
