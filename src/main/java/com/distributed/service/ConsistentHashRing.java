package com.distributed.service;


import com.distributed.dto.NodeInfo;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

@Component
public class ConsistentHashRing {
    private final ConcurrentNavigableMap<Long, NodeInfo> circle = new ConcurrentSkipListMap<>();

    private final HashFunction hashFunction = Hashing.murmur3_32_fixed();
    private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class);
    private long hash(String key) {
        logger.trace("ENTERING hash(key={})", key);
        logger.debug("Calculating hash for key: {}", key);

        try {
            long hashValue = hashFunction.hashString(key, StandardCharsets.UTF_8).asInt() & 0xffffffffL;
            logger.debug("Calculated hash value for key '{}': {}", key, hashValue);
            logger.trace("EXITING hash()");
            return hashValue;
        } catch (Exception e) {
            logger.error("Failed to calculate hash for key '{}': {}", key, e.getMessage(), e);
            throw e;
        }
    }

    public void addNode(NodeInfo node) {
        logger.trace("ENTERING addNode(node={})", node);
        logger.info("Attempting to add new node to hash ring: {}", node.getNodeId());

        if (node == null) {
            logger.error("Cannot add null node to hash ring");
            throw new IllegalArgumentException("Node cannot be null");
        }

        if (circle.containsKey(hash(node.getNodeId()))) {
            logger.warn("Node with ID {} already exists in hash ring", node.getNodeId());
            return;
        }

        try {
            long hash = hash(node.getNodeId());
            logger.debug("Calculated hash value {} for node {}", hash, node.getNodeId());

            node.setHashValue(hash);
            logger.debug("Set hash value {} for node {}", hash, node.getNodeId());

            circle.put(hash, node);
            logger.info("Successfully added node {} to hash ring with hash {}", node.getNodeId(), hash);

            logger.debug("Current hash ring state after addition:");
            circle.forEach((k, v) -> logger.debug("Hash: {}, Node: {}", k, v.getNodeId()));
        } catch (Exception e) {
            logger.error("Failed to add node {} to hash ring: {}", node.getNodeId(), e.getMessage(), e);
            throw e;
        }

        logger.trace("EXITING addNode()");
    }

    public synchronized void removeNode(NodeInfo node) {
        logger.trace("ENTERING removeNode(node={})", node != null ? node.getNodeId() : "null");
        logger.info("Attempting to remove node from hash ring: {}",
                node != null ? node.getNodeId() : "null");

        // Validate input
        if (node == null) {
            logger.error("Cannot remove null node from hash ring");
            throw new IllegalArgumentException("Node cannot be null");
        }

        // Calculate hash for the node
        long nodeHash;
        try {
            nodeHash = hash(node.getNodeId());
            logger.debug("Calculated hash value {} for node {}", nodeHash, node.getNodeId());
        } catch (Exception e) {
            logger.error("Failed to calculate hash for node {}: {}", node.getNodeId(), e.getMessage(), e);
            throw e;
        }

        // Check if node exists
        if (!circle.containsKey(nodeHash)) {
            logger.warn("Node {} (hash {}) not found in hash ring - nothing to remove",
                    node.getNodeId(), nodeHash);
            return;
        }

        // Log current state before removal
        logger.debug("Current hash ring state before removal:");
        circle.forEach((k, v) -> logger.debug("Hash: {}, Node: {}", k, v.getNodeId()));

        // Perform removal
        try {
            NodeInfo removedNode = circle.remove(nodeHash);
            if (removedNode != null) {
                logger.info("Successfully removed node {} (hash {}) from hash ring",
                        node.getNodeId(), nodeHash);

                // Log new state after removal
                logger.debug("Updated hash ring state after removal:");
                circle.forEach((k, v) -> logger.debug("Hash: {}, Node: {}", k, v.getNodeId()));

                logger.debug("Removed node details: {}", removedNode);
            } else {
                logger.warn("No node was actually removed for hash {}", nodeHash);
            }
        } catch (Exception e) {
            logger.error("Failed to remove node {} (hash {}): {}",
                    node.getNodeId(), nodeHash, e.getMessage(), e);
            throw e;
        }

        logger.trace("EXITING removeNode()");
    }

    public synchronized NodeInfo getNode(String key) {
        // Method entry logging with parameters
        logger.trace("getNode() invoked for key='{}'", key);
        final long startTime = System.nanoTime();

        try {
            // Early exit for empty circle
            if (circle.isEmpty()) {
                logger.error("Hash ring empty! Cannot route key='{}'", key);
                return null;
            }

            // Hash computation
            final long h = hash(key);
            logger.debug("Key='{}' → hash={}", key, h);

            // Check for direct hit
            NodeInfo result = circle.get(h);
            if (result != null) {
                logger.debug("Direct hit: hash={} → node={}", h, result.getNodeId());
                return result;
            }

            // Tail map lookup
            final SortedMap<Long, NodeInfo> tailMap = circle.tailMap(h);
            final Long resolvedHash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
            result = circle.get(resolvedHash);

            // Detailed resolution logging

                if (tailMap.isEmpty()) {
                    logger.debug("Wrapping around: hash={} → firstNode={} (hash={})",
                            h, result.getNodeId(), resolvedHash);
                } else {
                    logger.debug("Clockwise resolution: hash={} → nextNode={} (hash={})",
                            h, result.getNodeId(), resolvedHash);
                }


            // Null check (should never happen if circle is consistent)
            if (result == null) {
                logger.error("Critical: Resolved hash={} but node is null! Key='{}'", resolvedHash, key);
            }

            return result;
        } finally {
            // Performance tracking
                final long duration = System.nanoTime() - startTime;
                logger.trace("getNode() completed for key='{}' in {} ns", key, duration);
        }
    }


    public synchronized NodeInfo getNextNode(String nodeId) {
        final String methodName = "getNextNodeId";
        logger.trace("[{}] Entry - node={}", methodName, nodeId != null ? nodeId : "null");
        final long startTime = System.nanoTime();

        try {
            // Early exit for empty circle
            if (circle.isEmpty()) {
                logger.warn("[{}] Hash ring is empty", methodName);
                return null;
            }

            // Validate input
            if (nodeId == null) {
                logger.error("[{}] Current node cannot be null", methodName);
                throw new IllegalArgumentException("Current node cannot be null");
            }

            // Log current ring state (only if debug enabled)
                logger.debug("[{}] Current ring state (size={}): {}", methodName, circle.size(),
                        circle.entrySet().stream()
                                .limit(5) // Only show first 5 nodes for efficiency
                                .map(e -> e.getValue().getNodeId() + "(h=" + e.getKey() + ")")
                                .collect(Collectors.joining(", ")) + (circle.size() > 5 ? "..." : ""));


            // Find next node
            final long currentHash = circle.get(hash(nodeId)).getHashValue();
            final SortedMap<Long, NodeInfo> tailMap = circle.tailMap(currentHash + 1);
            final NodeInfo nextNode = tailMap.isEmpty() ?
                    circle.get(circle.firstKey()) : // Wrap around
                    tailMap.get(tailMap.firstKey());

            // Log resolution path

                if (tailMap.isEmpty()) {
                    logger.debug("[{}] Wrapping around from {} to first node {} (h={})",
                            methodName, nodeId, nextNode.getNodeId(), nextNode.getHashValue());
                } else {
                    logger.debug("[{}] Found next node {} (h={}) after {} (h={})",
                            methodName, nextNode.getNodeId(), nextNode.getHashValue(),
                            nodeId, currentHash);
                }


            return nextNode;
        } finally {
            logger.trace("[{}] Exit - duration={}ns", methodName, System.nanoTime() - startTime);
        }
    }
    public synchronized NodeInfo getPreviousNode(String nodeId) {
        final String methodName = "getPreviousNode";
        logger.trace("[{}] Entry - node={}", methodName,
                nodeId != null ? nodeId : "null");
        final long startTime = System.nanoTime();

        try {
            // Early exit for empty circle
            if (circle.isEmpty()) {
                logger.warn("[{}] Hash ring is empty - no previous node exists", methodName);
                return null;
            }

            // Input validation
            if (nodeId == null) {
                logger.error("[{}] Current node cannot be null", methodName);
                throw new IllegalArgumentException("Current node cannot be null");
            }

            // Debug logging of ring state (limited to 5 nodes)
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Current ring state (size={}): {}", methodName, circle.size(),
                        circle.entrySet().stream()
                                .limit(5)
                                .map(e -> String.format("%s(h=%d)",
                                        e.getValue().getNodeId(), e.getKey()))
                                .collect(Collectors.joining(", ")) +
                                (circle.size() > 5 ? "..." : ""));
            }

            final long currentHash = circle.get(hash(nodeId)).getHashValue();
            final SortedMap<Long, NodeInfo> headMap = circle.headMap(currentHash);
            final NodeInfo previousNode = headMap.isEmpty() ?
                    circle.get(circle.lastKey()) : // Wrap around to tail
                    headMap.get(headMap.lastKey());

            // Detailed resolution logging

                if (headMap.isEmpty()) {
                    logger.debug("[{}] Wrapped around to last node {} (h={}) as previous to {} (h={})",
                            methodName, previousNode.getNodeId(), previousNode.getHashValue(),
                            nodeId, currentHash);
                } else {
                    logger.debug("[{}] Found previous node {} (h={}) for {} (h={})",
                            methodName, previousNode.getNodeId(), previousNode.getHashValue(),
                            nodeId, currentHash);
                }


            // Validate result (should never be null in consistent ring)
            if (previousNode == null) {
                logger.error("[{}] Critical: No previous node found for {} (h={}) in ring with {} nodes",
                        methodName, nodeId, currentHash, circle.size());
            }

            return previousNode;
        } finally {
            logger.trace("[{}] Exit - duration={}ns", methodName,
                    System.nanoTime() - startTime);
        }
    }

    public synchronized List<NodeInfo> getAllNodes() {
        final String methodName = "getAllNodes";
        logger.trace("[{}] Entry", methodName);
        final long startTime = System.nanoTime();

        try {
            // Check if circle is empty
            if (circle.isEmpty()) {
                logger.warn("[{}] Hash ring is empty - returning empty list", methodName);
                return Collections.emptyList();  // Better than new ArrayList<>()
            }

            // Create defensive copy while eliminating duplicates
            List<NodeInfo> uniqueNodes = new ArrayList<>(circle.size());  // Pre-size for efficiency
            Set<String> seenNodeIds = new HashSet<>(circle.size());

            for (NodeInfo node : circle.values()) {
                if (node == null) {
                    logger.warn("[{}] Found null node in hash ring", methodName);
                    continue;
                }

                if (seenNodeIds.add(node.getNodeId())) {
                    uniqueNodes.add(node);
                } else {
                    logger.debug("[{}] Filtered out duplicate node: {}", methodName, node.getNodeId());
                }
            }

            // Log results

                logger.debug("[{}] Returning {} unique nodes (from {} raw entries): {}",
                        methodName,
                        uniqueNodes.size(),
                        circle.size(),
                        uniqueNodes.stream()
                                .limit(5)  // Only show first 5 for efficiency
                                .map(NodeInfo::getNodeId)
                                .collect(Collectors.joining(", ")) +
                                (uniqueNodes.size() > 5 ? "..." : ""));


            return uniqueNodes;
        } finally {
            logger.trace("[{}] Exit - duration={}ns", methodName,
                    System.nanoTime() - startTime);
        }
    }


    public synchronized List<NodeInfo> getReplicaNodes(String key, int replicationFactor) {
        List<NodeInfo> replicas = new ArrayList<>();
        if (circle.isEmpty()) return replicas;

        long h = hash(key);
        SortedMap<Long, NodeInfo> tailMap = circle.tailMap(h);

        // Get nodes starting from the hash position
        Iterator<NodeInfo> iterator = tailMap.values().iterator();

        // If no nodes found in tail, wrap around to the beginning
        if (!iterator.hasNext()) {
            iterator = circle.values().iterator();
        }

        Set<String> addedNodes = new HashSet<>();
        while (iterator.hasNext() && replicas.size() < replicationFactor) {
            NodeInfo node = iterator.next();
            if (!addedNodes.contains(node.getNodeId())) {
                replicas.add(node);
                addedNodes.add(node.getNodeId());
            }
        }

        // If we need more nodes, wrap around
        if (replicas.size() < replicationFactor) {
            iterator = circle.values().iterator();
            while (iterator.hasNext() && replicas.size() < replicationFactor) {
                NodeInfo node = iterator.next();
                if (!addedNodes.contains(node.getNodeId())) {
                    replicas.add(node);
                    addedNodes.add(node.getNodeId());
                }
            }
        }

        return replicas;
    }

    public synchronized boolean isEmpty() {
        return circle.isEmpty();
    }

    public synchronized Map<Long, NodeInfo> getDebugMap() {
        return new LinkedHashMap<>(circle);
    }
}