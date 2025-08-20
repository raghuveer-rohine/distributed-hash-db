package com.distributed.controller;


import com.distributed.dto.*;
import com.distributed.service.ConsistentHashRing;
import com.distributed.service.DataStorageService;
import com.distributed.service.DatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DatabaseController {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseController.class);
    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private DataStorageService dataStorageService;

    /*To put and update the key-value pair */
    @Autowired
    private ConsistentHashRing hashRing;
    @PostMapping("/data")
    public DataResponse put(@RequestBody DataRequest request) {
        return databaseService.put(request.getKey(), request.getValue());
    }

    // To get the value
    @GetMapping("/data/{key}")
    public DataResponse get(@PathVariable String key) {
        return databaseService.get(key);
    }

    //To put the replica data
    @PostMapping("/replica/{replicaIndex}")
    public void putReplica(@PathVariable int replicaIndex, @RequestBody DataRequest request) {
        databaseService.putReplica(replicaIndex, request.getKey(), request.getValue());
    }

    // To rebalance when there is node addition or removal
    @PostMapping("/rebalance")
    public RebalanceResponse rebalance(@RequestBody RebalanceRequest request) {
        logger.info("Received rebalance request for operation: {}", request.getOperation());
        RebalanceResponse response = databaseService.handleRebalance(request);
        logger.info("Completed rebalance request with success: {}", response.isSuccess());
        return response;
    }

    // For simple health check
    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    // To get both primary and secondary data from the current node
    @GetMapping("/data/all")
    public Map<String, Object> getAllData() {
        Map<String, Object> response = new HashMap<>();
        response.put("primary", dataStorageService.getPrimaryData());
        response.put("replicas", dataStorageService.getReplicaData());
        return response;
    }

    // To add bulk replica data
    @PostMapping("/replica/bulk/{replicaIndex}")
    public DataResponse putBulkReplica(@PathVariable int replicaIndex, @RequestBody BulkDataRequest request) {
        logger.info("Received bulk replica data: {} entries at replica level {}",
                request.getData().size(), replicaIndex);

        databaseService.putBulkReplica(replicaIndex, request.getData());

        return new DataResponse("Bulk replica data stored successfully", true);
    }


    // To get only primary data from the current node
    @GetMapping("/data/primary")
    public Map<String, String> getAllPrimaryData() {
        logger.debug("Request received to fetch all primary data");
        Map<String, String> primaryData = dataStorageService.getPrimaryData();
        logger.debug("Returning {} primary data entries", primaryData.size());
        return primaryData;
    }

    // To get all the nodes info along with their hash values sorted in ascending order
    @GetMapping("/nodes")
    public Map<String, Long> getNodesInfo() {
        logger.info("GET /nodes - Retrieving all nodes with hash values");

        try {
            List<NodeInfo> allNodes = hashRing.getAllNodes();

            // Create map with ip:port as key and hash as value, sorted by hash value
            Map<String, Long> nodesMap = allNodes.stream()
                    .sorted((a, b) -> Long.compare(a.getHashValue(), b.getHashValue())) // Sort by hash ascending
                    .collect(LinkedHashMap::new, // Preserve order
                            (map, node) -> { map.put(node.getNodeId(), node.getHashValue()); },
                            HashMap::putAll);

            logger.info("Successfully retrieved {} nodes information", nodesMap.size());
            return nodesMap;

        } catch (Exception e) {
            logger.error("Error retrieving nodes information: {}", e.getMessage(), e);
            return new LinkedHashMap<>(); // Return empty map on error
        }
    }

    // To delete a key-value on the basis of a key
    @DeleteMapping("/data/{key}")
    public DataResponse delete(@PathVariable String key) {
        logger.info("DELETE request received for key: {}", key);
        return databaseService.delete(key);
    }

    // To delete the secondary(replica) data on the basis of replicaIndex and key
    @DeleteMapping("/replica/{key}")
    public ResponseEntity<Void> deleteReplica(@PathVariable String key,
                                              @RequestParam int replicaIndex) {
        logger.info("DELETE replica request received for key: {} at replica index: {}", key, replicaIndex);

        boolean existed = dataStorageService.deleteReplica(replicaIndex, key);

        if (existed) {
            logger.debug("Replica key {} deleted from index {}", key, replicaIndex);
            return ResponseEntity.ok().build();
        } else {
            logger.debug("Replica key {} not found at index {}", key, replicaIndex);
            return ResponseEntity.notFound().build();
        }
    }

}
