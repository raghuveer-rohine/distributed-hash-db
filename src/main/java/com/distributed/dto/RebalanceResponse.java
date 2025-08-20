package com.distributed.dto;

import java.util.Map;

public class RebalanceResponse {
    private Map<String, String> newNodePrimaryData;
    private Map<String, String> newNodeSecondaryData;
    private boolean success;
    private String message;

    public RebalanceResponse() {}

    public RebalanceResponse(Map<String, String> newNodePrimaryData,
                             Map<String, String> newNodeSecondaryData,
                             boolean success, String message) {
        this.newNodePrimaryData = newNodePrimaryData;
        this.newNodeSecondaryData = newNodeSecondaryData;
        this.success = success;
        this.message = message;
    }

    // Getters and setters
    public Map<String, String> getNewNodePrimaryData() { return newNodePrimaryData; }
    public void setNewNodePrimaryData(Map<String, String> newNodePrimaryData) {
        this.newNodePrimaryData = newNodePrimaryData;
    }

    public Map<String, String> getNewNodeSecondaryData() { return newNodeSecondaryData; }
    public void setNewNodeSecondaryData(Map<String, String> newNodeSecondaryData) {
        this.newNodeSecondaryData = newNodeSecondaryData;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
