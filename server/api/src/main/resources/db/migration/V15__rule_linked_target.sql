-- 규칙의 조건이 규칙의 대상과 어느 관계로 이어진 대상을 본다 (D2 AD-338 ~ AD-340, D8 3절, D5 4절).
-- 이미 올라간 변경 파일(V9, V14)은 고치지 않고 여기서 더한다.

-- 조건이 보는 것에 "이어진 대상"을 더한다.
alter table pack_rule_condition
    drop constraint pack_rule_condition_subject_ck,
    add constraint pack_rule_condition_subject_ck
        check (subject in ('CHANGE_TARGET', 'IMPACT_TARGET', 'LINKED_TARGET')),
    -- 이어진 대상을 볼 때, 규칙의 대상과 그 대상을 잇는 관계 유형.
    add column linked_relation_type text collate "C",
    add constraint pack_rule_condition_linked_type_fk foreign key (pack_id, linked_relation_type)
        references pack_relation_type (pack_id, name),
    -- 이어진 대상을 보는 조건에만, 그리고 반드시 관계 유형이 있다.
    add constraint pack_rule_condition_linked_shape_ck
        check ((subject = 'LINKED_TARGET') = (linked_relation_type is not null));
