-- 관계를 도착 개체로 찾는 색인 (D2 AD-356, D5 3절 "relation").
--
-- 관계 지도, 영향 범위 계산, 시점 조회는 개체가 도착 쪽인 관계도 읽는다. 그동안은 도착 개체로 시작하는 색인이 없어
-- 겹침 거부의 색인(출발 개체로 시작한다)을 빌려 썼다. 성능 점검(A-07)의 큰 시험 자료로 재어 더한다.
-- 열의 정렬 규칙이 "C" 이므로 색인도 글자 코드 순이다.
create index relation_target_entity_idx on relation (target_entity_id);
