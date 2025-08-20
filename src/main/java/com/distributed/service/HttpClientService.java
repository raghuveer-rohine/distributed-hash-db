package com.distributed.service;

import com.distributed.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class HttpClientService {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientService.class);
    @Autowired
    private RestTemplate restTemplate;

    public DataResponse put(NodeInfo node, String key, String value) {
        try {
            String url = "http://" + node.getAddress() + "/api/data";
            DataRequest request = new DataRequest(key, value);
            return restTemplate.postForObject(url, request, DataResponse.class);
        } catch (Exception e) {
            return new DataResponse("Error communicating with node: " + e.getMessage());
        }
    }

    public DataResponse get(NodeInfo node, String key) {
        try {
            String url = "http://" + node.getAddress() + "/api/data/" + key;
            return restTemplate.getForObject(url, DataResponse.class);
        } catch (Exception e) {
            return new DataResponse("Error communicating with node: " + e.getMessage());
        }
    }

    public void replicateData(NodeInfo node, String key, String value, int replicaIndex) {
        try {
            String url = "http://" + node.getAddress() + "/api/replica/" + replicaIndex;
            DataRequest request = new DataRequest(key, value);
            restTemplate.postForObject(url, request, DataResponse.class);
        } catch (Exception e) {
            System.err.println("Error replicating data to node " + node.getNodeId() + ": " + e.getMessage());
        }
    }

    public void replicateBulkData(NodeInfo node, Map<String, String> data, int replicaIndex) {
        try {
            String url = "http://" + node.getAddress() + "/api/replica/bulk/" + replicaIndex;
            BulkDataRequest request = new BulkDataRequest(data);

            logger.debug("Sending bulk replication request to {} with {} entries at replica level {}",
                    node.getNodeId(), data.size(), replicaIndex);

            restTemplate.postForObject(url, request, DataResponse.class);

            logger.info("Successfully replicated {} entries to node {} at replica level {}",
                    data.size(), node.getNodeId(), replicaIndex);
        } catch (Exception e) {
            logger.error("Error replicating bulk data to node {} at replica level {}: {}",
                    node.getNodeId(), replicaIndex, e.getMessage(), e);
        }
    }

    public RebalanceResponse rebalance(NodeInfo node, RebalanceRequest request) {
        try {
            String url = "http://" + node.getAddress() + "/api/rebalance";
            RebalanceResponse response = restTemplate.postForObject(url, request, RebalanceResponse.class);
            logger.debug("Rebalance response received from node {}: success={}",
                    node.getNodeId(), response != null ? response.isSuccess() : "null response");
            return response;
        } catch (Exception e) {
            logger.error("Error during rebalancing with node {}: {}", node.getNodeId(), e.getMessage());
            return new RebalanceResponse(null, null, false, "Communication error: " + e.getMessage());
        }
    }

    public Map<String, String> getAllPrimaryData(NodeInfo node) {
        try {
            String url = "http://" + node.getAddress() + "/api/data/primary";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, String> primaryData = response.getBody();
            logger.debug("Successfully fetched {} primary data entries from node: {}",
                    primaryData != null ? primaryData.size() : 0, node.getNodeId());
            return primaryData != null ? primaryData : new HashMap<>();
        } catch (Exception e) {
            logger.error("Failed to fetch primary data from node {}: {}",
                    node.getNodeId(), e.getMessage(), e);
            return new HashMap<>();
        }
    }

    public DataResponse delete(NodeInfo node, String key) {
        logger.debug("Forwarding DELETE request for key {} to node {}", key, node.getNodeId());
        try {
            String url = "http://" + node.getAddress() + "/api/data/" + key;
            restTemplate.delete(url);
            return new DataResponse("Key deleted successfully", true);
        } catch (HttpClientErrorException.NotFound e) {
            logger.warn("Key {} not found on node {}", key, node.getNodeId());
            return new DataResponse("Key not found", false);
        } catch (Exception e) {
            logger.error("Error deleting key {} from node {}: {}", key, node.getNodeId(), e.getMessage());
            return new DataResponse("Error communicating with node: " + e.getMessage(), false);
        }
    }

    public void deleteReplica(NodeInfo node, String key, int replicaIndex) {
        logger.debug("Deleting replica key {} from node {} at replica index {}", key, node.getNodeId(), replicaIndex);
        try {
            String url = "http://" + node.getAddress() + "/api/replica/" + key + "?replicaIndex=" + replicaIndex;
            restTemplate.delete(url);
            logger.debug("Successfully deleted replica key {} from node {} at index {}", key, node.getNodeId(), replicaIndex);
        } catch (Exception e) {
            logger.error("Failed to delete replica key {} from node {} at index {}: {}",
                    key, node.getNodeId(), replicaIndex, e.getMessage());
        }
    }

}

