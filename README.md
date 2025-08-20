# 🚀 Distributed Hash Table Database

A highly scalable, fault-tolerant distributed database system built with Spring Boot that implements consistent hashing for automatic data distribution and replication across multiple nodes.

## 📋 Table of Contents

- [🎯 Overview](#-overview)
- [✨ Key Features](#-key-features)
- [🏗️ Architecture](#-architecture)
- [🔧 Prerequisites](#-prerequisites)
- [🚀 Quick Start](#-quick-start)
- [📖 API Documentation](#-api-documentation)
- [🔧 Configuration](#-configuration)
- [💾 Data Operations](#-data-operations)
- [🔄 Node Management](#-node-management)
- [📊 Monitoring & Health Checks](#-monitoring--health-checks)
- [🧪 Testing](#-testing)
- [🔍 Troubleshooting](#-troubleshooting)

## 🎯 Overview

This distributed database system provides a scalable solution for storing and retrieving key-value pairs across multiple nodes. It uses **consistent hashing** to automatically distribute data and ensures high availability through **configurable replication**.

### Why Use This Database?

- **Automatic Scaling**: Add/remove nodes dynamically without downtime
- **High Availability**: Data is replicated across multiple nodes
- **Consistent Hashing**: Minimizes data movement during cluster changes
- **Request Routing**: Send requests to any node - data is automatically routed
- **Self-Healing**: Automatic data rebalancing when nodes join/leave

## ✨ Key Features

### 🔄 Dynamic Node Management
- **Automatic Node Discovery**: Nodes automatically join the cluster on startup via Consul
- **Self-Organizing Ring**: Hash ring automatically reorganizes based on node hash values
- **Zero-Downtime Scaling**: Add or remove nodes without service interruption
- **Automatic Data Rebalancing**: Data migrates automatically when topology changes

### 📦 Data Distribution & Replication
- **Consistent Hashing**: Uses Murmur3 hash function for uniform data distribution
- **Configurable Replication Factor**: Set replication level (default: 2) for fault tolerance
- **Primary-Replica Model**: Data stored on primary node + downstream replicas
- **Quorum Consistency**: Maintains data consistency across replica nodes

### 🌐 Smart Request Routing
- **Any-Node Access**: Send requests to any node in the cluster
- **Automatic Forwarding**: Requests automatically routed to correct node
- **Transparent Operations**: Clients don't need to know data location
- **Load Distribution**: Requests can be distributed across any available node

### 🛡️ Fault Tolerance
- **Node Failure Handling**: System continues operating when nodes fail
- **Automatic Recovery**: Failed node's data automatically served by replicas
- **Data Redundancy**: Configurable replication ensures data survival
- **Service Discovery**: Consul-based service registration and discovery

## 🏗️ Architecture

### System Components

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Node 8080     │    │   Node 8081     │    │   Node 8082     │
│                 │    │                 │    │                 │
│ ┌─────────────┐ │    │ ┌─────────────┐ │    │ ┌─────────────┐ │
│ │   Primary   │ │    │ │   Primary   │ │    │ │   Primary   │ │
│ │    Data     │ │    │ │    Data     │ │    │ │    Data     │ │
│ └─────────────┘ │    │ └─────────────┘ │    │ └─────────────┘ │
│ ┌─────────────┐ │    │ ┌─────────────┐ │    │ ┌─────────────┐ │
│ │   Replica   │ │    │ │   Replica   │ │    │ │   Replica   │ │
│ │    Data     │ │    │ │    Data     │ │    │ │    Data     │ │
│ └─────────────┘ │    │ └─────────────┘ │    │ └─────────────┘ │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         └───────────────────────┼───────────────────────┘
                                 │
                    ┌─────────────────────┐
                    │     Consul          │
                    │ Service Discovery   │
                    │                     │
                    │  Registered Nodes:  │
                    │  - Node 8080        │
                    │  - Node 8081        │
                    │  - Node 8082        │
                    └─────────────────────┘
                                 │
                    ┌─────────────────────┐
                    │  Consistent Hash    │
                    │      Ring           │
                    │                     │
                    │  Node Positions:    │
                    │  8080: hash_value_1 │
                    │  8081: hash_value_2 │
                    │  8082: hash_value_3 │
                    └─────────────────────┘
```

### Data Flow

1. **Write Request**: Client sends PUT request to any node
2. **Hash Calculation**: Node calculates hash of the key
3. **Target Resolution**: Hash ring determines which node owns the key
4. **Request Routing**: If not local, request is forwarded to target node
5. **Primary Storage**: Data is stored on the target (primary) node
6. **Replication**: Data is replicated to downstream nodes based on replication factor
7. **Response**: Success confirmation sent back to client

## 🔧 Prerequisites

### Required Software

- **Java 11+** (JDK 11 or higher)
- **Maven 3.6+**
- **Docker** (for running Consul)
- **Network Access** between nodes (if running on different machines)

### Consul Service Discovery Setup

The system uses Consul for service discovery. You need to run Consul before starting the database nodes.

#### Start Consul Server

```bash
# Create Docker network for Consul
docker network create consul-net

# Start Consul server
docker run -d --name=consul-server \
  --net=consul-net \
  -p 8500:8500 \
  -e CONSUL_BIND_INTERFACE=eth0 \
  hashicorp/consul:1.21 agent -server -bootstrap-expect=1 \
  -node=server-1 \
  -client=0.0.0.0 -ui \
  -data-dir=/consul/data
```

#### Verify Consul is Running

```bash
# Check Consul status
curl http://localhost:8500/v1/status/leader

# Access Consul UI (optional)
# Open browser to: http://localhost:8500/ui
```

#### Consul Configuration in Application

The database nodes are configured to register with Consul automatically:

```properties
# Consul configuration (in application.properties)
spring.cloud.consul.host=localhost
spring.cloud.consul.port=8500
spring.cloud.consul.discovery.enabled=true
spring.cloud.consul.discovery.register=true
spring.cloud.consul.discovery.instance-id=${spring.application.name}-${server.port}
spring.cloud.consul.discovery.health-check-path=/api/health
spring.cloud.consul.discovery.health-check-interval=10s
```

## 🚀 Quick Start

### Step 1: Start Consul

First, ensure Consul is running:

```bash
# If not already running, start Consul
docker run -d --name=consul-server \
  --net=consul-net \
  -p 8500:8500 \
  -e CONSUL_BIND_INTERFACE=eth0 \
  hashicorp/consul:1.21 agent -server -bootstrap-expect=1 \
  -node=server-1 \
  -client=0.0.0.0 -ui \
  -data-dir=/consul/data

# Wait for Consul to be ready
sleep 10

# Verify Consul is running
curl http://localhost:8500/v1/status/leader
```

### Step 2: Option A - Using the Automated Test Script

The easiest way to start the database cluster with automatic load testing.

#### 🎬 Script Features
- **Automatic Startup**: Starts all nodes with proper delays
- **Consul Integration**: Waits for nodes to register with Consul
- **Health Monitoring**: Waits for all nodes to be healthy
- **Load Testing**: Runs configurable number of test requests
- **Process Management**: Automatic cleanup on exit

#### Usage

```bash
# Make script executable (first time only)
chmod +x test_db.sh

# Start cluster and run default 20 test requests
./test_db.sh

# Start cluster and run 50 test requests
./test_db.sh 50

# Start cluster only (no load test)
./test_db.sh --start-only

# Run load test only (assume cluster is running)
./test_db.sh --test-only 30

# Show help
./test_db.sh --help
```

### Step 2: Option B - Manual Node Startup

For more control over individual nodes or custom configurations.

#### Start Database Nodes

```bash
# Terminal 1: Start first node
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --replication.factor=2"

# Wait 10-15 seconds, then Terminal 2: Start second node
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --replication.factor=2"

# Wait 10-15 seconds, then Terminal 3: Start third node
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8082 --replication.factor=2"
```

#### Verify Cluster Formation

```bash
# Check nodes registered with Consul
curl http://localhost:8500/v1/agent/services

# Check database cluster health
curl http://localhost:8080/api/nodes
```

#### Important Setup Notes

⚠️ **Critical Requirements:**
- **Consul First**: Always start Consul before database nodes
- **Same Replication Factor**: All nodes MUST have the same replication factor
- **Sequential Startup**: Wait 10-15 seconds between starting each node
- **Network Connectivity**: Ensure all nodes can communicate with Consul and each other

## 📖 API Documentation

### Core Data Operations

#### Store Data
```http
POST /api/data
Content-Type: application/json

{
  "key": "user:123",
  "value": "John Doe"
}
```

**Response:**
```json
{
  "value": "John Doe",
  "success": true
}
```

#### Retrieve Data
```http
GET /api/data/{key}
```

**Example:**
```bash
curl http://localhost:8080/api/data/user:123
```

**Response:**
```json
{
  "value": "John Doe",
  "success": true
}
```

#### Delete Data
```http
DELETE /api/data/{key}
```

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/data/user:123
```

**Response:**
```json
{
  "value": "Key deleted successfully",
  "success": true
}
```

### Node Management Operations

#### Get All Nodes
```http
GET /api/nodes
```

**Response:**
```json
{
  "192.168.1.7:8080": 1234567890,
  "192.168.1.7:8081": 2345678901,
  "192.168.1.7:8082": 3456789012
}
```

#### Health Check
```http
GET /api/health
```

**Response:**
```
OK
```

#### Get All Data from Node
```http
GET /api/data/all
```

**Response:**
```json
{
  "primary": {
    "key1": "value1",
    "key2": "value2"
  },
  "replicas": {
    "1": {
      "key3": "value3"
    },
    "2": {
      "key4": "value4"
    }
  }
}
```

## 🔧 Configuration

### Application Properties

```properties
# Server Configuration
server.port=8080

# Replication Configuration
replication.factor=2

# Consul Configuration
spring.cloud.consul.host=localhost
spring.cloud.consul.port=8500
spring.cloud.consul.discovery.enabled=true
spring.cloud.consul.discovery.register=true
spring.cloud.consul.discovery.instance-id=${spring.application.name}-${server.port}
spring.cloud.consul.discovery.service-name=distributed-database
spring.cloud.consul.discovery.health-check-path=/api/health
spring.cloud.consul.discovery.health-check-interval=10s
spring.cloud.consul.discovery.health-check-timeout=5s
spring.cloud.consul.discovery.health-check-critical-timeout=30s

# Logging Configuration
logging.level.com.yourpackage.distributed=DEBUG
logging.level.com.yourpackage.distributed.ConsistentHashRing=TRACE
```

### Environment Variables

```bash
# Set replication factor
export REPLICATION_FACTOR=3

# Set server port
export SERVER_PORT=8080

# Set Consul host
export CONSUL_HOST=localhost
export CONSUL_PORT=8500
```

### Consul Environment Variables

```bash
# For Docker Consul setup
export CONSUL_BIND_INTERFACE=eth0
export CONSUL_CLIENT_ADDR=0.0.0.0
export CONSUL_DATA_DIR=/consul/data
```

## 💾 Data Operations

### Understanding Data Placement

The system uses **consistent hashing** to determine where data is stored:

1. **Key Hashing**: Each key is hashed using Murmur3
2. **Ring Position**: Hash value determines position on the ring
3. **Primary Node**: First node clockwise from hash position owns the key
4. **Replica Nodes**: Next N nodes (based on replication factor) store replicas

### Replication Strategy

- **Primary**: Node responsible for key based on hash
- **Replica 1**: Next node clockwise on ring
- **Replica N**: N-th node clockwise from primary
- **Wrap-around**: Ring wraps from highest to lowest hash

### Data Consistency

- **Write Consistency**: All replicas updated synchronously
- **Read Consistency**: Data read from primary node
- **Failure Handling**: Replicas serve data if primary fails

## 🔄 Node Management

### Adding Nodes

#### Automatic Addition (Recommended)
```bash
# Simply start new node - it auto-registers with Consul
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8085 --replication.factor=2"
```

**What Happens:**
1. New node registers with Consul
2. Other nodes discover new node via Consul
3. Hash ring automatically includes new node
4. Data rebalancing begins automatically
5. Some keys migrate to new node

### Removing Nodes

#### Graceful Removal
1. Stop the node process (Ctrl+C)
2. Node deregisters from Consul automatically
3. Other nodes detect removal via Consul
4. Replicas automatically serve data
5. System continues with remaining nodes

### Monitoring Node Health with Consul

```bash
# Check services in Consul
curl http://localhost:8500/v1/agent/services

# Check health status in Consul
curl http://localhost:8500/v1/health/service/distributed-database

# Check database cluster health
curl http://localhost:8080/api/health
curl http://localhost:8080/api/nodes
```

## 📊 Monitoring & Health Checks

### Health Endpoints

#### Basic Health Check
```bash
curl http://localhost:8080/api/health
# Response: OK
```

#### Cluster Health via Consul
```bash
# Check all services
curl http://localhost:8500/v1/agent/services

# Check service health
curl http://localhost:8500/v1/health/service/distributed-database?pretty
```

#### Cluster Topology
```bash
curl http://localhost:8080/api/nodes
# Shows all nodes discovered via Consul
```

### Log Monitoring

#### Key Log Patterns
```bash
# Monitor Consul registration
grep -i "consul\|registration\|discovery" app.log

# Monitor hash ring changes
grep "Added node\|Removed node" app.log

# Monitor data operations  
grep "PUT operation\|GET operation\|DELETE operation" app.log

# Monitor replication
grep "Replicating key\|replication" app.log
```

## 🧪 Testing

### Manual Testing

#### Basic CRUD Operations
```bash
# Ensure Consul is running
curl http://localhost:8500/v1/status/leader

# Store data
curl -X POST http://localhost:8080/api/data \
  -H "Content-Type: application/json" \
  -d '{"key": "test:1", "value": "Hello World"}'

# Retrieve data  
curl http://localhost:8080/api/data/test:1

# Delete data
curl -X DELETE http://localhost:8080/api/data/test:1
```

#### Cross-Node Testing
```bash
# Store on node 8080
curl -X POST http://localhost:8080/api/data \
  -H "Content-Type: application/json" \
  -d '{"key": "cross:test", "value": "Node 8080"}'

# Retrieve from node 8081 (should work!)
curl http://localhost:8081/api/data/cross:test
```

### Load Testing with Script

```bash
# Light load test
./test_db.sh 10

# Heavy load test  
./test_db.sh 100

# Stress test
./test_db.sh 1000
```

### Fault Tolerance Testing

```bash
# Start cluster
./test_db.sh --start-only

# Store test data
curl -X POST http://localhost:8080/api/data \
  -H "Content-Type: application/json" \
  -d '{"key": "fault:test", "value": "Fault Tolerance Test"}'

# Stop one node (Ctrl+C)
# Node will deregister from Consul

# Verify data still accessible from remaining nodes
curl http://localhost:8081/api/data/fault:test
curl http://localhost:8082/api/data/fault:test

# Check Consul shows reduced node count
curl http://localhost:8500/v1/health/service/distributed-database
```

## 🔍 Troubleshooting

### Common Issues

#### Issue: "No nodes available"
**Symptoms:**
```json
{"value": "No nodes available", "success": false}
```

**Causes & Solutions:**

1. **Consul Not Running**
   ```bash
   # Check Consul status
   curl http://localhost:8500/v1/status/leader
   
   # Start Consul if needed
   docker start consul-server
   ```

2. **Nodes Not Registered with Consul**
   ```bash
   # Check registered services
   curl http://localhost:8500/v1/agent/services
   
   # Restart nodes if missing
   ```

3. **Service Discovery Issues**
   ```bash
   # Check logs for Consul connection errors
   grep -i "consul\|discovery" app.log
   ```

#### Issue: Node registration problems

**Symptoms:**
- Nodes don't appear in `/api/nodes`
- Service not visible in Consul UI

**Solutions:**

1. **Check Consul Connectivity**
   ```bash
   # Test Consul API
   curl http://localhost:8500/v1/agent/services
   
   # Check Docker container
   docker ps | grep consul
   ```

2. **Verify Application Properties**
   ```properties
   spring.cloud.consul.host=localhost
   spring.cloud.consul.port=8500
   spring.cloud.consul.discovery.enabled=true
   ```

3. **Check Firewall/Network**
   ```bash
   # Test port connectivity
   telnet localhost 8500
   ```

#### Issue: Data inconsistency after node failure

**Solutions:**

1. **Check Consul Service Health**
   ```bash
   curl http://localhost:8500/v1/health/service/distributed-database
   ```

2. **Verify Replication**
   ```bash
   curl http://localhost:8080/api/data/all
   curl http://localhost:8081/api/data/all
   ```

3. **Manual Cleanup** (if needed)
   ```bash
   # Remove failed service from Consul
   curl -X PUT http://localhost:8500/v1/agent/service/deregister/SERVICE_ID
   ```

### Consul-Specific Troubleshooting

#### Consul Container Issues

```bash
# Check if Consul container is running
docker ps | grep consul

# Check Consul logs
docker logs consul-server

# Restart Consul if needed
docker restart consul-server

# Clean start Consul
docker rm -f consul-server
docker run -d --name=consul-server \
  --net=consul-net \
  -p 8500:8500 \
  -e CONSUL_BIND_INTERFACE=eth0 \
  hashicorp/consul:1.21 agent -server -bootstrap-expect=1 \
  -node=server-1 \
  -client=0.0.0.0 -ui \
  -data-dir=/consul/data
```

#### Service Discovery Debug

```bash
# List all registered services
curl http://localhost:8500/v1/catalog/services

# Get details of specific service
curl http://localhost:8500/v1/catalog/service/distributed-database

# Check health checks
curl http://localhost:8500/v1/health/checks/distributed-database
```

## 🚨 Important Notes & Best Practices

### ⚠️ Critical Requirements

1. **Start Consul First**: Always ensure Consul is running before starting database nodes
   ```bash
   # Check Consul status before starting nodes
   curl http://localhost:8500/v1/status/leader
   ```

2. **Consistent Replication Factor**: All nodes MUST use the same replication factor
   ```bash
   # ✅ Correct - all nodes RF=2
   mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --replication.factor=2"
   mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --replication.factor=2"
   ```

3. **Sequential Startup**: Allow time between node startups for proper Consul registration
   ```bash
   # ✅ Correct - wait between starts
   mvn spring-boot:run ... &
   sleep 10-15
   mvn spring-boot:run ... &
   ```

4. **Network Connectivity**: Ensure all nodes can reach Consul and each other
   ```bash
   # Test Consul connectivity from each node's machine
   curl http://consul-host:8500/v1/status/leader
   ```

### 🎯 Production Deployment Best Practices

#### Consul Production Setup
```bash
# Production Consul cluster (3 nodes minimum)
docker run -d --name=consul-1 \
  -p 8500:8500 \
  hashicorp/consul:1.21 agent -server -bootstrap-expect=3 \
  -node=consul-1 \
  -client=0.0.0.0 -ui

# Add additional Consul nodes for high availability
# Configure proper ACLs and TLS for production
```

#### Health Monitoring
```bash
# Monitor both Consul and database health
curl http://consul:8500/v1/health/service/distributed-database
curl http://database-node:8080/api/health
```

#### Backup Strategy
```bash
# Backup Consul data
consul snapshot save backup.snap

# Backup database state
for port in 8080 8081 8082; do
  curl http://localhost:$port/api/data/primary > backup_$port.json
done
```

---

## 📞 Support & Contributing

### Getting Help
- **Consul Issues**: Check Docker logs and Consul UI at http://localhost:8500
- **Database Issues**: Check application logs and ensure Consul connectivity
- **Performance**: Provide cluster topology and load patterns

### Quick Reference

#### Essential Commands
```bash
# Start Consul
docker run -d --name=consul-server --net=consul-net -p 8500:8500 \
  -e CONSUL_BIND_INTERFACE=eth0 hashicorp/consul:1.21 \
  agent -server -bootstrap-expect=1 -node=server-1 -client=0.0.0.0 -ui

# Verify Consul
curl http://localhost:8500/v1/status/leader

# Start database cluster
./test_db.sh

# Check cluster health
curl http://localhost:8080/api/nodes
curl http://localhost:8500/v1/health/service/distributed-database
```

#### Important URLs
- **Consul UI**: `http://localhost:8500/ui`
- **Consul API**: `http://localhost:8500/v1/`
- **Database Health**: `http://node:port/api/health`
- **Cluster Info**: `http://node:port/api/nodes`

---

*Happy Distributed Computing with Consul Service Discovery! 🎉*
