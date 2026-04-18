package com.devodox.stopatestimate.api;

public class ClockifyApiException extends RuntimeException {
    public ClockifyApiException(String message) {
        super(message);
    }

    public ClockifyApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
