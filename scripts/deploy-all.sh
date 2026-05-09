#!/bin/bash
#=============================================================================
# 脚本名称: deploy-all.sh
# 脚本用途: 一键部署 YOLO 训练管理系统到三节点 Kubernetes 集群
# 执行位置: k8s-master (192.168.100.10)
# 使用方法: chmod +x deploy-all.sh && ./deploy-all.sh
#=============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

K8S_DIR="../yolo-project/k8s"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  YOLO 训练管理系统 - 一键部署${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

#=========================================================================
# 第1步: 检查集群状态
#=========================================================================
echo -e "${GREEN}[1/8] 检查集群状态...${NC}"

NODES_READY=$(kubectl get nodes --no-headers 2>/dev/null | grep -c " Ready" || echo "0")
if [ "$NODES_READY" -lt 3 ]; then
    echo -e "${RED}错误: 集群节点未全部就绪 (当前: ${NODES_READY}/3)${NC}"
    echo "请确保 3 个节点都是 Ready 状态"
    kubectl get nodes
    exit 1
fi
echo -e "${GREEN}✓ 集群节点正常 (${NODES_READY}/3)${NC}"
kubectl get nodes -o wide
echo ""

#=========================================================================
# 第2步: 创建命名空间
#=========================================================================
echo -e "${GREEN}[2/8] 创建命名空间...${NC}"
kubectl apply -f "${K8S_DIR}/00-namespace.yaml"
echo -e "${GREEN}✓ 命名空间已创建${NC}"

#=========================================================================
# 第3步: 部署 NFS Provisioner
#=========================================================================
echo -e "${GREEN}[3/8] 部署 NFS Provisioner...${NC}"
kubectl apply -f "${K8S_DIR}/00-nfs-provisioner.yaml"
echo "等待 NFS Provisioner 启动..."
sleep 10
kubectl wait --for=condition=available deployment/nfs-subdir-external-provisioner -n yolo-system --timeout=120s || true
echo -e "${GREEN}✓ NFS Provisioner 已部署${NC}"

# 验证 StorageClass
kubectl get storageclass nfs-storage || {
    echo -e "${RED}错误: StorageClass nfs-storage 不存在${NC}"
    exit 1
}

#=========================================================================
# 第4步: 部署 ConfigMap 和 Secret
#=========================================================================
echo -e "${GREEN}[4/8] 部署配置...${NC}"
kubectl apply -f "${K8S_DIR}/01-config.yaml"
echo -e "${GREEN}✓ 配置已部署${NC}"

#=========================================================================
# 第5步: 部署 PVC
#=========================================================================
echo -e "${GREEN}[5/8] 部署 PVC...${NC}"
kubectl apply -f "${K8S_DIR}/02-pvc.yaml"
echo "等待 PVC 绑定..."
sleep 5

for i in $(seq 1 30); do
    WORKSPACE_BOUND=$(kubectl get pvc yolo-workspace -n yolo-system -o jsonpath='{.status.phase}' 2>/dev/null || echo "Pending")
    MYSQL_BOUND=$(kubectl get pvc yolo-mysql-data -n yolo-system -o jsonpath='{.status.phase}' 2>/dev/null || echo "Pending")
    if [ "$WORKSPACE_BOUND" = "Bound" ] && [ "$MYSQL_BOUND" = "Bound" ]; then
        break
    fi
    echo "  等待 PVC 绑定... workspace=${WORKSPACE_BOUND}, mysql=${MYSQL_BOUND}"
    sleep 5
done

WORKSPACE_BOUND=$(kubectl get pvc yolo-workspace -n yolo-system -o jsonpath='{.status.phase}' 2>/dev/null || echo "Unknown")
if [ "$WORKSPACE_BOUND" != "Bound" ]; then
    echo -e "${YELLOW}警告: yolo-workspace PVC 未绑定 (${WORKSPACE_BOUND})${NC}"
    echo "请检查 NFS Provisioner 是否正常运行"
    kubectl get pods -n yolo-system -l app=nfs-provisioner
    kubectl describe pvc yolo-workspace -n yolo-system
fi
echo -e "${GREEN}✓ PVC 已部署${NC}"
kubectl get pvc -n yolo-system
echo ""

#=========================================================================
# 第6步: 部署 RBAC
#=========================================================================
echo -e "${GREEN}[6/8] 部署 RBAC...${NC}"
kubectl apply -f "${K8S_DIR}/06-rbac.yaml"
echo -e "${GREEN}✓ RBAC 已部署${NC}"

#=========================================================================
# 第7步: 部署 MySQL
#=========================================================================
echo -e "${GREEN}[7/8] 部署 MySQL...${NC}"
kubectl apply -f "${K8S_DIR}/03-mysql.yaml"
echo "等待 MySQL 启动..."
kubectl wait --for=condition=ready pod -l app=yolo-mysql -n yolo-system --timeout=300s || {
    echo -e "${YELLOW}MySQL 启动超时，查看状态...${NC}"
    kubectl describe pod -l app=yolo-mysql -n yolo-system
    kubectl logs -l app=yolo-mysql -n yolo-system --tail=20
}
echo -e "${GREEN}✓ MySQL 已部署${NC}"

# 等待 MySQL 完全就绪
echo "等待 MySQL 完全就绪..."
sleep 15

#=========================================================================
# 第8步: 部署后端和前端
#=========================================================================
echo -e "${GREEN}[8/8] 部署后端和前端...${NC}"
kubectl apply -f "${K8S_DIR}/04-backend.yaml"
kubectl apply -f "${K8S_DIR}/05-frontend.yaml"
kubectl apply -f "${K8S_DIR}/07-ingress.yaml"

echo "等待后端启动..."
kubectl wait --for=condition=ready pod -l app=yolo-backend -n yolo-system --timeout=300s || {
    echo -e "${YELLOW}后端启动超时，查看状态...${NC}"
    kubectl describe pod -l app=yolo-backend -n yolo-system
    kubectl logs -l app=yolo-backend -n yolo-system --tail=30
}

echo "等待前端启动..."
kubectl wait --for=condition=ready pod -l app=yolo-frontend -n yolo-system --timeout=300s || {
    echo -e "${YELLOW}前端启动超时，查看状态...${NC}"
    kubectl describe pod -l app=yolo-frontend -n yolo-system
}

#=========================================================================
# 部署完成
#=========================================================================
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  部署完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

echo -e "${BLUE}=== Pod 状态 ===${NC}"
kubectl get pods -n yolo-system -o wide
echo ""

echo -e "${BLUE}=== PVC 状态 ===${NC}"
kubectl get pvc -n yolo-system
echo ""

echo -e "${BLUE}=== Service 状态 ===${NC}"
kubectl get svc -n yolo-system
echo ""

echo -e "${BLUE}=== 节点状态 ===${NC}"
kubectl get nodes -o wide
echo ""

echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}  访问信息${NC}"
echo -e "${YELLOW}========================================${NC}"
echo ""
echo -e "${GREEN}前端地址:${NC}"
echo "  http://192.168.100.11:30080"
echo "  http://192.168.100.12:30080"
echo ""
echo -e "${GREEN}后端 API:${NC}"
echo "  http://192.168.100.11:30080/api/auth/health"
echo ""
echo -e "${YELLOW}常用命令:${NC}"
echo "  kubectl get pods -n yolo-system -o wide    # 查看 Pod"
echo "  kubectl logs -f -l app=yolo-backend -n yolo-system  # 后端日志"
echo "  kubectl get jobs -n yolo-system             # 训练任务"
echo "  kubectl get nodes -o wide                   # 节点状态"
