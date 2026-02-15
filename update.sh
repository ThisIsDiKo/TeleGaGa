#!/bin/bash

# TeleGaGa Quick Update Script
# Builds and deploys only the JAR file, restarts the service

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== TeleGaGa Quick Update Script ===${NC}"

# Check if deploy.properties exists
if [ ! -f "deploy.properties" ]; then
    echo -e "${RED}ERROR: deploy.properties not found!${NC}"
    echo -e "${YELLOW}Please copy deploy.properties.example to deploy.properties and fill in your values${NC}"
    exit 1
fi

# Read properties
SSH_HOST=$(grep '^ssh.host=' deploy.properties | cut -d'=' -f2)
SSH_PORT=$(grep '^ssh.port=' deploy.properties | cut -d'=' -f2)
SSH_USER=$(grep '^ssh.user=' deploy.properties | cut -d'=' -f2)
SSH_KEY=$(grep '^ssh.key=' deploy.properties | cut -d'=' -f2)
DEPLOY_PATH=$(grep '^deploy.path=' deploy.properties | cut -d'=' -f2)
SERVICE_NAME=$(grep '^service.name=' deploy.properties | cut -d'=' -f2)

# Validate required properties
if [ -z "$SSH_HOST" ] || [ -z "$SSH_USER" ] || [ -z "$DEPLOY_PATH" ] || [ -z "$SERVICE_NAME" ]; then
    echo -e "${RED}ERROR: Missing required properties in deploy.properties${NC}"
    exit 1
fi

# Build SSH command
SSH_CMD="ssh -p ${SSH_PORT:-22}"
SCP_CMD="scp -P ${SSH_PORT:-22}"
if [ -n "$SSH_KEY" ]; then
    SSH_CMD="$SSH_CMD -i $SSH_KEY"
    SCP_CMD="$SCP_CMD -i $SSH_KEY"
fi
SSH_CMD="$SSH_CMD ${SSH_USER}@${SSH_HOST}"

echo -e "${GREEN}Configuration loaded:${NC}"
echo "  Server: ${SSH_USER}@${SSH_HOST}:${SSH_PORT:-22}"
echo "  Deploy path: ${DEPLOY_PATH}"
echo "  Service name: ${SERVICE_NAME}"

# Step 1: Build fat JAR
echo -e "\n${YELLOW}Step 1: Building fat JAR...${NC}"
export JAVA_HOME=/Users/dmitriikonovalov/Library/Java/JavaVirtualMachines/openjdk-17.0.1/Contents/Home
./gradlew clean shadowJar

# Find the built JAR
JAR_FILE=$(find build/libs -name "telegaga-bot-*.jar" | head -n 1)
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}ERROR: Fat JAR not found in build/libs/${NC}"
    exit 1
fi
echo -e "${GREEN}Built JAR: ${JAR_FILE}${NC}"

# Step 2: Check if service is running
echo -e "\n${YELLOW}Step 2: Checking service status...${NC}"
SERVICE_RUNNING=$($SSH_CMD "systemctl is-active ${SERVICE_NAME} 2>/dev/null || echo 'inactive'")

if [ "$SERVICE_RUNNING" = "active" ]; then
    echo -e "${GREEN}Service is running, stopping...${NC}"
    $SSH_CMD "sudo systemctl stop ${SERVICE_NAME}"
    echo -e "${GREEN}Service stopped${NC}"
else
    echo -e "${YELLOW}Service is not running (will skip restart)${NC}"
fi

# Step 3: Backup old JAR
echo -e "\n${YELLOW}Step 3: Backing up old JAR...${NC}"
$SSH_CMD "if [ -f ${DEPLOY_PATH}/telegaga.jar ]; then cp ${DEPLOY_PATH}/telegaga.jar ${DEPLOY_PATH}/telegaga.jar.backup; echo 'Backup created'; else echo 'No old JAR to backup'; fi"

# Step 4: Upload new JAR
echo -e "\n${YELLOW}Step 4: Uploading new JAR...${NC}"
$SCP_CMD "$JAR_FILE" "${SSH_USER}@${SSH_HOST}:${DEPLOY_PATH}/telegaga.jar"
echo -e "${GREEN}JAR uploaded${NC}"

# Step 5: Set permissions
echo -e "\n${YELLOW}Step 5: Setting permissions...${NC}"
$SSH_CMD "chmod +x ${DEPLOY_PATH}/telegaga.jar"

# Step 6: Restart service if it was running
if [ "$SERVICE_RUNNING" = "active" ]; then
    echo -e "\n${YELLOW}Step 6: Starting service...${NC}"
    $SSH_CMD "sudo systemctl start ${SERVICE_NAME}"

    # Wait a bit and check status
    sleep 2
    SERVICE_STATUS=$($SSH_CMD "systemctl is-active ${SERVICE_NAME}")

    if [ "$SERVICE_STATUS" = "active" ]; then
        echo -e "${GREEN}Service started successfully!${NC}"
    else
        echo -e "${RED}ERROR: Service failed to start!${NC}"
        echo -e "${YELLOW}Check logs with: sudo journalctl -u ${SERVICE_NAME} -n 50${NC}"
        echo -e "${YELLOW}Restoring backup...${NC}"
        $SSH_CMD "cp ${DEPLOY_PATH}/telegaga.jar.backup ${DEPLOY_PATH}/telegaga.jar && sudo systemctl start ${SERVICE_NAME}"
        exit 1
    fi
else
    echo -e "\n${YELLOW}Step 6: Skipping service restart (was not running)${NC}"
    echo -e "${YELLOW}To start manually: cd ${DEPLOY_PATH} && ./start.sh${NC}"
fi

echo -e "\n${GREEN}=== Update Complete! ===${NC}"
echo ""
echo -e "${YELLOW}To view logs:${NC}"
echo "  sudo journalctl -u ${SERVICE_NAME} -f"
echo ""
echo -e "${YELLOW}To check status:${NC}"
echo "  sudo systemctl status ${SERVICE_NAME}"
