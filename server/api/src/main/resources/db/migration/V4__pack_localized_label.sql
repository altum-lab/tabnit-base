-- pack 의 테이블: 언어별 이름표 (D5 4절, D6 5절 "이름표", D2 AD-131).
-- 기본 이름표(언어 표시 없는 것)는 각 정의 테이블의 label 열에 있다. 여기에는 언어 표시가 붙은 것만 담는다.

create table pack_localized_label (
    pack_id  text collate "C" not null,
    -- 이름표가 붙은 정의의 이름. Pack 자신의 이름표이면 빈 글자다.
    -- 개체 유형, 관계 유형, 속성의 이름은 한 Pack 안에서 겹치지 않는다. 정의된 이름인지는 pack 의 검증이 지킨다.
    name     text collate "C" not null,
    -- 언어 표시. 소문자로 저장한다.
    language text collate "C" not null,
    label    text             not null,
    constraint pack_localized_label_pk primary key (pack_id, name, language),
    constraint pack_localized_label_pack_fk foreign key (pack_id) references pack_definition (pack_id),
    constraint pack_localized_label_not_blank_ck check (language <> '' and label <> ''),
    constraint pack_localized_label_language_lower_ck check (language = lower(language))
);
