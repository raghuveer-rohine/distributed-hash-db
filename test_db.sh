#!/bin/bash

# Complete Distributed Database Startup and Load Test Script
# This script starts all instances and then runs load tests

# Configuration
BASE_HOST="192.168.1.7"
PORTS=(8080 8081 8082 8083 8084)
REPLICATION_FACTOR=2
NUM_REQUESTS=${1:-20}  # Default to 20 requests if not specified

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Arrays to store PIDs for cleanup
declare -a APP_PIDS=()

# Test data arrays
CITIES=("Mumbai" "Delhi" "Bangalore" "Chennai" "Kolkata" "Hyderabad" "Pune" "Ahmedabad" "Jaipur" "Lucknow")
COUNTRIES=("India" "USA" "UK" "Canada" "Australia" "Germany" "France" "Japan" "Singapore" "Dubai")
PRODUCTS=("Laptop" "Mobile" "Tablet" "Monitor" "Keyboard" "Mouse" "Headphones" "Speaker" "Camera" "Printer")
COMPANIES=("TCS" "Infosys" "Wipro" "Microsoft" "Google" "Amazon" "Apple" "Meta" "Netflix" "Tesla")

# Function to cleanup processes on exit
cleanup() {
    echo -e "\n${YELLOW}Cleaning up processes...${NC}"
    for pid in "${APP_PIDS[@]}"; do
        if kill -0 "$pid" 2>/dev/null; then
            echo -e "${YELLOW}Stopping process $pid${NC}"
            kill "$pid" 2>/dev/null
        fi
    done

    # Wait a bit and force kill if necessary
    sleep 3
    for pid in "${APP_PIDS[@]}"; do
        if kill -0 "$pid" 2>/dev/null; then
            echo -e "${RED}Force stopping process $pid${NC}"
            kill -9 "$pid" 2>/dev/null
        fi
    done

    echo -e "${GREEN}Cleanup completed${NC}"
}

# Set trap to cleanup on script exit
trap cleanup EXIT INT TERM

# Function to start a single instance
start_instance() {
    local port=$1
    echo -e "${CYAN}Starting instance on port $port...${NC}"

    # Start the Spring Boot application in background
    mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=$port --replication.factor=$REPLICATION_FACTOR" > "app_$port.log" 2>&1 &
    local pid=$!
    APP_PIDS+=("$pid")

    echo -e "${GREEN}✓ Started instance on port $port (PID: $pid)${NC}"
    return 0
}

# Function to check if port is responding
check_port_health() {
    local port=$1
    local max_attempts=30
    local attempt=1

    echo -e "${BLUE}Checking health of port $port...${NC}"

    while [ $attempt -le $max_attempts ]; do
        if curl -s --connect-timeout 3 "http://$BASE_HOST:$port/api/nodes" > /dev/null 2>&1; then
            echo -e "${GREEN}✓ Port $port is healthy${NC}"
            return 0
        fi

        echo -e "${YELLOW}Attempt $attempt/$max_attempts - Port $port not ready yet...${NC}"
        sleep 2
        ((attempt++))
    done

    echo -e "${RED}✗ Port $port failed to become healthy${NC}"
    return 1
}

# Function to get random element from array
get_random_element() {
    local arr=("$@")
    local rand_index=$((RANDOM % ${#arr[@]}))
    echo "${arr[$rand_index]}"
}

# Function to get random port
get_random_port() {
    local rand_index=$((RANDOM % ${#PORTS[@]}))
    echo "${PORTS[$rand_index]}"
}

# Function to send PUT request
send_request() {
    local port=$1
    local key=$2
    local value=$3
    local url="http://${BASE_HOST}:${port}/api/data"

    echo -e "${BLUE}[$(date '+%H:%M:%S')] Sending to port ${port}: ${key} = ${value}${NC}"

    # Use temp file approach for macOS compatibility
    temp_file=$(mktemp)
    http_code=$(curl -s -w "%{http_code}" -X POST "$url" \
        -H "Content-Type: application/json" \
        -d "{\"key\": \"$key\", \"value\": \"$value\"}" \
        -o "$temp_file")

    response_body=$(cat "$temp_file")
    rm -f "$temp_file"

    if [ "$http_code" -eq 200 ] || [ "$http_code" -eq 201 ]; then
        echo -e "${GREEN}✓ Success (${http_code}): ${response_body}${NC}"
        return 0
    else
        echo -e "${RED}✗ Failed (${http_code}): ${response_body}${NC}"
        return 1
    fi
}

# Function to run load test
run_load_test() {
    local total_requests=$1
    local success_count=0
    local failed_count=0

    echo -e "${PURPLE}===========================================${NC}"
    echo -e "${PURPLE}  Starting Load Test${NC}"
    echo -e "${PURPLE}===========================================${NC}"
    echo -e "Target Host: ${BASE_HOST}"
    echo -e "Ports: ${PORTS[*]}"
    echo -e "Total Requests: ${total_requests}"
    echo -e "${PURPLE}===========================================${NC}"
    echo ""

    for ((i=1; i<=total_requests; i++)); do
        # Generate random key-value pair
        case $((RANDOM % 5)) in
            0)
                # City data
                city=$(get_random_element "${CITIES[@]}")
                key="city_$(echo "$city" | tr '[:upper:]' '[:lower:]')"
                value="$city"
                ;;
            1)
                # Country data
                country=$(get_random_element "${COUNTRIES[@]}")
                key="country_$(echo "$country" | tr '[:upper:]' '[:lower:]')"
                value="$country"
                ;;
            2)
                # Product data
                product=$(get_random_element "${PRODUCTS[@]}")
                key="product_$(echo "$product" | tr '[:upper:]' '[:lower:]')"
                value="$product"
                ;;
            3)
                # Company data
                company=$(get_random_element "${COMPANIES[@]}")
                key="company_$(echo "$company" | tr '[:upper:]' '[:lower:]')"
                value="$company"
                ;;
        esac

        # Get random port
        port=$(get_random_port)

        # Send request
        echo -e "${YELLOW}Request $i/$total_requests:${NC}"
        if send_request "$port" "$key" "$value"; then
            ((success_count++))
        else
            ((failed_count++))
        fi

        echo ""

        # Small delay between requests
        sleep 0.5
    done

    # Summary
    echo -e "${PURPLE}===========================================${NC}"
    echo -e "${PURPLE}  Load Test Summary${NC}"
    echo -e "${PURPLE}===========================================${NC}"
    echo -e "${GREEN}Successful requests: $success_count${NC}"
    echo -e "${RED}Failed requests: $failed_count${NC}"
    echo -e "Total requests: $total_requests"
    echo -e "Success rate: $(( (success_count * 100) / total_requests ))%"
    echo -e "${PURPLE}===========================================${NC}"
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [OPTIONS] [NUMBER_OF_REQUESTS]"
    echo ""
    echo "Options:"
    echo "  -h, --help          Show this help message"
    echo "  -s, --start-only    Start instances only (no load test)"
    echo "  -t, --test-only     Run load test only (assume instances running)"
    echo ""
    echo "Examples:"
    echo "  $0                  # Start instances and run 20 requests"
    echo "  $0 50               # Start instances and run 50 requests"
    echo "  $0 -s               # Start instances only"
    echo "  $0 -t 30            # Run 30 test requests only"
}

# Function to display nodes information
show_nodes_info() {
    echo -e "${CYAN}Fetching nodes information...${NC}"
    for port in "${PORTS[@]}"; do
        echo -e "${BLUE}Nodes from port $port:${NC}"
        curl -s "http://$BASE_HOST:$port/api/nodes" | python3 -m json.tool 2>/dev/null || echo "Failed to get nodes info from port $port"
        echo ""
    done
}

# Main script execution
case "$1" in
    -h|--help)
        show_usage
        exit 0
        ;;
    -t|--test-only)
        echo -e "${YELLOW}Running load test only...${NC}"
        run_load_test "${2:-$NUM_REQUESTS}"
        exit 0
        ;;
    -s|--start-only)
        echo -e "${YELLOW}Starting instances only...${NC}"
        ;;
    *)
        echo -e "${YELLOW}Starting instances and running load test...${NC}"
        ;;
esac

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Error: Maven (mvn) is required but not installed.${NC}"
    exit 1
fi

# Check if curl is available
if ! command -v curl &> /dev/null; then
    echo -e "${RED}Error: curl is required but not installed.${NC}"
    exit 1
fi

echo -e "${PURPLE}===========================================${NC}"
echo -e "${PURPLE}  Distributed Database Startup${NC}"
echo -e "${PURPLE}===========================================${NC}"
echo -e "Base Host: ${BASE_HOST}"
echo -e "Ports: ${PORTS[*]}"
echo -e "Replication Factor: ${REPLICATION_FACTOR}"
echo -e "${PURPLE}===========================================${NC}"
echo ""

# Start all instances
echo -e "${CYAN}Starting all instances...${NC}"
for port in "${PORTS[@]}"; do
    start_instance "$port"
    echo -e "${YELLOW}Waiting 5 seconds before starting next instance...${NC}"
    sleep 5
done

echo -e "${GREEN}All instances started. Waiting 15 seconds for full initialization...${NC}"
sleep 15

# Health check all instances
echo -e "${CYAN}Performing health checks...${NC}"
healthy_count=0
for port in "${PORTS[@]}"; do
    if check_port_health "$port"; then
        ((healthy_count++))
    fi
done

echo -e "${GREEN}Health check completed: $healthy_count/${#PORTS[@]} instances are healthy${NC}"

if [ "$healthy_count" -eq 0 ]; then
    echo -e "${RED}No instances are healthy. Exiting...${NC}"
    exit 1
fi

# Show nodes information
show_nodes_info

# Run load test if not start-only mode
if [ "$1" != "-s" ] && [ "$1" != "--start-only" ]; then
    echo -e "${GREEN}All systems ready! Starting load test...${NC}"
    run_load_test "$NUM_REQUESTS"
fi


echo -e "${GREEN}Script completed. Press Ctrl+C to stop all instances.${NC}"

# Keep script running to maintain instances
    echo -e "${YELLOW}Instances are running. Press Ctrl+C to stop all instances.${NC}"
    while true; do
        sleep 10
    done
fi