package com.altumlab.tabnit.pack;

/** 조건이 속성 값을 무엇과 견주는가 (D8 3절, D2 AD-292). */
public enum RuleCompareTo {
    /** 규칙에 적은 고정 값. */
    VALUE,
    /** 그 변동이 나온 판의 적용일. 날짜 속성에만 쓴다. */
    EFFECTIVE_DATE,
    /** 다른 대상(변동 대상이나 영향 대상)의 속성 값. 두 속성의 형식이 같아야 한다. */
    ATTRIBUTE
}
