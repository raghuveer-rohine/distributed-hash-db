package com.distributed.dto;

import java.util.Map;

public class BulkDataRequest {
    private Map<String, String> data;

    public BulkDataRequest() {}

    public BulkDataRequest(Map<String, String> data) {
        this.data = data;
    }

    public Map<String, String> getData() {
        return data;
    }

    public void setData(Map<String, String> data) {
        this.data = data;
    }
}
