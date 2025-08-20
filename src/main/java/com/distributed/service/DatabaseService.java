package com.distributed.service;

import com.distributed.dto.DataResponse;
import com.distributed.dto.NodeInfo;
import com.distributed.dto.RebalanceRequest;
import com.distributed.dto.RebalanceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class DatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class);

    @Autowired
    private ConsistentHashRing hashRing;

    @Autowired
    private DataStorageService storageService;

    @Autowired
    private DiscoveryClient nodeDiscovery;

    @Autowired
    private HttpClientService httpClient;

    private final AtomicBoolean rebalancing = new AtomicBoolean(false);
    private Set<String> knownNodes = new HashSet<>();
    @Autowired
    private Registration registration;

    public DataResponse put(String key, String value) {
        logger.info("PUT operation initiated for key: {}", key);

        if (rebalancing.get()) {
            logger.warn("System is currently rebalancing - rejecting PUT for key: {}", key);
            return new DataResponse("System is rebalancing, please try again later");
        }

        NodeInfo targetNode = hashRing.getNode(key);
        if (targetNode == null) {
            logger.error("No nodes available in hash ring for key: {}", key);
            return new DataResponse("No nodes available");
        }
        logger.debug("Target node determined for key {}: {}", key, targetNode.getNodeId());

        String nodeId = registration.getHost() + ":" + registration.getPort();

        NodeInfo currentNode = new NodeInfo(nodeId, registration.getHost(), registration.getPort());
        logger.info("Current nodeId: {} and NodeInfo nodeId: {}", nodeId, currentNode.getNodeId());
        if (currentNode == null) {
            logger.error("Current node {} not found in hash ring!", nodeId);
        }

        if (targetNode.getNodeId().equals(currentNode.getNodeId())) {
            logger.info("Key {} belongs to current node - storing locally", key);
            storageService.putPrimary(key, value);
            logger.debug("Successfully stored primary data for key: {}", key);

            logger.info("Initiating replication for key: {}", key);
            replicateToDownstream(key, value, currentNode.getNodeId());
            return new DataResponse(value, true);
        } else {
            logger.info("Key {} belongs to node {} - forwarding PUT request", key, targetNode.getNodeId());
            DataResponse response = httpClient.put(targetNode, key, value);
            logger.debug("Received response from forwarded PUT: {}", response);
            return response;
        }
    }

    private void replicateToDownstream(String key, String value, String currentNodeId) {
        logger.info("Starting replication process for key: {}", key);
        List<NodeInfo> allNodes = hashRing.getAllNodes();
        logger.debug("All nodes in hash ring before replication: {}",
                allNodes.stream()
                        .map(n -> n.getNodeId() + "(hash=" + n.getHashValue() + ")")
                        .toList());

        if (allNodes.size() <= 1) {
            logger.info("No other nodes available for replication");
            return;
        }

        int replicaIndex = 1;
        NodeInfo nextNode = hashRing.getNextNode(currentNodeId);

        if (nextNode == null) {
            logger.error("getNextNode() returned null. Replication aborted.");
            return;
        }

        logger.info("Starting replication for key='{}' from node={} with replicationFactor={} → first target={}",
                key, currentNodeId, storageService.getReplicationFactor(), nextNode.getNodeId());

        while (nextNode != null &&
                !nextNode.getNodeId().equals(currentNodeId) &&
                replicaIndex < storageService.getReplicationFactor()) {

            logger.info("Replicating key '{}' → node={} [replicaIndex={}]",
                    key, nextNode.getNodeId(), replicaIndex);

            httpClient.replicateData(nextNode, key, value, replicaIndex);

            nextNode = hashRing.getNextNode(currentNodeId);
            replicaIndex++;

            logger.debug("Next node after replication step: {}",
                    nextNode != null ? nextNode.getNodeId() : "null");
        }

        if (nextNode == null) {
            logger.warn("Replication loop ended because nextNode is null (incomplete ring?)");
        } else if (nextNode.getNodeId().equals(currentNodeId)) {
            logger.info("Replication loop ended after full circle back to current node ({})", currentNodeId);
        } else if (replicaIndex >= storageService.getReplicationFactor()) {
            logger.info("Replication loop ended after reaching replicationFactor={} for key='{}'",
                    storageService.getReplicationFactor(), key);
        }

        logger.info("Completed replication for key '{}' → total replicas: {}", key, replicaIndex - 1);
    }

    public void putReplica(int replicaIndex, String key, String value) {
        logger.info("Storing replica data for key {} at replica index {}", key, replicaIndex);
        storageService.putReplica(replicaIndex, key, value);
        logger.debug("Successfully stored replica data for key {} at index {}", key, replicaIndex);
    }

    public DataResponse get(String key) {
        logger.info("GET operation initiated for key: {}", key);
        NodeInfo targetNode = hashRing.getNode(key);

        if (targetNode == null) {
            logger.error("No nodes available in hash ring for key: {}", key);
            return new DataResponse("No nodes available");
        }
        logger.debug("Target node determined for key {}: {}", key, targetNode.getNodeId());

        String nodeId = registration.getHost() + ":" + registration.getPort();
        NodeInfo currentNode = new NodeInfo(nodeId, registration.getHost(), registration.getPort());
        if (currentNode == null) {
            logger.error("Current node {} not found in hash ring!", nodeId);
        }


        if (targetNode.getNodeId().equals(currentNode.getNodeId())) {
            logger.info("Key {} belongs to current node - checking local storage", key);
            String value = storageService.getPrimary(key);

            if (value != null) {
                logger.debug("Found primary data for key: {}", key);
                return new DataResponse(value, true);
            }

            logger.debug("Primary data not found for key {}, checking replicas", key);
            for (int i = 1; i < storageService.getReplicationFactor(); i++) {
                value = storageService.getReplica(i, key);
                if (value != null) {
                    logger.debug("Found replica data for key {} at replica index {}", key, i);
                    return new DataResponse(value, true);
                }
            }

            logger.warn("Key {} not found in primary or any replicas", key);
            return new DataResponse("Key not found");
        } else {
            logger.info("Key {} belongs to node {} - forwarding GET request", key, targetNode.getNodeId());
            DataResponse response = httpClient.get(targetNode, key);

            if (response == null || !response.isFound()) {
                logger.debug("Primary node lookup failed for key {}, trying next node", key);

                for (int i = 1; i < storageService.getReplicationFactor(); i++) {

                    targetNode = hashRing.getNextNode(targetNode.getNodeId());

                    if (targetNode != null && !targetNode.getNodeId().equals(targetNode.getNodeId())) {
                        logger.debug("Attempting GET from next node: {}", targetNode.getNodeId());

                        response = httpClient.get(targetNode, key);
                        if (response != null && response.isFound()) {
                            break;
                        }
                    }
                }

            }

            if (response != null) {
                logger.debug("GET operation result for key {}: {}", key, response);
            } else {
                logger.warn("Key {} not found after checking primary and next node", key);
            }

            return response != null ? response : new DataResponse("Key not found");
        }
    }


    public DataResponse delete(String key) {
        logger.info("DELETE operation initiated for key: {}", key);

        if (rebalancing.get()) {
            logger.warn("System is currently rebalancing - rejecting DELETE for key: {}", key);
            return new DataResponse("System is rebalancing, please try again later");
        }

        NodeInfo targetNode = hashRing.getNode(key);
        if (targetNode == null) {
            logger.error("No nodes available in hash ring for key: {}", key);
            return new DataResponse("No nodes available");
        }
        logger.debug("Target node determined for key {}: {}", key, targetNode.getNodeId());

        String nodeId = registration.getHost() + ":" + registration.getPort();
        NodeInfo currentNode = new NodeInfo(nodeId, registration.getHost(), registration.getPort());
        logger.info("Current nodeId: {} and NodeInfo nodeId: {}", nodeId, currentNode.getNodeId());

        if (currentNode == null) {
            logger.error("Current node {} not found in hash ring!", nodeId);
        }

        if (targetNode.getNodeId().equals(currentNode.getNodeId())) {
            logger.info("Key {} belongs to current node - deleting locally", key);

            // Check if key exists before deletion
            String existingValue = storageService.getPrimary(key);
            if (existingValue == null) {
                logger.warn("Key {} not found in primary storage - nothing to delete", key);
                return new DataResponse("Key not found", false);
            }

            storageService.deletePrimary(key);
            logger.debug("Successfully deleted primary data for key: {}", key);

            logger.info("Initiating deletion replication for key: {}", key);
            deleteFromDownstream(key, currentNode.getNodeId());
            return new DataResponse("Key deleted successfully", true);
        } else {
            logger.info("Key {} belongs to node {} - forwarding DELETE request", key, targetNode.getNodeId());
            DataResponse response = httpClient.delete(targetNode, key);
            logger.debug("Received response from forwarded DELETE: {}", response);
            return response;
        }
    }

    private void deleteFromDownstream(String key, String currentNodeId) {
        logger.info("Starting deletion replication process for key: {}", key);
        List<NodeInfo> allNodes = hashRing.getAllNodes();
        logger.debug("All nodes in hash ring before deletion replication: {}",
                allNodes.stream()
                        .map(n -> n.getNodeId() + "(hash=" + n.getHashValue() + ")")
                        .toList());

        if (allNodes.size() <= 1) {
            logger.info("No other nodes available for deletion replication");
            return;
        }

        int replicaIndex = 1;
        NodeInfo nextNode = hashRing.getNextNode(currentNodeId);

        if (nextNode == null) {
            logger.error("getNextNode() returned null. Deletion replication aborted.");
            return;
        }

        logger.info("Starting deletion replication for key='{}' from node={} with replicationFactor={} → first target={}",
                key, currentNodeId, storageService.getReplicationFactor(), nextNode.getNodeId());

        while (nextNode != null &&
                !nextNode.getNodeId().equals(currentNodeId) &&
                replicaIndex < storageService.getReplicationFactor()) {

            logger.info("Deleting key '{}' from node={} [replicaIndex={}]",
                    key, nextNode.getNodeId(), replicaIndex);

            httpClient.deleteReplica(nextNode, key, replicaIndex);

            nextNode = hashRing.getNextNode(nextNode.getNodeId());
            replicaIndex++;

            logger.debug("Next node after deletion replication step: {}",
                    nextNode != null ? nextNode.getNodeId() : "null");
        }

        if (nextNode == null) {
            logger.warn("Deletion replication loop ended because nextNode is null (incomplete ring?)");
        } else if (nextNode.getNodeId().equals(currentNodeId)) {
            logger.info("Deletion replication loop ended after full circle back to current node ({})", currentNodeId);
        } else if (replicaIndex >= storageService.getReplicationFactor()) {
            logger.info("Deletion replication loop ended after reaching replicationFactor={} for key='{}'",
                    storageService.getReplicationFactor(), key);
        }

        logger.info("Completed deletion replication for key '{}' → total replicas processed: {}", key, replicaIndex - 1);
    }

    @Scheduled(fixedDelay = 10000)
    public void checkForNodeChanges() {

        logger.debug("Starting scheduled node health check");

        if (rebalancing.get()) {
            logger.debug("Skipping node check during rebalancing");
            return;
        }

        List<ServiceInstance> instances = nodeDiscovery.getInstances("distributed-database");
        Set<String> currentNodeIds = instances.stream()
                .map(si -> si.getHost() + ":" + si.getPort())
                .collect(Collectors.toSet());
        logger.debug("Current live nodes: {}", currentNodeIds);

        boolean isCurrentNodeNewNode = false;
        String currentNodeId = registration.getHost() + ":" + registration.getPort();
        // Check for new nodes
        for (ServiceInstance si : instances) {
            String nodeId = si.getHost() + ":" + si.getPort();
            if (!knownNodes.contains(nodeId)) {
                logger.info("Detected new node: {}", nodeId);
                knownNodes.add(nodeId);
                if (currentNodeId.equals(nodeId)) isCurrentNodeNewNode = true;
                NodeInfo newNode = new NodeInfo(nodeId, si.getHost(), si.getPort());
                handleNodeAddition(newNode);
            }
        }

        // Check for removed nodes
        for (String nodeId : knownNodes) {
            if (!currentNodeIds.contains(nodeId)) {
                logger.warn("Detected node removal: {}", nodeId);
                knownNodes.remove(nodeId);
                handleNodeRemoval(nodeId);
                break;
            }
        }
        if (isCurrentNodeNewNode) {
            NodeInfo newNode = new NodeInfo(currentNodeId, registration.getHost(), registration.getPort());
            rebalanceForAddition(newNode);
        }

        logger.debug("Node check completed. Known nodes: {}", knownNodes);
    }

    private void handleNodeAddition(NodeInfo newNode) {
        logger.info("Attempting to handle addition of node: {}", newNode.getNodeId());

        if (rebalancing.compareAndSet(false, true)) {
            try {
                logger.info("Proceeding with node addition for: {}", newNode.getNodeId());
                hashRing.addNode(newNode);
                logger.debug("Node {} added to hash ring", newNode.getNodeId());
            } finally {
                rebalancing.set(false);
                logger.debug("Rebalancing lock released after node addition");
            }
        } else {
            logger.warn("Could not acquire rebalancing lock for node addition");
        }
    }

    private void handleNodeRemoval(String removedNodeId) {
        logger.info("Attempting to handle removal of node: {}", removedNodeId);

        if (rebalancing.compareAndSet(false, true)) {
            try {
                logger.info("Proceeding with node removal for: {}", removedNodeId);
                List<NodeInfo> allNodes = hashRing.getAllNodes();
                NodeInfo removedNode = allNodes.stream()
                        .filter(n -> n.getNodeId().equals(removedNodeId))
                        .findFirst().orElse(null);

                if (removedNode != null) {
                    // Check if the removed node was the previous node to current node
                    String currentNodeId = registration.getHost() + ":" + registration.getPort();
                    boolean shouldPromoteData = isRemovedNodeMyPrevious(removedNodeId, currentNodeId);

                    // Remove node from hash ring first
                    hashRing.removeNode(removedNode);
                    logger.debug("Node {} removed from hash ring", removedNodeId);

                    // Only promote data if this node is the inheritor
                    if (shouldPromoteData) {
                        logger.info("Current node {} will inherit data from removed node {}",
                                currentNodeId, removedNodeId);
                        promoteSecondaryToPrimary(removedNodeId);
                    } else {
                        logger.debug("Current node {} is not affected by removal of {}",
                                currentNodeId, removedNodeId);
                    }

                    logger.info("Completed handling node removal for: {}", removedNodeId);
                } else {
                    logger.warn("Node {} not found in hash ring during removal", removedNodeId);
                }
            } finally {
                rebalancing.set(false);
                logger.debug("Rebalancing lock released after node removal");
            }
        } else {
            logger.warn("Could not acquire rebalancing lock for node removal");
        }
    }

    private boolean isRemovedNodeMyPrevious(String removedNodeId, String currentNodeId) {
        try {
            NodeInfo previousNode = hashRing.getPreviousNode(currentNodeId);
            if (previousNode != null) {
                boolean isMyPrevious = previousNode.getNodeId().equals(removedNodeId);
                logger.debug("Previous node of {} is {}, removed node is {}, match: {}",
                        currentNodeId, previousNode.getNodeId(), removedNodeId, isMyPrevious);
                return isMyPrevious;
            } else {
                logger.warn("Could not find previous node for {}", currentNodeId);
                return false;
            }
        } catch (Exception e) {
            logger.error("Error checking if {} was previous to {}: {}",
                    removedNodeId, currentNodeId, e.getMessage(), e);
            return false;
        }
    }

    private void promoteSecondaryToPrimary(String removedNodeId) {
        logger.info("Starting promotion of secondary data to primary for removed node: {}", removedNodeId);

        try {

            int replicaIndexToPromote = 1;


            int dataSize = storageService.getReplicaData(replicaIndexToPromote).size();

            if (dataSize > 0) {
                logger.info("Promoting {} entries from replica level {} to primary for removed node: {}",
                        dataSize, replicaIndexToPromote, removedNodeId);


                Map<String, String> dataPromoted = storageService.promoteReplicaToPrimary(replicaIndexToPromote);

                logger.info("Successfully promoted {} secondary entries to primary for removed node: {}",
                        dataSize, removedNodeId);


                // Replicate all promoted data to downstream nodes
                if (!dataPromoted.isEmpty()) {
                    String currentNodeId = registration.getHost() + ":" + registration.getPort();
                    logger.info("Initiating replication of {} promoted entries to downstream nodes from current node: {}",
                            dataPromoted.size(), currentNodeId);
                    replicatePromotedDataToDownstream(dataPromoted, currentNodeId);
                } else {
                    logger.info("No promoted data to replicate to downstream nodes");
                }

                logger.info("Successfully promoted {} secondary entries to primary for removed node: {}",
                        dataSize, removedNodeId);
            } else {
                logger.info("No secondary data found at replica level {} to promote for removed node: {}",
                        replicaIndexToPromote, removedNodeId);
            }


            fetchPreviousNodePrimaryData();

        } catch (Exception e) {
            logger.error("Failed to promote secondary data for removed node {}: {}",
                    removedNodeId, e.getMessage(), e);
        }
    }


    private void fetchPreviousNodePrimaryData() {
        logger.info("Starting fetch of primary data from previous node");

        try {
            String currentNodeId = registration.getHost() + ":" + registration.getPort();
            NodeInfo previousNode = hashRing.getPreviousNode(currentNodeId);

            if (previousNode == null) {
                logger.warn("No previous node found to fetch primary data from");
                return;
            }

            if (previousNode.getNodeId().equals(currentNodeId)) {
                logger.info("Current node is the only node in ring - no previous node data to fetch");
                return;
            }

            logger.info("Fetching primary data from previous node: {}", previousNode.getNodeId());

            Map<String, String> previousNodePrimaryData = httpClient.getAllPrimaryData(previousNode);

            if (!previousNodePrimaryData.isEmpty()) {

                storageService.putReplicaData(1, previousNodePrimaryData);

                logger.info("Successfully stored {} entries from previous node {} as replica data at index 1",
                        previousNodePrimaryData.size(), previousNode.getNodeId());
            } else {
                logger.info("No primary data found on previous node: {}", previousNode.getNodeId());
            }

        } catch (Exception e) {
            logger.error("Failed to fetch primary data from previous node: {}", e.getMessage(), e);
        }
    }

    private void replicatePromotedDataToDownstream(Map<String, String> promotedData, String currentNodeId) {
        logger.info("Starting replication of {} promoted entries to downstream nodes", promotedData.size());

        List<NodeInfo> allNodes = hashRing.getAllNodes();
        if (allNodes.size() <= 1) {
            logger.info("No other nodes available for replication of promoted data");
            return;
        }

        int replicaIndex = 1;
        NodeInfo nextNode = hashRing.getNextNode(currentNodeId);

        if (nextNode == null) {
            logger.error("getNextNode() returned null. Promoted data replication aborted.");
            return;
        }

        logger.info("Starting replication of promoted data from node={} with replicationFactor={} → first target={}",
                currentNodeId, storageService.getReplicationFactor(), nextNode.getNodeId());

        while (nextNode != null &&
                !nextNode.getNodeId().equals(currentNodeId) &&
                replicaIndex < storageService.getReplicationFactor()) {

            logger.info("Replicating {} promoted entries → node={} [replicaIndex={}]",
                    promotedData.size(), nextNode.getNodeId(), replicaIndex);

            httpClient.replicateBulkData(nextNode, promotedData, replicaIndex);

            nextNode = hashRing.getNextNode(nextNode.getNodeId());
            replicaIndex++;

            logger.debug("Next node after bulk replication step: {}",
                    nextNode != null ? nextNode.getNodeId() : "null");
        }

        logger.info("Completed replication of promoted data → total replica levels: {}", replicaIndex - 1);
    }
    public void putBulkReplica(int replicaIndex, Map<String, String> data) {
        logger.info("Storing bulk replica data: {} entries at replica index {}", data.size(), replicaIndex);
        storageService.putReplicaData(replicaIndex, data);
        logger.info("Successfully stored {} replica entries at index {}", data.size(), replicaIndex);
    }


    private void rebalanceForAddition(NodeInfo newNode) {
        logger.info("Starting rebalance for new node addition: {}", newNode.getNodeId());

        NodeInfo nextNode = hashRing.getNextNode(newNode.getNodeId());
        if (nextNode != null && !nextNode.getNodeId().equals(newNode.getNodeId())) {
            NodeInfo prevNode = hashRing.getPreviousNode(newNode.getNodeId());
            long startRange = prevNode != null ? prevNode.getHashValue() + 1 : 0;
            long endRange = newNode.getHashValue();

            logger.debug("New node will handle range {}-{}", startRange, endRange);


            int replicaIndexToUse = storageService.getReplicationFactor() - 1;

            RebalanceRequest request = new RebalanceRequest();
            request.setOperation("ADD");
            request.setNodeId(newNode.getNodeId());
            request.setStartRange(startRange);
            request.setEndRange(endRange);
            request.setReplicaIndex(replicaIndexToUse);

            logger.debug("Sending rebalance request to next node {} with replica index {}",
                    nextNode.getNodeId(), replicaIndexToUse);

            RebalanceResponse response = httpClient.rebalance(nextNode, request);

            if (response != null && response.isSuccess()) {

                if (response.getNewNodePrimaryData() != null) {
                    storageService.putAllPrimary(response.getNewNodePrimaryData());
                    logger.info("Added {} primary data entries to new node",
                            response.getNewNodePrimaryData().size());
                }

                if (response.getNewNodeSecondaryData() != null) {
                    storageService.putReplicaData(1, response.getNewNodeSecondaryData());
                    logger.info("Added {} secondary data entries to new node at replica index 1",
                            response.getNewNodeSecondaryData().size());
                }

                logger.info("Successfully completed rebalancing for new node {}", newNode.getNodeId());
            } else {
                logger.error("Rebalance failed for new node {}: {}",
                        newNode.getNodeId(), response != null ? response.getMessage() : "No response");
            }
        } else {
            logger.warn("No suitable next node found for rebalancing after addition");
        }
    }

    public RebalanceResponse handleRebalance(RebalanceRequest request) {
        logger.info("Handling rebalance request. Operation: {}, Node: {}",
                request.getOperation(), request.getNodeId());

        if ("ADD".equals(request.getOperation())) {
            return handleAddRebalance(request);
        }
//        else if ("REMOVE".equals(request.getOperation())) {
//            return handleRemoveRebalance(request);
//        }

        return new RebalanceResponse(null, null, false, "Unknown operation: " + request.getOperation());
    }

    private RebalanceResponse handleAddRebalance(RebalanceRequest request) {
        logger.info("Processing ADD rebalance for range {}-{}, replicaIndex: {}",
                request.getStartRange(), request.getEndRange(), request.getReplicaIndex());

        try {

            Map<String, String> newNodePrimaryData = storageService.extractDataInRange(
                    request.getStartRange(), request.getEndRange());
            logger.debug("Extracted {} keys for new node's primary data", newNodePrimaryData.size());


            Map<String, String> newNodeSecondaryData = storageService.extractReplicaData(request.getReplicaIndex());
            logger.debug("Extracted {} keys for new node's secondary data from replica index {}",
                    newNodeSecondaryData.size(), request.getReplicaIndex());


            storageService.putReplicaData(request.getReplicaIndex(), newNodePrimaryData);
            logger.debug("Moved new node's primary data to replica index {} on current node", request.getReplicaIndex());

            logger.info("ADD rebalance completed successfully");
            return new RebalanceResponse(newNodePrimaryData, newNodeSecondaryData, true, "Rebalance successful");

        } catch (Exception e) {
            logger.error("Error during ADD rebalance: {}", e.getMessage(), e);
            return new RebalanceResponse(null, null, false, "Rebalance failed: " + e.getMessage());
        }
    }
}