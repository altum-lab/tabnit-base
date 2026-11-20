package com.altumlab.tabnit.link;

/** 투입의 결과 (D5 5절 ingest_record, D2 AD-152, AD-155). */
public enum IngestOutcome {
    /** 판을 만들었다. */
    ACCEPTED,
    /** 내용이 바로 앞 판과 같아 판을 만들지 않았다. */
    UNCHANGED,
    /** 거부했다. 판을 만들지 않았다. */
    REJECTED
}
