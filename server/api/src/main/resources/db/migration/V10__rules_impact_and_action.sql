-- rules 의 첫 테이블: 저장된 영향 범위와 조치 (D5 6절, D2 AD-209 ~ AD-212, AD-225 ~ AD-228).
--
-- 영향 범위와 조치는 트랜잭션 하나로 함께 저장된다. 한 번 저장한 것은 고치지 않는다.
-- rules 는 다른 모듈의 테이블을 가리키지 않는다: 변동 이벤트(link)와 개체·관계(core)의 식별자는 값으로 담는다 (D2 AD-18).
-- 식별자는 길 수 있어 유일 제약에 그대로 넣지 않는다. 그 UTF-8 바이트의 SHA-256 을 열로 두고 키로 쓴다 (D2 AD-187 과 같은 방법).

-- 영향 범위: 변동 이벤트마다 하나. 영향 대상이 없어도, 규칙에 걸린 것이 없어도 한 행이 있다.
create table impact_scope (
    change_event_hash text collate "C" not null,
    change_event_id   text collate "C" not null,
    pack_id           text collate "C" not null,
    -- 계산에 쓴 기준 날짜 둘: 판의 적용일 전날과 적용일 (D2 AD-100).
    as_of_previous    date not null,
    as_of_effective   date not null,
    max_depth         integer not null,
    -- 최대 깊이에서 멈췄고 그 너머에 아직 찾지 못한 대상이 있는가.
    truncated         boolean not null,
    -- 저장한 때. 밖에서 받아 넘긴 시각. 계산은 core 가 그 전에 한다.
    stored_at         timestamptz not null,
    constraint impact_scope_pk primary key (change_event_hash),
    constraint impact_scope_hash_ck check (change_event_hash ~ '^[0-9a-f]{64}$'),
    constraint impact_scope_not_blank_ck check (change_event_id <> '' and pack_id <> ''),
    constraint impact_scope_dates_ck check (as_of_previous = as_of_effective - 1),
    constraint impact_scope_depth_ck check (max_depth >= 1)
);

-- 영향 대상. 변동 대상 자신은 넣지 않는다 (D2 AD-34).
create table impact_target (
    change_event_hash    text collate "C" not null,
    entity_hash          text collate "C" not null,
    entity_id            text collate "C" not null,
    entity_type          text collate "C" not null,
    depth                integer not null,
    -- 근거 경로가 시작한 변동 대상과, 그 경로의 관계가 모두 유효한 기준 날짜 (D2 AD-64, AD-116).
    path_start_entity_id text collate "C" not null,
    path_as_of           date not null,
    constraint impact_target_pk primary key (change_event_hash, entity_hash),
    constraint impact_target_scope_fk foreign key (change_event_hash) references impact_scope (change_event_hash),
    constraint impact_target_hash_ck check (entity_hash ~ '^[0-9a-f]{64}$'),
    constraint impact_target_not_blank_ck check (entity_id <> '' and entity_type <> '' and path_start_entity_id <> ''),
    constraint impact_target_depth_ck check (depth >= 1)
);

-- 근거 경로의 단계. 변동 대상 쪽부터 1, 2, ... 관계의 내용을 값으로 적어 둔다 (D2 AD-212).
create table impact_path_step (
    change_event_hash text collate "C" not null,
    entity_hash       text collate "C" not null,
    step_no           integer not null,
    relation_id       text collate "C" not null,
    relation_type     text collate "C" not null,
    source_entity_id  text collate "C" not null,
    target_entity_id  text collate "C" not null,
    constraint impact_path_step_pk primary key (change_event_hash, entity_hash, step_no),
    constraint impact_path_step_target_fk foreign key (change_event_hash, entity_hash)
        references impact_target (change_event_hash, entity_hash),
    constraint impact_path_step_no_ck check (step_no >= 1),
    constraint impact_path_step_not_blank_ck check (
        relation_id <> '' and relation_type <> '' and source_entity_id <> '' and target_entity_id <> '')
);

-- 조치. (변동 이벤트, 규칙, 대상)이 같으면 같은 조치다 (D2 AD-60).
create table action (
    change_event_hash     text collate "C" not null,
    rule_no               text collate "C" not null,
    target_entity_hash    text collate "C" not null,
    -- (변동 이벤트의 식별자, 규칙 번호, 대상 개체의 식별자)를 식별자의 규칙으로 이은 글자 (D2 AD-65).
    id                    text collate "C" not null,
    -- 그 식별자의 해시. 식별자 하나로 조치를 찾고 가리킬 때 쓴다 (D2 AD-230).
    id_hash               text collate "C" not null,
    -- 대상이 영향 대상이면 저장된 영향 대상을 가리킨다 (target_entity_hash 와 같은 값). 변동 대상이면 비어 있다 (D2 AD-211).
    impact_entity_hash    text collate "C",
    pack_id               text collate "C" not null,
    -- 만들 때의 규칙의 내용. 뒤에 규칙을 고치거나 빼도 조치는 이것을 보여 준다 (D2 AD-226).
    rule_label            text not null,
    action_text           text not null,
    priority              integer not null,
    -- 대상이 영향 대상인가 변동 대상인가. 변동 대상이면 근거 경로가 없다 (D2 AD-102).
    applies_to            text collate "C" not null,
    target_entity_id      text collate "C" not null,
    target_entity_type    text collate "C" not null,
    -- 근거: 변동이 생긴 행의 주 개체와, 그 변동이 나온 입력 자료의 판 (D2 AD-120, AD-98). 반드시 있다.
    basis_entity_id       text collate "C" not null,
    basis_input_source_id text collate "C" not null,
    basis_edition_no      integer not null,
    -- 그 판의 적용일과, 거기에 규칙이 적은 일수를 더한 기한 (D2 AD-99).
    as_of                 date not null,
    due_date              date not null,
    status                text collate "C" not null,
    -- 만든 때. 밖에서 받아 넘긴 시각.
    created_at            timestamptz not null,
    constraint action_pk primary key (change_event_hash, rule_no, target_entity_hash),
    constraint action_id_hash_uq unique (id_hash),
    constraint action_scope_fk foreign key (change_event_hash) references impact_scope (change_event_hash),
    constraint action_impact_target_fk foreign key (change_event_hash, impact_entity_hash)
        references impact_target (change_event_hash, entity_hash),
    constraint action_impact_target_ck check (
        (applies_to = 'IMPACT_TARGET' and impact_entity_hash is not null and impact_entity_hash = target_entity_hash)
        or (applies_to = 'CHANGE_TARGET' and impact_entity_hash is null)),
    constraint action_hash_ck check (target_entity_hash ~ '^[0-9a-f]{64}$' and id_hash ~ '^[0-9a-f]{64}$'),
    constraint action_not_blank_ck check (
        id <> '' and pack_id <> '' and rule_no <> '' and target_entity_id <> '' and target_entity_type <> ''
        and basis_entity_id <> '' and basis_input_source_id <> '' and rule_label <> '' and action_text <> ''),
    constraint action_priority_ck check (priority >= 1),
    constraint action_applies_to_ck check (applies_to in ('IMPACT_TARGET', 'CHANGE_TARGET')),
    constraint action_edition_ck check (basis_edition_no >= 1),
    constraint action_due_ck check (due_date >= as_of),
    constraint action_status_ck check (status in ('PENDING', 'CONFIRMED', 'ON_HOLD'))
);
