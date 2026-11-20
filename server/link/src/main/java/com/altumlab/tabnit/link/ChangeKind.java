package com.altumlab.tabnit.link;

/** 판 비교에서 항목이 어떻게 바뀌었는가 (D3 6절). */
public enum ChangeKind {
    /** 키가 새 판에만 있다. */
    ADDED,
    /** 키가 두 판에 다 있고 그 행이 넣은 것이 달라졌다. */
    CHANGED,
    /** 키가 앞 판에만 있다. */
    REMOVED
}
