#!/bin/bash
# 在 master 节点执行，部署项目

set -e

REGISTRY="${1:-docker.io/ancious}"
VERSION="${2:-v1.0.0}"
NFS_SERVER="${3:-192.168.100.10}"
K8S_DIR="../yolo-project/k8s"

echo "=== 开始部署项目 ==="
echo "镜像仓库: ${REGISTRY}"
echo "版本: ${VERSION}"
echo "NFS 服务器: ${NFS_SERVER}"

# 进入脚本目录
cd "$(dirname "$0")"

# 替换镜像仓库
echo "更新配置文件中的镜像仓库..."
sed -i "s|<REGISTRY>|${REGISTRY}|g" "${K8S_DIR}/01-config.yaml"
sed -i "s|<REGISTRY>|${REGISTRY}|g" "${K8S_DIR}/04-backend.yaml"
sed -i "s|<REGISTRY>|${REGISTRY}|g" "${K8S_DIR}/05-frontend.yaml"

# 替换 NFS 服务器 IP
echo "更新 NFS 配置..."
sed -i "s|NFS_SERVER_IP|${NFS_SERVER}|g" "${K8S_DIR}/00-nfs-provisioner.yaml"

# 部署
echo "部署 Kubernetes 资源..."
kubectl apply -f "${K8S_DIR}/00-namespace.yaml"
kubectl apply -f "${K8S_DIR}/00-nfs-provisioner.yaml"
sleep 10
kubectl apply -f "${K8S_DIR}/01-config.yaml"
kubectl apply -f "${K8S_DIR}/02-pvc.yaml"
kubectl apply -f "${K8S_DIR}/03-mysql.yaml"
kubectl apply -f "${K8S_DIR}/06-rbac.yaml"
kubectl apply -f "${K8S_DIR}/04-backend.yaml"
kubectl apply -f "${K8S_DIR}/05-frontend.yaml"
kubectl apply -f "${K8S_DIR}/07-ingress.yaml"

# 等待 Pod 就绪
echo "等待 Pod 就绪..."
kubectl wait --for=condition=ready pod -l app=nfs-provisioner -n yolo-system --timeout=300s || true
kubectl wait --for=condition=ready pod -l app=yolo-mysql -n yolo-system --timeout=300s
kubectl wait --for=condition=ready pod -l app=yolo-backend -n yolo-system --timeout=300s
kubectl wait --for=condition=ready pod -l app=yolo-frontend -n yolo-system --timeout=300s

echo "=== 部署完成 ==="
echo ""
echo "Pod 状态:"
kubectl get pods -n yolo-system -o wide
echo ""
echo "PVC 状态:"
kubectl get pvc -n yolo-system
echo ""
echo "服务:"
kubectl get svc -n yolo-system
echo ""
echo "访问地址:"
echo "  前端: http://192.168.100.11:30080"
