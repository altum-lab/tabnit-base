-- link 의 판 비교 (D5 5절, D2 AD-194 ~ AD-199).
-- 1) 판 비교에 쓰는 기록을 항목과 항목이 만든 것에 더한다.
-- 2) 변동 이벤트와 변동 대상의 테이블을 만든다.
--
-- 새 열은 값이 반드시 있어야 한다. 이 변경 파일은 판이 하나도 없는 데이터베이스를 전제로 한다 (D2 AD-197).
-- 출시 전이라 V7 로 만든 판이 있는 설치는 없다. 개발용 데이터베이스에 판이 들어 있으면 이 파일이 실패하므로
-- 그 데이터베이스는 다시 만든다.

-- 항목의 주 개체. 다음 판에서 그 행이 없어졌을 때 삭제 변동의 주 개체가 된다 (D2 AD-120).
alter table edition_item
    add column primary_entity_id text collate "C" not null,
    add constraint edition_item_primary_ck check (primary_entity_id <> '');

-- 만든 것마다: 무엇에 대한 것인가(subject), 행이 적은 것의 해시(fact_hash), 저장된 유효기간.
-- 다음 판은 같은 것을 적은 행이 있으면 이 기록의 것을 그대로 잇고, 없으면 끝낸다 (D2 AD-195).
alter table edition_item_output
    -- 개체는 (E, 유형, 키), 속성 값은 (A, 유형, 키, 속성 이름), 관계는 (R, 출발의 유형과 키, 도착의 유형과 키, 관계 유형)를
    -- 식별자와 같은 규칙으로 이은 글자.
    add column subject    text collate "C" not null,
    -- 행이 적은 것 한 줄(D5 5절 "항목의 내용 해시를 만드는 법"의 줄)의 UTF-8 바이트의 SHA-256.
    add column fact_hash  text collate "C" not null,
    -- 저장된 유효기간. 개체에는 없다. valid_to 는 만들 때의 종료일이다 (뒤의 판이 끝낸 것은 그 판의 항목에 적히지 않는다).
    add column valid_from date,
    add column valid_to   date,
    add constraint edition_item_output_subject_ck check (subject <> ''),
    add constraint edition_item_output_fact_hash_ck check (fact_hash ~ '^[0-9a-f]{64}$'),
    add constraint edition_item_output_period_ck check (
        (kind = 'ENTITY' and valid_from is null and valid_to is null)
        or (kind <> 'ENTITY' and valid_from is not null and (valid_to is null or valid_to > valid_from)));

-- 알림의 종류에 "적힌 유효기간과 다르게 처리했다"를 더한다 (D2 AD-199).
alter table edition_notice
    drop constraint edition_notice_kind_ck,
    add constraint edition_notice_kind_ck check (
        kind in ('SKIPPED_ROW', 'REFERENCE_ONLY_ENTITY', 'ATTRIBUTE_CONFLICT', 'RELATION_CONFLICT', 'PERIOD_ADJUSTED'));

-- 변동 이벤트: 바뀐 항목 하나 (D3 6절, D2 AD-44, AD-154).
create table change_event (
    -- (판, 항목의 키)를 이은 글자.
    id                text collate "C" not null,
    edition_id        text collate "C" not null,
    item_key          text collate "C" not null,
    change_kind       text collate "C" not null,
    -- 그 행의 주 개체. 뒤 단계가 조치의 근거로 쓴다. 삭제된 행은 앞 판의 그 행의 주 개체다 (D2 AD-120).
    primary_entity_id text collate "C" not null,
    status            text collate "C" not null,
    failed_stage      text collate "C",
    failure_code      text collate "C",
    constraint change_event_pk primary key (id),
    constraint change_event_item_uq unique (edition_id, item_key),
    constraint change_event_edition_fk foreign key (edition_id) references edition (id),
    constraint change_event_kind_ck check (change_kind in ('ADDED', 'CHANGED', 'REMOVED')),
    constraint change_event_status_ck check (status in ('PENDING', 'PROCESSED', 'FAILED')),
    constraint change_event_stage_ck check (failed_stage is null or failed_stage in ('IMPACT', 'RULES')),
    -- 실패했을 때만 어느 단계인지와 이유가 있다.
    constraint change_event_failure_ck check (
        (status = 'FAILED' and failed_stage is not null and failure_code is not null and failure_code <> '')
        or (status <> 'FAILED' and failed_stage is null and failure_code is null)),
    constraint change_event_not_blank_ck check (id <> '' and primary_entity_id <> '')
);

-- 변동 대상: 바뀐 항목이 만든 모든 개체 (D2 AD-43, AD-58).
create table change_target (
    change_event_id text collate "C" not null,
    -- core 의 개체의 식별자. 값으로만 담는다.
    entity_id       text collate "C" not null,
    constraint change_target_pk primary key (change_event_id, entity_id),
    constraint change_target_event_fk foreign key (change_event_id) references change_event (id),
    constraint change_target_not_blank_ck check (entity_id <> '')
);
