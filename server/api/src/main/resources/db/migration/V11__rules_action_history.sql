-- rules 의 조치의 이력 (D5 6절 action_history, D2 AD-47, AD-245 ~ AD-248).
--
-- 조치의 상태가 바뀔 때마다 한 줄을 더한다. 누가 언제 무엇에서 무엇으로 바꿨는지와 사유다.
-- 이력은 고치거나 지우지 않는다. 조치의 상태(action.status)와 이력은 트랜잭션 하나로 함께 바뀐다.
--
-- 제약으로 걸 수 있는 것은 여기서 건다 (D2 AD-249): 이력 한 줄의 모양, 첫 줄은 대기에서 시작한다, 조치마다 확정은
-- 한 번뿐이다, 아이디와 사유의 길이와 빈 값. 조치의 상태를 직접 되돌리거나 이력을 고치고 지우는 것은 제약으로
-- 막을 수 없다: rules 의 코드가 지킨다. 트리거는 쓰지 않는다.

create table action_history (
    -- 조치의 식별자의 해시. action 을 가리킨다 (D2 AD-230).
    action_id_hash text collate "C" not null,
    -- 그 조치 안에서의 차례. 1 부터.
    seq            integer not null,
    from_status    text collate "C" not null,
    to_status      text collate "C" not null,
    -- 누가 했는가. 사용자의 아이디를 값으로 담는다. 사용자의 테이블(common)을 가리키지 않는다 (D2 AD-18, AD-246).
    decided_by     text collate "C" not null,
    -- 언제 했는가. 밖에서 받아 넘긴 시각.
    decided_at     timestamptz not null,
    -- 사유. 보류에는 반드시 있다 (D2 AD-247).
    note           text,
    constraint action_history_pk primary key (action_id_hash, seq),
    constraint action_history_action_fk foreign key (action_id_hash) references action (id_hash),
    constraint action_history_hash_ck check (action_id_hash ~ '^[0-9a-f]{64}$'),
    constraint action_history_seq_ck check (seq >= 1),
    -- 첫 줄은 대기에서 시작한다. 조치는 대기로 만들어진다.
    constraint action_history_first_ck check (seq <> 1 or from_status = 'PENDING'),
    -- 대기에서 확정이나 보류로, 보류에서 확정으로만 간다. 확정은 되돌릴 수 없다 (D2 AD-47).
    constraint action_history_transition_ck check (
        (from_status = 'PENDING' and to_status in ('CONFIRMED', 'ON_HOLD'))
        or (from_status = 'ON_HOLD' and to_status = 'CONFIRMED')),
    -- 공백뿐인 값은 빈 것이다. 길이는 글자(코드 포인트)의 수로 센다 (D2 AD-247).
    constraint action_history_decided_by_ck check (decided_by !~ '^[[:space:]]*$' and char_length(decided_by) <= 200),
    constraint action_history_note_ck check (
        (note is null or (note !~ '^[[:space:]]*$' and char_length(note) <= 1000))
        and (to_status <> 'ON_HOLD' or note is not null))
);

-- 조치마다 확정은 한 번뿐이다. 확정된 조치를 다시 확정하는 이력을 데이터베이스가 받지 않는다 (D2 AD-47).
create unique index action_history_confirmed_uq on action_history (action_id_hash) where to_status = 'CONFIRMED';
