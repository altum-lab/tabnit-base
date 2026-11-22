-- C-08: 처리에 실패한 변동의 까닭을 남긴다 (D2 AD-321, AD-345, D5 5절).

-- ── link: 평가에 실패한 규칙과 그 까닭. 변동 이벤트가 실패(FAILED)이고 규칙이 평가에 실패했을 때만 행이 있다.
-- 다시 실패로 표시하면 새것으로 바뀌고, 처리됨으로 표시하면 없어진다: 지금의 상태의 까닭이다.
-- 그 밖의 실패(저장소 오류 등)는 change_event 의 단계와 코드뿐이다. 오류의 문구는 저장하지 않는다.
create table change_event_failure (
    change_event_id text collate "C" not null,
    -- 넘겨받은 차례. 0 부터다.
    seq             integer not null,
    rule_no         text collate "C" not null,
    code            text collate "C" not null,
    -- 걸린 개체, 속성, 값. 없을 수 있다.
    entity_id       text collate "C",
    attribute_name  text collate "C",
    value           text collate "C",
    constraint change_event_failure_pk primary key (change_event_id, seq),
    constraint change_event_failure_event_fk foreign key (change_event_id) references change_event (id),
    constraint change_event_failure_seq_ck check (seq >= 0),
    constraint change_event_failure_not_blank_ck check (rule_no <> '' and code <> '')
);
