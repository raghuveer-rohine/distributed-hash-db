package com.distributed.dto;

public class DataResponse {
    private String value;
    private boolean found;
    private String message;

    public DataResponse() {}

    public DataResponse(String value, boolean found) {
        this.value = value;
        this.found = found;
    }

    public DataResponse(String message) {
        this.message = message;
        this.found = false;
    }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public boolean isFound() { return found; }
    public void setFound(boolean found) { this.found = found; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
