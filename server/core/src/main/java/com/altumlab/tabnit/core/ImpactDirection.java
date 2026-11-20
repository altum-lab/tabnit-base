package com.altumlab.tabnit.core;

/**
 * 관계 유형 하나에서 영향이 퍼지는 방향 (D3 7절, D2 AD-33).
 *
 * <p>값은 Pack 이 정하고 부르는 쪽이 넘긴다. 이 모듈은 받은 값만 읽는다 (D2 AD-66).
 */
public enum ImpactDirection {

    /** 출발 개체에서 도착 개체로 퍼진다. */
    SOURCE_TO_TARGET,

    /** 도착 개체에서 출발 개체로 퍼진다. */
    TARGET_TO_SOURCE,

    /** 양쪽으로 퍼진다. */
    BOTH,

    /** 퍼지지 않는다. */
    NONE
}
