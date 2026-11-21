package com.altumlab.tabnit.rules;

/**
 * 확정이나 보류에 넘긴 값이 틀렸다. 어느 항목인지를 함께 알린다. 아무것도 바뀌지 않았다.
 *
 * <p>문구는 사용자에게 보여도 되는 한국어 문구다: 무엇이 틀렸고 무엇을 하면 되는지를 담는다.
 */
public class ActionDecisionInputException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** 틀린 항목. */
    public enum Field {
        /** 조치의 식별자. */
        ACTION_ID,
        /** 누가 했는가. */
        DECIDED_BY,
        /** 사유. */
        NOTE,
        /** 언제 했는가. */
        DECIDED_AT
    }

    private final Field field;

    public ActionDecisionInputException(Field field, String message) {
        super(message);
        this.field = field;
    }

    public Field field() {
        return field;
    }
}
