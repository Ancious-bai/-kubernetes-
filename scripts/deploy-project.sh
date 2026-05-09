#!/bin/bash
#=============================================================================
# 脚本名称: deploy-project.sh
# 脚本用途: 部署 YOLO 训练管理系统到 Kubernetes
# 使用方法: ./deploy-project.sh <REGISTRY> <VERSION>
# 示例:
#   ./deploy-project.sh docker.io/anxious v1.0.0
#   ./deploy-project.sh registry.cn-hangzhou.aliyuncs.com/anxious-yolo v1.0.0
#=============================================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 默认值
REGISTRY="${1:-docker.io/ancious}"
VERSION="${2:-v1.0.0}"
K8S_DIR="../yolo-project/k8s"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  开始部署 YOLO 训练管理系统${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${BLUE}配置信息:${NC}"
echo "  镜像仓库: ${REGISTRY}"
echo "  版本: ${VERSION}"
echo "  K8s 配置目录: ${K8S_DIR}"
echo ""

#=========================================================================
# 第1步: 检查 kubectl
#=========================================================================
echo -e "${GREEN}[1/7] 检查 kubectl...${NC}"
if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}错误: kubectl 未安装或未配置${NC}"
    exit 1
fi
echo -e "${GREEN}✓ kubectl 可用${NC}"

#=========================================================================
# 第2步: 确认集群状态
#=========================================================================
echo -e "${GREEN}[2/7] 确认集群状态...${NC}"
echo -e "${YELLOW}节点列表:${NC}"
kubectl get nodes -o wide
echo ""

# 检查节点是否 Ready
NOT_READY=$(kubectl get nodes --no-headers 2>/dev/null | grep -v Ready | wc -l)
if [ "$NOT_READY" -gt 0 ]; then
    echo -e "${YELLOW}警告: 有节点未就绪，请等待...${NC}"
    echo "等待所有节点 Ready..."
    kubectl wait --for=condition=Ready nodes --all --timeout=300s
fi
echo -e "${GREEN}✓ 集群节点正常${NC}"

#=========================================================================
# 第3步: 更新配置文件
#=========================================================================
echo -e "${GREEN}[3/7] 更新配置文件...${NC}"

# 进入脚本目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

# 替换镜像仓库
echo "  替换镜像仓库: <REGISTRY> -> ${REGISTRY}"
sed -i "s|<REGISTRY>|${REGISTRY}|g" "${K8S_DIR}/01-config.yaml"
sed -i "s|<REGISTRY>|${REGISTRY}|g" "${K8S_DIR}/04-backend.yaml"
sed -i "s|<REGISTRY>|${REGISTRY}|g" "${K8S_DIR}/05-frontend.yaml"

echo -e "${GREEN}✓ 配置文件已更新${NC}"

#=========================================================================
# 第4步: 创建命名空间
#=========================================================================
echo -e "${GREEN}[4/7] 创建命名空间...${NC}"
kubectl apply -f "${K8S_DIR}/00-namespace.yaml"
echo -e "${GREEN}✓ 命名空间已创建${NC}"

#=========================================================================
# 第5步: 部署 ConfigMap 和 Secret
#=========================================================================
echo -e "${GREEN}[5/7] 部署配置...${NC}"
kubectl apply -f "${K8S_DIR}/01-config.yaml"
echo -e "${GREEN}✓ 配置已部署${NC}"

#=========================================================================
# 第6步: 部署存储
#=========================================================================
echo -e "${GREEN}[6/7] 部署 PVC...${NC}"
kubectl apply -f "${K8S_DIR}/02-pvc.yaml"
echo -e "${GREEN}✓ PVC 已部署${NC}"

#=========================================================================
# 第7步: 部署应用
#=========================================================================
echo -e "${GREEN}[7/7] 部署应用...${NC}"
kubectl apply -f "${K8S_DIR}/03-mysql.yaml"
kubectl apply -f "${K8S_DIR}/06-rbac.yaml"
kubectl apply -f "${K8S_DIR}/04-backend.yaml"
kubectl apply -f "${K8S_DIR}/05-frontend.yaml"
kubectl apply -f "${K8S_DIR}/07-ingress.yaml"
echo -e "${GREEN}✓ 应用已部署${NC}"

#=========================================================================
# 等待 Pod 就绪
#=========================================================================
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  等待 Pod 就绪...${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

echo -e "${YELLOW}等待 MySQL...${NC}"
kubectl wait --for=condition=ready pod -l app=yolo-mysql -n yolo-system --timeout=300s || true

echo -e "${YELLOW}等待 Backend...${NC}"
kubectl wait --for=condition=ready pod -l app=yolo-backend -n yolo-system --timeout=300s || true

echo -e "${YELLOW}等待 Frontend...${NC}"
kubectl wait --for=condition=ready pod -l app=yolo-frontend -n yolo-system --timeout=300s || true

#=========================================================================
# 验证部署
#=========================================================================
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  部署完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

echo -e "${BLUE}Pod 状态:${NC}"
kubectl get pods -n yolo-system -o wide
echo ""

echo -e "${BLUE}PVC 状态:${NC}"
kubectl get pvc -n yolo-system
echo ""

echo -e "${BLUE}Service 状态:${NC}"
kubectl get svc -n yolo-system
echo ""

echo -e "${BLUE}StorageClass 状态:${NC}"
kubectl get storageclass
echo ""

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  访问信息${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}前端访问地址:${NC}"
echo "  http://192.168.100.11:30080"
echo ""
echo -e "${YELLOW}后端 API 地址:${NC}"
echo "  http://192.168.100.11:30080/api"
echo ""
echo -e "${YELLOW}Ingress（如果配置了域名）:${NC}"
echo "  http://yolo.example.com"
echo ""
