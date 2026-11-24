package com.altumlab.tabnit.link;

import java.util.List;

/** 시험에서 알림을 고르는 도우미. */
final class Notices {

    private Notices() {
    }

    /**
     * 참조로만 만들어진 개체의 알림을 뺀 나머지.
     *
     * <p>그 알림은 관계의 상대편에 속성이 없으면 판마다 나온다 (D2 AD-378). 다른 것을 보는 시험의 지어낸 자료와 예시 Pack 의
     * 첫 판들에서도 나온다 (자료를 넣는 차례 때문이다). 그 알림 자체는 {@link ReferenceOnlyEntityTest} 가 본다.
     */
    static List<IngestNotice> exceptReferenceOnly(List<IngestNotice> notices) {
        return notices.stream().filter(notice -> !"REFERENCE_ONLY_ENTITY".equals(notice.kind())).toList();
    }
}
