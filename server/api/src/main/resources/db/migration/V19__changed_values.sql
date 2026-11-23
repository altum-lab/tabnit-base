-- C-07: 변동 이벤트의 행에서 바뀐 속성의 이전 값과 새 값 (D2 AD-367, D5 5절).

-- ── link: 항목이 만든 속성 값의 값.
-- 다음 판에서 그 행의 값이 바뀌면 이것이 "이전 값"이 된다. 개체와 관계에는 없다.
-- 이 변경 파일보다 앞서 들어간 판의 것은 비어 있다: 그 값이 무엇이었는지 link 는 모른다.
alter table edition_item_output
    add column value text,
    add constraint edition_item_output_value_ck check (value is null or kind = 'ATTRIBUTE_VALUE');

-- ── link: 변동 이벤트의 행에서 바뀐 속성 값.
-- 변경된 행은 앞 판과 새 판에서 그 행이 만든 것이 다른 속성 값, 추가된 행은 넣은 것 전부, 삭제된 행은 넣었던 것 전부다
-- (change_event_changed_item 과 같은 기준이다. D2 AD-291).
create table change_event_changed_value (
    change_event_id text collate "C" not null,
    -- 변동 이벤트 안의 차례. 1 부터다. 개체의 식별자, 속성의 이름, 같은 속성 안의 차례 순으로 매긴다.
    seq             integer not null,
    entity_id       text collate "C" not null,
    attribute_name  text collate "C" not null,
    -- 앞 판에서 그 행이 넣었던 값. 없었으면 비어 있다.
    before_value    text,
    -- 새 판에서 그 행이 넣은 값. 없어졌으면 비어 있다.
    after_value     text,
    constraint change_event_changed_value_pk primary key (change_event_id, seq),
    constraint change_event_changed_value_event_fk foreign key (change_event_id) references change_event (id),
    constraint change_event_changed_value_seq_ck check (seq >= 1),
    constraint change_event_changed_value_entity_ck check (entity_id <> ''),
    constraint change_event_changed_value_name_ck check (attribute_name <> ''),
    constraint change_event_changed_value_some_ck check (before_value is not null or after_value is not null)
);

-- 바뀐 값을 남긴 변동 이벤트인가. 거짓이면 바뀐 값이 없는 것이 아니라 모르는 것이다:
-- 앞서 만들어진 이벤트이거나, 이전 값을 남기지 않은 판(이 변경 파일보다 앞선 판)과 견준 이벤트다.
alter table change_event
    add column changed_values_recorded boolean not null default false;
