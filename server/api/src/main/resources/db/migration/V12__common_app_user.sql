-- common 의 첫 테이블: 사용자 (D5 7절 app_user, D2 AD-262 ~ AD-266).
--
-- 비밀번호는 평문으로 저장하지 않는다. 해시만 저장하고, 어떤 방식의 해시인지를 값의 앞에 함께 적는다.
-- 다른 모듈은 이 테이블을 가리키지 않는다: 누가 했는지는 아이디를 값으로 담는다 (D2 AD-18, AD-246).

create table app_user (
    -- 아이디. 영어 소문자나 숫자로 시작하고 영어 소문자, 숫자, 점, 밑줄, 붙임표만 쓴다. 3자에서 50자.
    login_id      text collate "C" not null,
    -- 비밀번호의 해시. 앞에 {방식의 이름} 이 붙는다.
    password_hash text collate "C" not null,
    -- 역할 (D1: 관리자 ADMIN, 담당자 OFFICER).
    role          text collate "C" not null,
    -- 잇달아 틀린 횟수. 로그인에 성공하거나 막히면 0 으로 돌아간다.
    failed_count  integer not null default 0,
    -- 이때까지 로그인을 막는다. 막혀 있지 않으면 비어 있다.
    locked_until  timestamptz,
    -- 만든 때. 밖에서 받아 넘긴 시각.
    created_at    timestamptz not null,
    constraint app_user_pk primary key (login_id),
    constraint app_user_login_id_ck check (login_id ~ '^[a-z0-9][a-z0-9._-]{2,49}$'),
    constraint app_user_password_hash_ck check (password_hash ~ '^\{[a-zA-Z0-9@_-]+\}.+'),
    constraint app_user_role_ck check (role in ('ADMIN', 'OFFICER')),
    constraint app_user_failed_count_ck check (failed_count >= 0)
);
