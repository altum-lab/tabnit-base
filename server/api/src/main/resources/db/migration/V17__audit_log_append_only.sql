-- E-18: 감사 기록은 더하기만 한다. 고치거나 지우는 것을 데이터베이스가 막는다 (D2 AD-276, AD-329, D5 7절).
--
-- 제품은 이 테이블의 행을 고치거나 지우지 않는다. 데이터베이스에 직접 붙어 고치거나 지우려 해도 여기서 막힌다.
-- 막지 못하는 것: 테이블의 주인이나 데이터베이스의 관리자가 이 방아쇠를 끄거나 지우는 것. 그것은 데이터베이스의
-- 권한 관리와 설치 구성이 맡는다.

create function audit_log_refuse_change() returns trigger
    language plpgsql
as $$
begin
    raise exception '감사 기록(audit_log)은 고치거나 지울 수 없습니다. 더하기만 합니다.'
        using errcode = 'restrict_violation';
end;
$$;

-- 행을 고치거나 지우는 문장.
create trigger audit_log_no_update_delete
    before update or delete on audit_log
    for each row execute function audit_log_refuse_change();

-- 테이블을 통째로 비우는 문장.
create trigger audit_log_no_truncate
    before truncate on audit_log
    for each statement execute function audit_log_refuse_change();
