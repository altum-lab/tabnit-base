package com.altumlab.tabnit.rules;

/** 그런 조치가 없다. */
public class ActionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ActionNotFoundException(String message) {
        super(message);
    }
}
