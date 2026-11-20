-- pack 의 테이블: 매핑 정의 (D5 4절, D6 7절, D2 AD-141).
-- 매핑은 입력 자료 하나에 하나다. Turtle 에서 차례가 없는 부분(행이 만드는 개체, 관계)은 내용을 키로 삼는다.
-- "속성 하나는 매핑 하나만 넣는다"(D2 AD-137)는 pack 의 검증이 지킨다.

create table pack_mapping (
    pack_id             text collate "C" not null,
    -- 입력 자료의 식별자. 출처의 input_source_id 가 된다 (D2 AD-41).
    input_source_id     text collate "C" not null,
    file_format         text collate "C" not null,
    -- 엑셀일 때 읽을 시트. CSV 이면 없다.
    sheet_name          text,
    -- 행의 주 개체 (D2 AD-120).
    primary_entity_type text collate "C" not null,
    primary_key_column  text             not null,
    constraint pack_mapping_pk primary key (pack_id, input_source_id),
    constraint pack_mapping_pack_fk foreign key (pack_id) references pack_definition (pack_id),
    constraint pack_mapping_primary_type_fk foreign key (pack_id, primary_entity_type)
        references pack_entity_type (pack_id, name),
    constraint pack_mapping_not_blank_ck check (input_source_id <> '' and primary_key_column <> ''),
    constraint pack_mapping_file_format_ck check (file_format in ('CSV', 'XLSX')),
    constraint pack_mapping_sheet_ck check ((file_format = 'XLSX') = (sheet_name is not null and sheet_name <> ''))
);

-- 차례가 있는 열 목록: 반드시 있어야 하는 열, 항목의 키 열 (D2 AD-57, AD-122).
create table pack_mapping_column (
    pack_id         text collate "C" not null,
    input_source_id text collate "C" not null,
    kind            text collate "C" not null,
    position        integer          not null,
    column_name     text             not null,
    constraint pack_mapping_column_pk primary key (pack_id, input_source_id, kind, position),
    constraint pack_mapping_column_mapping_fk foreign key (pack_id, input_source_id)
        references pack_mapping (pack_id, input_source_id),
    constraint pack_mapping_column_kind_ck check (kind in ('REQUIRED', 'ITEM_KEY')),
    constraint pack_mapping_column_position_ck check (position >= 1),
    constraint pack_mapping_column_not_blank_ck check (column_name <> ''),
    constraint pack_mapping_column_name_uq unique (pack_id, input_source_id, kind, column_name)
);

-- 행이 만드는 개체.
create table pack_mapping_entity (
    pack_id         text collate "C" not null,
    input_source_id text collate "C" not null,
    entity_type     text collate "C" not null,
    key_column      text collate "C" not null,
    constraint pack_mapping_entity_pk primary key (pack_id, input_source_id, entity_type, key_column),
    constraint pack_mapping_entity_mapping_fk foreign key (pack_id, input_source_id)
        references pack_mapping (pack_id, input_source_id),
    constraint pack_mapping_entity_type_fk foreign key (pack_id, entity_type)
        references pack_entity_type (pack_id, name),
    constraint pack_mapping_entity_not_blank_ck check (key_column <> '')
);

-- 개체의 속성 값. 값을 읽는 법은 열마다 적는다 (D2 AD-94).
create table pack_mapping_attribute (
    pack_id            text collate "C" not null,
    input_source_id    text collate "C" not null,
    entity_type        text collate "C" not null,
    key_column         text collate "C" not null,
    attribute_name     text collate "C" not null,
    column_name        text             not null,
    valid_from_column  text,
    valid_to_column    text,
    date_pattern       text,
    grouping_separator text,
    true_value         text,
    false_value        text,
    constraint pack_mapping_attribute_pk
        primary key (pack_id, input_source_id, entity_type, key_column, attribute_name),
    constraint pack_mapping_attribute_entity_fk foreign key (pack_id, input_source_id, entity_type, key_column)
        references pack_mapping_entity (pack_id, input_source_id, entity_type, key_column),
    constraint pack_mapping_attribute_attribute_fk foreign key (pack_id, attribute_name)
        references pack_attribute (pack_id, name),
    constraint pack_mapping_attribute_not_blank_ck check (column_name <> '')
);

-- 행이 만드는 관계. 출발 개체와 도착 개체는 유형과 키 열로 가리킨다.
create table pack_mapping_relation (
    pack_id            text collate "C" not null,
    input_source_id    text collate "C" not null,
    relation_type      text collate "C" not null,
    source_entity_type text collate "C" not null,
    source_key_column  text collate "C" not null,
    target_entity_type text collate "C" not null,
    target_key_column  text collate "C" not null,
    valid_from_column  text,
    valid_to_column    text,
    constraint pack_mapping_relation_pk primary key (pack_id, input_source_id, relation_type,
                                                     source_entity_type, source_key_column,
                                                     target_entity_type, target_key_column),
    constraint pack_mapping_relation_mapping_fk foreign key (pack_id, input_source_id)
        references pack_mapping (pack_id, input_source_id),
    constraint pack_mapping_relation_type_fk foreign key (pack_id, relation_type)
        references pack_relation_type (pack_id, name),
    constraint pack_mapping_relation_source_fk foreign key (pack_id, source_entity_type)
        references pack_entity_type (pack_id, name),
    constraint pack_mapping_relation_target_fk foreign key (pack_id, target_entity_type)
        references pack_entity_type (pack_id, name),
    constraint pack_mapping_relation_not_blank_ck check (source_key_column <> '' and target_key_column <> '')
);
