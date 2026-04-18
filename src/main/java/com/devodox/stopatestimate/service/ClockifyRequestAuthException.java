package com.devodox.stopatestimate.service;

public class ClockifyRequestAuthException extends RuntimeException {
    public ClockifyRequestAuthException(String message) {
        super(message);
    }

    public ClockifyRequestAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
