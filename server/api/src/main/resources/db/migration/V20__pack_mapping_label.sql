-- 입력 자료의 이름표 (B-15, D2 AD-384, D6 7절, D5 4절).
-- 매핑에 적은 화면에 보일 이름이다. 적지 않았으면 없다: 화면은 입력 자료의 식별자를 보인다.

alter table pack_mapping
    add column label text,
    add constraint pack_mapping_label_ck check (label is null or label <> '');
