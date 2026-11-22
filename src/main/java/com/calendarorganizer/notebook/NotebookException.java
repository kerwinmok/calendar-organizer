package com.calendarorganizer.notebook;

import java.io.IOException;

public class NotebookException extends IOException {

    private final int statusCode;

    public NotebookException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public NotebookException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
