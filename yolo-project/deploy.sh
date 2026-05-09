#!/bin/bash
set -e

echo "========================================"
echo "  YOLO分布式训练调度系统 - K8s部署脚本"
echo "========================================"

NAMESPACE="yolo-system"

echo "[1/8] 创建命名空间..."
kubectl apply -f k8s/00-namespace.yaml

echo "[2/8] 创建配置和密钥..."
kubectl apply -f k8s/01-config.yaml

echo "[3/8] 创建持久化存储..."
kubectl apply -f k8s/02-pvc.yaml

echo "[4/8] 部署MySQL数据库..."
kubectl apply -f k8s/03-mysql.yaml
echo "等待MySQL就绪..."
kubectl wait --for=condition=ready pod -l app=yolo-mysql -n $NAMESPACE --timeout=120s || true

echo "[5/8] 创建RBAC权限..."
kubectl apply -f k8s/06-rbac.yaml

echo "[6/8] 部署后端服务..."
kubectl apply -f k8s/04-backend.yaml
echo "等待后端就绪..."
kubectl wait --for=condition=ready pod -l app=yolo-backend -n $NAMESPACE --timeout=120s || true

echo "[7/8] 部署前端服务..."
kubectl apply -f k8s/05-frontend.yaml
echo "等待前端就绪..."
kubectl wait --for=condition=ready pod -l app=yolo-frontend -n $NAMESPACE --timeout=120s || true

echo "[8/8] 配置Ingress..."
kubectl apply -f k8s/07-ingress.yaml

echo ""
echo "========================================"
echo "  部署完成！"
echo "========================================"
echo ""
echo "查看服务状态:"
echo "  kubectl get all -n $NAMESPACE"
echo ""
echo "查看节点信息:"
echo "  kubectl get nodes -o wide"
echo ""
echo "访问系统:"
echo "  kubectl get ingress -n $NAMESPACE"
echo ""
