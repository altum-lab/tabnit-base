-- 스키마 v0: core 의 테이블 (D5 3절). 개체, 속성 값, 관계.
-- 주인 모듈은 core 다. 다른 모듈은 core 의 공개 API 로만 읽고 쓴다 (D2 AD-18, AD-46).
-- pack, link, rules, common 의 테이블은 여기에 넣지 않는다 (D2 AD-73).

-- 유효기간 겹침을 배제 제약으로 막으려면 필요하다 (D2 AD-67).
create extension if not exists btree_gist;

-- 개체 (D5 3절 entity)
-- 식별자와 유형 이름은 글자 코드 순으로 비교한다. 설치한 곳의 언어 설정에 순서가 달라지지 않도록
-- 정렬 규칙을 "C" 로 둔다 (D5 1절 순서).
create table entity (
    id          text collate "C" not null,
    pack_id     text collate "C" not null,
    entity_type text collate "C" not null,
    entity_key  text collate "C" not null,
    -- 기록한 때는 밖에서 받아 넘긴다. 기본값을 두지 않는다 (D2 AD-31).
    recorded_at timestamptz      not null,
    constraint entity_pk primary key (id),
    constraint entity_key_uq unique (pack_id, entity_type, entity_key),
    -- 관계와 속성 값이 같은 Pack 의 개체만 가리키게 하는 참조의 대상 (D2 AD-74).
    constraint entity_pack_id_uq unique (pack_id, id)
);

-- 속성 값 (D5 3절 attribute_value)
create table attribute_value (
    id              text collate "C" not null,
    pack_id         text collate "C" not null,
    entity_id       text collate "C" not null,
    attribute_name  text collate "C" not null,
    value           text             not null,
    -- 유효기간: 시작일 포함, 종료일 제외. 종료일이 없으면 계속 유효하다 (D2 AD-22, AD-39).
    valid_from      date             not null,
    valid_to        date,
    -- 출처와 기록한 때 (D2 AD-54)
    input_source_id text collate "C" not null,
    edition_no      integer          not null,
    recorded_at     timestamptz      not null,
    constraint attribute_value_pk primary key (id),
    constraint attribute_value_key_uq unique (entity_id, attribute_name, valid_from),
    constraint attribute_value_entity_fk foreign key (pack_id, entity_id) references entity (pack_id, id),
    constraint attribute_value_period_ck check (valid_to is null or valid_to > valid_from),
    -- 같은 개체의 같은 속성은 유효기간이 겹칠 수 없다. 출처는 보지 않는다 (D2 AD-53).
    constraint attribute_value_no_overlap exclude using gist (
        entity_id with =,
        attribute_name with =,
        daterange(valid_from, valid_to, '[)') with &&
    )
);

-- 관계 (D5 3절 relation)
create table relation (
    id               text collate "C" not null,
    pack_id          text collate "C" not null,
    -- 관계에는 방향이 있다: 출발 개체에서 도착 개체로 (D2 AD-29).
    source_entity_id text collate "C" not null,
    target_entity_id text collate "C" not null,
    relation_type    text collate "C" not null,
    valid_from       date             not null,
    valid_to         date,
    -- 출처(입력 자료와 판)와 기록한 때 (D2 AD-30, AD-31)
    input_source_id  text collate "C" not null,
    edition_no       integer          not null,
    recorded_at      timestamptz      not null,
    constraint relation_pk primary key (id),
    constraint relation_key_uq unique (source_entity_id, target_entity_id, relation_type,
                                       input_source_id, edition_no, valid_from),
    -- 없는 개체나 다른 Pack 의 개체를 가리키는 관계는 거부한다 (D2 AD-74).
    constraint relation_source_fk foreign key (pack_id, source_entity_id) references entity (pack_id, id),
    constraint relation_target_fk foreign key (pack_id, target_entity_id) references entity (pack_id, id),
    -- 출발과 도착이 같은 관계는 거부한다 (D2 AD-68).
    constraint relation_not_self_ck check (source_entity_id <> target_entity_id),
    constraint relation_period_ck check (valid_to is null or valid_to > valid_from),
    -- 같은 관계(출발, 도착, 유형, 입력 자료, 판)는 유효기간이 겹칠 수 없다 (D2 AD-30).
    -- 맞닿는 것(앞의 종료일 = 뒤의 시작일)은 겹침이 아니다.
    constraint relation_no_overlap exclude using gist (
        source_entity_id with =,
        target_entity_id with =,
        relation_type with =,
        input_source_id with =,
        edition_no with =,
        daterange(valid_from, valid_to, '[)') with &&
    )
);
