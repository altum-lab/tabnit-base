-- 제약 파일(constraints.ttl)의 글 그대로 (D5 4절, D6 6절, D2 AD-393). SHACL 을 Turtle 로 적은 것이다.
-- 제약이 없는 Pack 은 빈 글자다. 이미 적재된 Pack 은 다시 적재할 때까지 빈 글자다.
alter table pack_definition
    add column constraints text not null default '';
