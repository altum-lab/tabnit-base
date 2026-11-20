package com.altumlab.tabnit.pack;

import java.util.List;

/** 이미 적재된 Pack 을 받을 수 없는 방식으로 고쳤다. 저장된 정의는 그대로다 (D6 9절, D2 AD-95). */
public class PackChangeRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> reasons;

    public PackChangeRejectedException(String packId, List<String> reasons) {
        super("고친 Pack 을 적재하지 않았습니다. Pack: " + packId + ". 이미 적재된 정의와 맞지 않는 곳 " + reasons.size()
                + "건을 되돌린 뒤 다시 적재하세요.\n- " + String.join("\n- ", reasons));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> reasons() {
        return reasons;
    }
}
