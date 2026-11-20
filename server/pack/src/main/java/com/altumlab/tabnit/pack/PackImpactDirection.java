package com.altumlab.tabnit.pack;

/** 관계 유형에서 영향이 퍼지는 방향. Pack 의 온톨로지가 관계 유형마다 적는다 (D6 5절, D2 AD-33). */
public enum PackImpactDirection {

    /** 출발에서 도착으로. `tabnit:SourceToTarget` */
    SOURCE_TO_TARGET,

    /** 도착에서 출발로. `tabnit:TargetToSource` */
    TARGET_TO_SOURCE,

    /** 양쪽. `tabnit:Both` */
    BOTH,

    /** 퍼지지 않는다. `tabnit:None` */
    NONE
}
