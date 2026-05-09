#!/bin/bash
#=============================================================================
# 脚本名称: build-and-push.sh
# 脚本用途: 在 master 节点上构建并推送 Docker 镜像
# 执行位置: k8s-master (192.168.100.10)
# 使用方法: chmod +x build-and-push.sh && ./build-and-push.sh
#=============================================================================

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

REPOSITORY="${1:-docker.io/ancious}"
VERSION="${2:-v1.0.0}"
PUSH="${3:-push}"

TRAINER_IMAGE="${REPOSITORY}/yolov8-trainer:${VERSION}"
BACKEND_IMAGE="${REPOSITORY}/yolo-backend:${VERSION}"
FRONTEND_IMAGE="${REPOSITORY}/yolo-frontend:${VERSION}"

CODE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  在 Master 节点构建镜像${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}代码目录: ${CODE_DIR}${NC}"
echo -e "${YELLOW}镜像仓库: ${REPOSITORY}${NC}"
echo -e "${YELLOW}版本: ${VERSION}${NC}"
echo ""

#=========================================================================
# 检查 Docker
#=========================================================================
if ! command -v docker &> /dev/null; then
    echo -e "${RED}错误: Docker 未安装${NC}"
    exit 1
fi

echo -e "${YELLOW}Docker 版本:${NC}"
docker --version
echo ""

#=========================================================================
# 构建 Trainer 镜像
#=========================================================================
echo -e "${GREEN}[1/3] 构建训练镜像: ${TRAINER_IMAGE}${NC}"
docker build -t "${TRAINER_IMAGE}" \
    -f "${CODE_DIR}/Dockerfile.trainer" \
    "${CODE_DIR}"

if [ $? -ne 0 ]; then
    echo -e "${RED}训练镜像构建失败！${NC}"
    exit 1
fi
echo -e "${GREEN}✓ 训练镜像构建成功${NC}"
echo ""

#=========================================================================
# 构建 Backend 镜像
#=========================================================================
echo -e "${GREEN}[2/3] 构建后端镜像: ${BACKEND_IMAGE}${NC}"
docker build -t "${BACKEND_IMAGE}" \
    -f "${CODE_DIR}/yolo-project/Dockerfile" \
    "${CODE_DIR}/yolo-project"

if [ $? -ne 0 ]; then
    echo -e "${RED}后端镜像构建失败！${NC}"
    exit 1
fi
echo -e "${GREEN}✓ 后端镜像构建成功${NC}"
echo ""

#=========================================================================
# 构建 Frontend 镜像
#=========================================================================
echo -e "${GREEN}[3/3] 构建前端镜像: ${FRONTEND_IMAGE}${NC}"
docker build -t "${FRONTEND_IMAGE}" \
    -f "${CODE_DIR}/yolo-project/Dockerfile.frontend" \
    "${CODE_DIR}/yolo-project"

if [ $? -ne 0 ]; then
    echo -e "${RED}前端镜像构建失败！${NC}"
    exit 1
fi
echo -e "${GREEN}✓ 前端镜像构建成功${NC}"
echo ""

#=========================================================================
# 推送镜像
#=========================================================================
if [ "$PUSH" = "push" ]; then
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  推送镜像到仓库${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""

    echo -e "${YELLOW}登录 Docker Hub...${NC}"
    docker login

    echo -e "${YELLOW}推送训练镜像...${NC}"
    docker push "${TRAINER_IMAGE}"

    echo -e "${YELLOW}推送后端镜像...${NC}"
    docker push "${BACKEND_IMAGE}"

    echo -e "${YELLOW}推送前端镜像...${NC}"
    docker push "${FRONTEND_IMAGE}"

    echo -e "${GREEN}✓ 所有镜像推送完成${NC}"
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  构建完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}镜像列表:${NC}"
docker images | grep -E "yolov8-trainer|yolo-backend|yolo-frontend" | head -6
echo ""
echo -e "${YELLOW}镜像地址:${NC}"
echo "  ${TRAINER_IMAGE}"
echo "  ${BACKEND_IMAGE}"
echo "  ${FRONTEND_IMAGE}"
