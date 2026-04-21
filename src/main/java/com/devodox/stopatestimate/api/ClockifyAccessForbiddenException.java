package com.devodox.stopatestimate.api;

public class ClockifyAccessForbiddenException extends RuntimeException {
    public ClockifyAccessForbiddenException(String message) {
        super(message);
    }

    public ClockifyAccessForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}
