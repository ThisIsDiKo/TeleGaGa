#!/bin/bash

# TeleGaGa Deployment Script - localModel branch (Ollama / Gemma3)
# Builds fat JAR locally and deploys to VPS server
# Requirements on server: JDK 17+, Ollama with gemma3:1b pulled

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== TeleGaGa Deployment Script (localModel / Gemma3) ===${NC}"

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
JAVA_PATH=$(grep '^java.path=' deploy.properties | cut -d'=' -f2)
JAVA_PATH="${JAVA_PATH:-/usr/bin/java}"

# Validate required properties
if [ -z "$SSH_HOST" ] || [ -z "$SSH_USER" ] || [ -z "$DEPLOY_PATH" ] || [ -z "$SERVICE_NAME" ]; then
    echo -e "${RED}ERROR: Missing required properties in deploy.properties${NC}"
    exit 1
fi

# Build SSH / SCP commands
SSH_OPTS="-p ${SSH_PORT:-22} -o StrictHostKeyChecking=no"
if [ -n "$SSH_KEY" ]; then
    SSH_OPTS="$SSH_OPTS -i $SSH_KEY"
fi
SSH_CMD="ssh $SSH_OPTS ${SSH_USER}@${SSH_HOST}"
SCP_CMD="scp -P ${SSH_PORT:-22} -o StrictHostKeyChecking=no"
if [ -n "$SSH_KEY" ]; then
    SCP_CMD="$SCP_CMD -i $SSH_KEY"
fi

echo -e "${GREEN}Configuration loaded:${NC}"
echo "  Server:       ${SSH_USER}@${SSH_HOST}:${SSH_PORT:-22}"
echo "  Deploy path:  ${DEPLOY_PATH}"
echo "  Service name: ${SERVICE_NAME}"
echo "  Java path:    ${JAVA_PATH}"

# ---------------------------------------------------------------------------
# Step 1: Build fat JAR locally
# ---------------------------------------------------------------------------
echo -e "\n${YELLOW}Step 1: Building fat JAR...${NC}"
export JAVA_HOME=/Users/dmitriikonovalov/Library/Java/JavaVirtualMachines/openjdk-17.0.1/Contents/Home
./gradlew clean shadowJar

JAR_FILE=$(find build/libs -name "telegaga-bot-*.jar" | head -n 1)
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}ERROR: Fat JAR not found in build/libs/${NC}"
    exit 1
fi
echo -e "${GREEN}Built JAR: ${JAR_FILE}${NC}"

# ---------------------------------------------------------------------------
# Step 2: Verify Ollama is reachable on the server
# ---------------------------------------------------------------------------
echo -e "\n${YELLOW}Step 2: Checking Ollama on server...${NC}"
OLLAMA_OK=$($SSH_CMD "curl -s --max-time 5 http://localhost:11434/api/tags > /dev/null 2>&1 && echo ok || echo fail")
if [ "$OLLAMA_OK" != "ok" ]; then
    echo -e "${RED}ERROR: Ollama is not running on the server (http://localhost:11434)${NC}"
    echo -e "${YELLOW}Start it with: sudo systemctl start ollama${NC}"
    exit 1
fi
echo -e "${GREEN}Ollama is running${NC}"

# Check that gemma3:1b is available
echo "  Checking gemma3:1b model..."
GEMMA_OK=$($SSH_CMD "curl -s http://localhost:11434/api/tags | grep -c 'gemma3' || true")
if [ "$GEMMA_OK" -eq 0 ]; then
    echo -e "${YELLOW}gemma3:1b not found, pulling now (this may take a while)...${NC}"
    $SSH_CMD "ollama pull gemma3:1b"
    echo -e "${GREEN}gemma3:1b pulled${NC}"
else
    echo -e "${GREEN}gemma3:1b is available${NC}"
fi

# ---------------------------------------------------------------------------
# Step 3: Prepare deployment package
# ---------------------------------------------------------------------------
echo -e "\n${YELLOW}Step 3: Preparing deployment package...${NC}"
DEPLOY_TEMP=$(mktemp -d)
echo "  Temp directory: ${DEPLOY_TEMP}"

# Copy JAR
cp "$JAR_FILE" "${DEPLOY_TEMP}/telegaga.jar"

# Copy config.properties (contains telegram token and ollama settings)
if [ -f "config.properties" ]; then
    cp config.properties "${DEPLOY_TEMP}/"
    echo "  Copied config.properties"
else
    echo -e "${RED}WARNING: config.properties not found!${NC}"
fi

# Generate start.sh
cat > "${DEPLOY_TEMP}/start.sh" <<EOF
#!/bin/bash
# TeleGaGa start script (localModel / Gemma3)

cd "\$(dirname "\$0")"

if ! command -v ${JAVA_PATH} &> /dev/null; then
    echo "ERROR: Java not found at ${JAVA_PATH}"
    exit 1
fi

# Verify Ollama is available
if ! curl -s --max-time 5 http://localhost:11434/api/tags > /dev/null 2>&1; then
    echo "ERROR: Ollama is not running on localhost:11434"
    exit 1
fi

echo "Starting TeleGaGa (Gemma3)..."
${JAVA_PATH} -jar telegaga.jar
EOF
chmod +x "${DEPLOY_TEMP}/start.sh"

# Generate systemd service file
cat > "${DEPLOY_TEMP}/telegaga.service" <<EOF
[Unit]
Description=TeleGaGa Telegram Bot (Gemma3 / Ollama)
After=network.target ollama.service
Wants=ollama.service

[Service]
Type=simple
User=${SSH_USER}
WorkingDirectory=${DEPLOY_PATH}
ExecStart=${JAVA_PATH} -jar ${DEPLOY_PATH}/telegaga.jar
Restart=on-failure
RestartSec=15
StandardOutput=journal
StandardError=journal
# Give Ollama time to warm up before bot starts
ExecStartPre=/bin/sleep 3

[Install]
WantedBy=multi-user.target
EOF

echo -e "${GREEN}Deployment package prepared${NC}"

# ---------------------------------------------------------------------------
# Step 4: Stop service if running
# ---------------------------------------------------------------------------
echo -e "\n${YELLOW}Step 4: Stopping service if running...${NC}"
SERVICE_RUNNING=$($SSH_CMD "systemctl is-active ${SERVICE_NAME} 2>/dev/null || echo inactive")
if [ "$SERVICE_RUNNING" = "active" ]; then
    $SSH_CMD "sudo systemctl stop ${SERVICE_NAME}"
    echo -e "${GREEN}Service stopped${NC}"
else
    echo "  Service was not running"
fi

# ---------------------------------------------------------------------------
# Step 5: Upload files
# ---------------------------------------------------------------------------
echo -e "\n${YELLOW}Step 5: Uploading files to server...${NC}"
$SSH_CMD "mkdir -p ${DEPLOY_PATH}"
rsync -avz --progress \
    -e "ssh -p ${SSH_PORT:-22} -o StrictHostKeyChecking=no${SSH_KEY:+ -i $SSH_KEY}" \
    "${DEPLOY_TEMP}/" \
    "${SSH_USER}@${SSH_HOST}:${DEPLOY_PATH}/"

# ---------------------------------------------------------------------------
# Step 6: Set permissions and install service file
# ---------------------------------------------------------------------------
echo -e "\n${YELLOW}Step 6: Setting permissions...${NC}"
$SSH_CMD "chmod +x ${DEPLOY_PATH}/start.sh"

echo -e "\n${YELLOW}Step 7: Installing systemd service...${NC}"
$SSH_CMD "sudo cp ${DEPLOY_PATH}/telegaga.service /etc/systemd/system/${SERVICE_NAME}.service && sudo systemctl daemon-reload && sudo systemctl enable ${SERVICE_NAME}"
echo -e "${GREEN}Service installed and enabled${NC}"

# ---------------------------------------------------------------------------
# Step 8: Start service
# ---------------------------------------------------------------------------
echo -e "\n${YELLOW}Step 8: Starting service...${NC}"
$SSH_CMD "sudo systemctl start ${SERVICE_NAME}"
sleep 4

SERVICE_STATUS=$($SSH_CMD "systemctl is-active ${SERVICE_NAME}")
if [ "$SERVICE_STATUS" = "active" ]; then
    echo -e "${GREEN}Service started successfully!${NC}"
else
    echo -e "${RED}ERROR: Service failed to start${NC}"
    echo -e "${YELLOW}Check logs: sudo journalctl -u ${SERVICE_NAME} -n 50${NC}"
    rm -rf "${DEPLOY_TEMP}"
    exit 1
fi

# Cleanup
rm -rf "${DEPLOY_TEMP}"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo -e "\n${GREEN}=== Deployment Complete! ===${NC}"
echo -e "  Files:   ${SSH_USER}@${SSH_HOST}:${DEPLOY_PATH}"
echo -e "  Service: ${SERVICE_NAME} (active)"
echo ""
echo -e "${YELLOW}Useful commands on server:${NC}"
echo "  View logs:     sudo journalctl -u ${SERVICE_NAME} -f"
echo "  Check status:  sudo systemctl status ${SERVICE_NAME}"
echo "  Restart:       sudo systemctl restart ${SERVICE_NAME}"
echo "  Stop:          sudo systemctl stop ${SERVICE_NAME}"
echo "  Ollama status: sudo systemctl status ollama"
