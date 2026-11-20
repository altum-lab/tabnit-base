-- link 의 테이블: 판, 항목, 항목이 만든 것, 투입 기록, 알림 (D5 5절, D2 AD-148 ~ AD-158, AD-183).
-- 변동 이벤트와 변동 대상의 테이블은 판 비교(C-05)에서 만든다.
-- 다른 모듈의 테이블을 가리키지 않는다. Pack, 입력 자료, 개체·관계·속성 값의 식별자는 값으로만 담는다 (D2 AD-18, AD-160).

-- 판: 받아들여진 투입 하나 (D2 AD-41, AD-152). 원본을 함께 담는다 (D2 AD-150).
create table edition (
    id              text collate "C" not null,
    pack_id         text collate "C" not null,
    input_source_id text collate "C" not null,
    -- 그 입력 자료 안에서의 판 번호. 1 부터, 빈틈 없이 (D2 AD-75).
    edition_no      integer          not null,
    effective_date  date             not null,
    -- 받은 파일의 바이트의 SHA-256. 소문자 16진수 64자 (D2 AD-158).
    content_hash    text collate "C" not null,
    content         bytea            not null,
    -- 사람이 보기 위한 것이다. 처리에 쓰지 않는다.
    file_name       text,
    -- 밖에서 받아 넘긴 시각.
    received_at     timestamptz      not null,
    constraint edition_pk primary key (id),
    constraint edition_no_uq unique (pack_id, input_source_id, edition_no),
    constraint edition_no_ck check (edition_no >= 1),
    constraint edition_not_blank_ck check (id <> '' and pack_id <> '' and input_source_id <> ''),
    constraint edition_content_hash_ck check (content_hash ~ '^[0-9a-f]{64}$')
);

-- 판의 항목: 받아들인 행 하나 (D2 AD-57).
create table edition_item (
    edition_id   text collate "C" not null,
    -- 키 열의 값을 식별자와 같은 규칙으로 이은 글자 (D2 AD-122). 200자까지다. link 가 지킨다 (D2 AD-187).
    item_key     text collate "C" not null,
    row_no       integer          not null,
    -- 매핑이 쓰는 열의 읽은 값의 해시 (D2 AD-156).
    content_hash text collate "C" not null,
    constraint edition_item_pk primary key (edition_id, item_key),
    constraint edition_item_edition_fk foreign key (edition_id) references edition (id),
    constraint edition_item_row_no_ck check (row_no >= 2),
    constraint edition_item_content_hash_ck check (content_hash ~ '^[0-9a-f]{64}$')
);

-- 항목이 만든 것 (D2 AD-151).
create table edition_item_output (
    edition_id text collate "C" not null,
    item_key   text collate "C" not null,
    kind       text collate "C" not null,
    -- core 의 개체, 관계, 속성 값의 식별자. 값으로만 담는다.
    object_id  text collate "C" not null,
    -- object_id 의 UTF-8 바이트의 SHA-256. 식별자는 길 수 있어 색인에 넣지 않고 이것으로 유일을 지킨다 (D2 AD-187).
    object_hash text collate "C" not null,
    constraint edition_item_output_pk primary key (edition_id, item_key, kind, object_hash),
    constraint edition_item_output_hash_ck check (object_hash ~ '^[0-9a-f]{64}$'),
    constraint edition_item_output_item_fk foreign key (edition_id, item_key)
        references edition_item (edition_id, item_key),
    constraint edition_item_output_kind_ck check (kind in ('ENTITY', 'RELATION', 'ATTRIBUTE_VALUE')),
    constraint edition_item_output_not_blank_ck check (object_id <> '')
);

-- 투입 기록: 받아들여졌든 거부됐든 한 행씩 (D2 AD-152).
create table ingest_record (
    -- 입력의 키가 없는 테이블이라 데이터베이스가 매긴다 (D2 AD-37 의 예외). 결과의 순서를 정하는 데는 받은 때를 먼저 쓴다.
    id              bigint generated always as identity,
    pack_id         text collate "C" not null,
    input_source_id text collate "C" not null,
    effective_date  date             not null,
    content_hash    text collate "C" not null,
    file_name       text,
    received_at     timestamptz      not null,
    outcome         text collate "C" not null,
    -- 만든 판, 또는 내용이 같았던 앞 판.
    edition_id      text collate "C",
    reason_code     text collate "C",
    reason_detail   text,
    constraint ingest_record_pk primary key (id),
    constraint ingest_record_edition_fk foreign key (edition_id) references edition (id),
    constraint ingest_record_outcome_ck check (outcome in ('ACCEPTED', 'UNCHANGED', 'REJECTED')),
    constraint ingest_record_content_hash_ck check (content_hash ~ '^[0-9a-f]{64}$'),
    -- 받아들였거나 내용이 같았으면 판이 있고 이유가 없다. 거부했으면 판이 없고 이유가 있다.
    constraint ingest_record_outcome_shape_ck check (
        (outcome in ('ACCEPTED', 'UNCHANGED') and edition_id is not null and reason_code is null)
        or (outcome = 'REJECTED' and edition_id is null and reason_code is not null and reason_code <> ''))
);

-- 한 입력 자료의 투입 기록을 받은 때 순으로 읽는다.
create index ingest_record_source_ix on ingest_record (pack_id, input_source_id, received_at, id);

-- 투입의 알림 (D2 AD-153).
create table edition_notice (
    edition_id text collate "C" not null,
    -- 그 판 안에서의 차례. 1 부터. 행 번호, 종류, 코드, subject, detail 순으로 매긴다.
    notice_no  integer          not null,
    kind       text collate "C" not null,
    row_no     integer          not null,
    code       text collate "C" not null,
    subject    text collate "C",
    detail     text collate "C",
    constraint edition_notice_pk primary key (edition_id, notice_no),
    constraint edition_notice_edition_fk foreign key (edition_id) references edition (id),
    constraint edition_notice_no_ck check (notice_no >= 1),
    constraint edition_notice_kind_ck check (
        kind in ('SKIPPED_ROW', 'REFERENCE_ONLY_ENTITY', 'ATTRIBUTE_CONFLICT', 'RELATION_CONFLICT')),
    constraint edition_notice_code_ck check (code <> '')
);
