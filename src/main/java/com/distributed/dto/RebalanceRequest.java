package com.distributed.dto;

import java.util.Map;

public class RebalanceRequest {
    private String operation;
    private String nodeId;
    private long startRange;
    private long endRange;
    private Map<String, String> data;
    private int replicaIndex;


    private Map<String, String> newNodePrimaryData;
    private Map<String, String> newNodeSecondaryData;

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public long getStartRange() { return startRange; }
    public void setStartRange(long startRange) { this.startRange = startRange; }

    public long getEndRange() { return endRange; }
    public void setEndRange(long endRange) { this.endRange = endRange; }

    public Map<String, String> getData() { return data; }
    public void setData(Map<String, String> data) { this.data = data; }

    public int getReplicaIndex() { return replicaIndex; }
    public void setReplicaIndex(int replicaIndex) { this.replicaIndex = replicaIndex; }

    public Map<String, String> getNewNodePrimaryData() { return newNodePrimaryData; }
    public void setNewNodePrimaryData(Map<String, String> newNodePrimaryData) {
        this.newNodePrimaryData = newNodePrimaryData;
    }

    public Map<String, String> getNewNodeSecondaryData() { return newNodeSecondaryData; }
    public void setNewNodeSecondaryData(Map<String, String> newNodeSecondaryData) {
        this.newNodeSecondaryData = newNodeSecondaryData;
    }
}
