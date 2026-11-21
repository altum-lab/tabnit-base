-- common 의 감사 기록 (D5 7절 audit_log, D2 AD-05, AD-273 ~ AD-276).
--
-- 누가, 언제, 무엇을 조회하거나 바꿨는지와 그 결과를 남긴다. 더하기만 한다: 제품은 이 테이블의 행을 고치거나 지우지 않는다.
-- 요청 하나는 두 줄이다. 하기 전에 시작의 줄을 먼저 남기고 (남기지 못하면 하지 않는다), 끝나면 결과의 줄을 남긴다.
-- 한 줄의 식별자는 일련번호다. 입력의 키가 없다 (AD-37 의 예외, 투입 기록과 같다).

create table audit_log (
    id          bigint generated always as identity,
    -- 언제. 밖에서 받아 넘긴 시각.
    occurred_at timestamptz not null,
    -- 누가. 사용자의 아이디. 로그인 전이면 비어 있다. 사용자 테이블을 가리키지 않는다: 값으로 담는다.
    actor       text collate "C",
    -- 무엇을 했는가. 기능의 이름.
    action      text collate "C" not null,
    -- 무엇에 대해. 요청의 항목 가운데 대상을 가리키는 것들.
    target      text collate "C",
    -- 조회에 쓴 기준 날짜. 기준 날짜가 없는 요청이면 비어 있다.
    as_of       date,
    -- 결과. STARTED 하기 전에 남긴 줄, SUCCESS 성공, DENIED 거부 (권한 없음, 로그인 실패), FAILED 실패.
    outcome     text collate "C" not null,
    -- 응답의 HTTP 상태. 시작의 줄에는 없다.
    http_status integer,
    -- 그 밖의 내용.
    detail      text collate "C",
    -- 결과의 줄이 가리키는 시작의 줄.
    started_id  bigint,
    constraint audit_log_pk primary key (id),
    constraint audit_log_started_fk foreign key (started_id) references audit_log (id),
    constraint audit_log_outcome_ck check (outcome in ('STARTED', 'SUCCESS', 'DENIED', 'FAILED')),
    constraint audit_log_action_ck check (length(action) between 1 and 100 and btrim(action) <> ''),
    constraint audit_log_actor_ck check (actor is null or length(actor) between 1 and 200),
    constraint audit_log_target_ck check (target is null or length(target) <= 1000),
    constraint audit_log_detail_ck check (detail is null or length(detail) <= 4000),
    constraint audit_log_started_ck check (started_id is null or outcome <> 'STARTED')
);

-- 결과의 줄에서 시작의 줄을, 시작의 줄에서 결과의 줄을 찾는다.
create index audit_log_started_idx on audit_log (started_id) where started_id is not null;
