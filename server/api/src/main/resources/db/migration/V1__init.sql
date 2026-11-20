-- 뼈대 확인용 스키마. 실제 테이블은 D5 가 확정되면 V2 부터 넣는다.
create table tabnit_meta (
    key   text primary key,
    value text not null
);
insert into tabnit_meta (key, value) values ('schema', 'v0');
