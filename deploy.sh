#!/bin/bash

# TeleGaGa Full Deployment Script
# Builds fat JAR and deploys to VPS server

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== TeleGaGa Deployment Script ===${NC}"

# Check if deploy.properties exists
if [ ! -f "deploy.properties" ]; then
    echo -e "${RED}ERROR: deploy.properties not found!${NC}"
    echo -e "${YELLOW}Please copy deploy.properties.example to deploy.properties and fill in your values${NC}"
    exit 1
fi

# Load configuration
echo -e "${YELLOW}Loading configuration...${NC}"
source <(grep -v '^#' deploy.properties | sed 's/\./\n/g' | awk '{printf "export %s=\"%s\"\n", $1, $2}')

# Read properties properly
SSH_HOST=$(grep '^ssh.host=' deploy.properties | cut -d'=' -f2)
SSH_PORT=$(grep '^ssh.port=' deploy.properties | cut -d'=' -f2)
SSH_USER=$(grep '^ssh.user=' deploy.properties | cut -d'=' -f2)
SSH_KEY=$(grep '^ssh.key=' deploy.properties | cut -d'=' -f2)
DEPLOY_PATH=$(grep '^deploy.path=' deploy.properties | cut -d'=' -f2)
SERVICE_NAME=$(grep '^service.name=' deploy.properties | cut -d'=' -f2)
JAVA_PATH=$(grep '^java.path=' deploy.properties | cut -d'=' -f2)

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
SCP_CMD_BASE="$SCP_CMD"

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

# Step 2: Prepare deployment package
echo -e "\n${YELLOW}Step 2: Preparing deployment package...${NC}"
DEPLOY_TEMP=$(mktemp -d)
echo "Temporary directory: ${DEPLOY_TEMP}"

# Copy JAR
cp "$JAR_FILE" "${DEPLOY_TEMP}/telegaga.jar"

# Copy config.properties
if [ -f "config.properties" ]; then
    cp config.properties "${DEPLOY_TEMP}/"
else
    echo -e "${RED}WARNING: config.properties not found!${NC}"
fi

# Copy rag_docs
if [ -d "rag_docs" ]; then
    cp -r rag_docs "${DEPLOY_TEMP}/"
    echo "Copied rag_docs/"
else
    echo -e "${YELLOW}WARNING: rag_docs/ not found, skipping${NC}"
fi

# Copy embeddings_store
if [ -d "embeddings_store" ]; then
    cp -r embeddings_store "${DEPLOY_TEMP}/"
    echo "Copied embeddings_store/"
else
    echo -e "${YELLOW}WARNING: embeddings_store/ not found, skipping${NC}"
fi

# Create start script
cat > "${DEPLOY_TEMP}/start.sh" <<EOF
#!/bin/bash
# TeleGaGa Start Script

cd "\$(dirname "\$0")"

# Check if Java is available
if ! command -v ${JAVA_PATH} &> /dev/null; then
    echo "ERROR: Java not found at ${JAVA_PATH}"
    exit 1
fi

echo "Starting TeleGaGa bot..."
${JAVA_PATH} -jar telegaga.jar
EOF
chmod +x "${DEPLOY_TEMP}/start.sh"

# Create systemd service file template
cat > "${DEPLOY_TEMP}/telegaga.service" <<EOF
[Unit]
Description=TeleGaGa Telegram Bot
After=network.target

[Service]
Type=simple
User=${SSH_USER}
WorkingDirectory=${DEPLOY_PATH}
ExecStart=${JAVA_PATH} -jar ${DEPLOY_PATH}/telegaga.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

echo -e "${GREEN}Deployment package prepared${NC}"

# Step 3: Create directory on server
echo -e "\n${YELLOW}Step 3: Creating directory on server...${NC}"
$SSH_CMD "mkdir -p ${DEPLOY_PATH}"

# Step 4: Upload files
echo -e "\n${YELLOW}Step 4: Uploading files to server...${NC}"
rsync -avz --progress -e "$SSH_CMD_BASE" "${DEPLOY_TEMP}/" "${SSH_USER}@${SSH_HOST}:${DEPLOY_PATH}/"

# Step 5: Set permissions
echo -e "\n${YELLOW}Step 5: Setting permissions...${NC}"
$SSH_CMD "chmod +x ${DEPLOY_PATH}/start.sh"
$SSH_CMD "chmod +x ${DEPLOY_PATH}/telegaga.jar"

# Cleanup
rm -rf "${DEPLOY_TEMP}"

echo -e "\n${GREEN}=== Deployment Complete! ===${NC}"
echo -e "${GREEN}Files deployed to: ${SSH_USER}@${SSH_HOST}:${DEPLOY_PATH}${NC}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo "1. SSH to server: ssh ${SSH_USER}@${SSH_HOST}"
echo "2. Test manual run: cd ${DEPLOY_PATH} && ./start.sh"
echo ""
echo -e "${YELLOW}To install as systemd service:${NC}"
echo "  sudo cp ${DEPLOY_PATH}/telegaga.service /etc/systemd/system/"
echo "  sudo systemctl daemon-reload"
echo "  sudo systemctl enable ${SERVICE_NAME}"
echo "  sudo systemctl start ${SERVICE_NAME}"
echo "  sudo systemctl status ${SERVICE_NAME}"
echo ""
echo -e "${YELLOW}To view logs:${NC}"
echo "  sudo journalctl -u ${SERVICE_NAME} -f"
