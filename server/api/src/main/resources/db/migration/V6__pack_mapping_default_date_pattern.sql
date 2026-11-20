-- 매핑에 날짜의 기본 모양을 더한다 (D6 7절, D2 AD-143).
-- 유효기간의 열과, 모양을 따로 적지 않은 날짜 속성의 열을 이 모양으로 읽는다. 적지 않았으면 없다.
alter table pack_mapping add column default_date_pattern text;
