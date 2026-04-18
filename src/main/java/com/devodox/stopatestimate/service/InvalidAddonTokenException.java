package com.devodox.stopatestimate.service;

public class InvalidAddonTokenException extends RuntimeException {

    public InvalidAddonTokenException(String message) {
        super(message);
    }

    public InvalidAddonTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
