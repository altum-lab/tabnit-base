package com.altumlab.tabnit.pack;

/** 속성 값의 형식 (D6 5절, D2 AD-71). */
public enum ValueFormat {

    /** 글자. `xsd:string` */
    STRING,

    /** 정수. `xsd:integer` */
    INTEGER,

    /** 소수. `xsd:decimal` */
    DECIMAL,

    /** 날짜. `xsd:date` */
    DATE,

    /** 참·거짓. `xsd:boolean` */
    BOOLEAN
}
