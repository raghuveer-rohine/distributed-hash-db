package com.distributed.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Service
public class DataStorageService {

    private static final Logger logger = LoggerFactory.getLogger(DataStorageService.class);

    @Value("${replication.factor:2}")
    private int replicationFactor;

    private Map<String, String> primaryData = new ConcurrentHashMap<>();
    private Map<Integer, Map<String, String>> replicaData = new ConcurrentHashMap<>();

    private final AtomicLong replicaWriteCount = new AtomicLong(0);
    @PostConstruct
    public void init() {
        logger.info("Initializing DataStorageService with replication factor: {}", replicationFactor);

        if (replicationFactor < 1) {
            logger.error("Invalid replication factor: {}. Must be >= 1. Defaulting to 2", replicationFactor);
            replicationFactor = 2;
        }

        // Initialize replica stores
        for (int i = 1; i < replicationFactor; i++) {
            replicaData.put(i, new ConcurrentHashMap<>());
            logger.debug("Initialized replica store level: {}", i);
        }

        logger.info("Storage service initialized. {} replica levels configured", replicationFactor - 1);
    }

    public void putPrimary(String key, String value) {
        primaryData.put(key, value);
    }

    public void putAllPrimary(Map<String, String> data) {
        if (data != null) {
            primaryData.putAll(data);
            logger.debug("Added {} entries to primary data", data.size());
        }
    }
    public String getPrimary(String key) {
        return primaryData.get(key);
    }

    public void putReplica(int replicaIndex, String key, String value) {
        logger.trace("ENTER putReplica(replicaIndex={}, key={}, value={})", replicaIndex, key, value);
        Map<String, String> replica = replicaData.get(replicaIndex);
        if (replica != null) {
            replica.put(key, value);
            long count = replicaWriteCount.incrementAndGet();
            logger.debug("Replica write completed at index={} for key={}, total replica writes={}",
                    replicaIndex, key, count);
        } else {
            logger.warn("Replica index {} not initialized. Skipping write for key={}",
                    replicaIndex, key);
        }
        logger.trace("EXIT putReplica()");
    }

    public void putReplicaData(int replicaIndex, Map<String, String> data) {
        logger.trace("ENTER putReplicaData(replicaIndex={}, dataSize={})", replicaIndex,
                data != null ? data.size() : 0);

        Map<String, String> replica = replicaData.get(replicaIndex);
        if (replica != null && data != null) {
            replica.putAll(data);
            logger.debug("Added {} entries to replica at index {}", data.size(), replicaIndex);
        } else if (replica == null) {
            logger.warn("Replica index {} not initialized. Skipping bulk write", replicaIndex);
        }
        logger.trace("EXIT putReplicaData()");
    }

    public String getReplica(int replicaIndex, String key) {
        Map<String, String> replica = replicaData.get(replicaIndex);
        return replica != null ? replica.get(key) : null;
    }


    public void removeReplica(int replicaIndex, String key) {
        Map<String, String> replica = replicaData.get(replicaIndex);
        if (replica != null) {
            replica.remove(key);
        }
    }


    public int getReplicationFactor() {
        return replicationFactor;
    }

    // Transfer data from replica to primary during rebalancing
    public Map<String, String> promoteReplicaToPrimary(int replicaIndex) {
        Map<String, String> replica = replicaData.get(replicaIndex);
        if (replica != null && !replica.isEmpty()) {
            // Create a copy of the data before clearing
            Map<String, String> promotedData = new HashMap<>(replica);

            // Move data to primary
            primaryData.putAll(replica);

            // Clear the replica after copying
            replica.clear();

            logger.debug("Promoted {} entries from replica level {} to primary",
                    promotedData.size(), replicaIndex);

            return promotedData;
        } else {
            logger.debug("No data found at replica level {} to promote", replicaIndex);
            return new HashMap<>();
        }
    }

    // Extract and remove data within hash range from primary data
    public Map<String, String> extractDataInRange(long startHash, long endHash) {
        logger.debug("Extracting data in range {}-{} from primary data", startHash, endHash);

        Map<String, String> extractedData = new HashMap<>();
        Iterator<Map.Entry<String, String>> iterator = primaryData.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, String> entry = iterator.next();
            long keyHash = hash(entry.getKey());

            if (isInRange(keyHash, startHash, endHash)) {
                extractedData.put(entry.getKey(), entry.getValue());
                iterator.remove(); // Remove from primary data
                logger.trace("Extracted key {} (hash={}) from primary data", entry.getKey(), keyHash);
            }
        }

        logger.debug("Extracted {} keys from primary data in specified range", extractedData.size());
        return extractedData;
    }


    // Extract and remove all data from specified replica index
    public Map<String, String> extractReplicaData(int replicaIndex) {
        logger.debug("Extracting all data from replica index {}", replicaIndex);

        Map<String, String> replica = replicaData.get(replicaIndex);
        if (replica == null) {
            logger.warn("Replica index {} not found", replicaIndex);
            return new HashMap<>();
        }

        Map<String, String> extractedData = new HashMap<>(replica);
        replica.clear(); // Clear the replica data

        logger.debug("Extracted {} keys from replica index {}", extractedData.size(), replicaIndex);
        return extractedData;
    }

    private long hash(String key) {
        return com.google.common.hash.Hashing.murmur3_32_fixed()
                .hashString(key, java.nio.charset.StandardCharsets.UTF_8).asInt() & 0xffffffffL;
    }

    private boolean isInRange(long keyHash, long startHash, long endHash) {
        if (startHash <= endHash) {
            return keyHash >= startHash && keyHash <= endHash;
        } else {
            // Handle wrap around
            return keyHash >= startHash || keyHash <= endHash;
        }
    }

    public Map<String, String> getPrimaryData() {
        return new HashMap<>(primaryData);
    }

    public Map<String, String> getReplicaData(int replicaIndex) {
        Map<String, String> replica = replicaData.get(replicaIndex);
        return replica != null ? new HashMap<>(replica) : new HashMap<>();
    }

    public Map<Integer, Map<String, String>> getReplicaData() {
        return Collections.unmodifiableMap(replicaData);
    }

    // Get data within hash range for rebalancing
    public Map<String, String> getDataInRange(long startHash, long endHash, boolean fromPrimary) {
        Map<String, String> result = new HashMap<>();
        Map<String, String> sourceData = fromPrimary ? primaryData : replicaData.get(1);

        if (sourceData == null) return result;

        for (Map.Entry<String, String> entry : sourceData.entrySet()) {
            long keyHash = hash(entry.getKey());
            if (isInRange(keyHash, startHash, endHash)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public boolean deletePrimary(String key) {
        logger.trace("ENTER deletePrimary(key={})", key);
        String removedValue = primaryData.remove(key);
        boolean existed = removedValue != null;

        if (existed) {
            logger.debug("Successfully deleted key {} from primary storage", key);
        } else {
            logger.debug("Key {} not found in primary storage", key);
        }

        logger.trace("EXIT deletePrimary() -> existed={}", existed);
        return existed;
    }

    public boolean deleteReplica(int replicaIndex, String key) {
        logger.trace("ENTER deleteReplica(replicaIndex={}, key={})", replicaIndex, key);

        Map<String, String> replica = replicaData.get(replicaIndex);
        if (replica == null) {
            logger.warn("Replica index {} not initialized. Cannot delete key={}", replicaIndex, key);
            logger.trace("EXIT deleteReplica() -> existed=false (no replica)");
            return false;
        }

        String removedValue = replica.remove(key);
        boolean existed = removedValue != null;

        if (existed) {
            logger.debug("Successfully deleted key {} from replica at index {}", key, replicaIndex);
        } else {
            logger.debug("Key {} not found in replica at index {}", key, replicaIndex);
        }

        logger.trace("EXIT deleteReplica() -> existed={}", existed);
        return existed;
    }

    public void removePrimary(String key) {
        primaryData.remove(key);
    }


    public void clearPrimary() {
        primaryData.clear();
    }

    public void clearReplica(int replicaIndex) {
        Map<String, String> replica = replicaData.get(replicaIndex);
        if (replica != null) {
            replica.clear();
        }
    }

}
