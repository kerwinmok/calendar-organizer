package com.calendarorganizer.notebook;

import java.io.IOException;

public class NotebookException extends IOException {

    private final int statusCode;
    private final String errorCode;

    public NotebookException(int statusCode, String message) {
        this(statusCode, "", message, null);
    }

    public NotebookException(int statusCode, String errorCode, String message) {
        this(statusCode, errorCode, message, null);
    }

    public NotebookException(int statusCode, String message, Throwable cause) {
        this(statusCode, "", message, cause);
    }

    public NotebookException(int statusCode, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        if (errorCode == null) {
            this.errorCode = "";
        } else {
            this.errorCode = errorCode;
        }
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isInvalidGrant() {
        return "invalid_grant".equalsIgnoreCase(errorCode);
    }

    public boolean isNetworkError() {
        return "OFFLINE".equals(errorCode) || "TIMEOUT".equals(errorCode) || statusCode == 0;
    }
}
