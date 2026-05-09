#!/bin/bash
#=============================================================================
# 脚本名称: setup-nfs.sh
# 脚本用途: 在 Master 节点配置 NFS 共享存储
# 使用方法: chmod +x setup-nfs.sh && ./setup-nfs.sh
#=============================================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  开始配置 NFS 共享存储${NC}"
echo -e "${GREEN}========================================${NC}"

#=========================================================================
# 第1步: 安装 NFS 服务器
#=========================================================================
echo -e "${GREEN}[1/4] 安装 NFS 服务器...${NC}"
apt-get update -qq
apt-get install -y nfs-kernel-server > /dev/null 2>&1
echo -e "${GREEN}✓ NFS 服务器已安装${NC}"

#=========================================================================
# 第2步: 创建共享目录
#=========================================================================
echo -e "${GREEN}[2/4] 创建共享目录...${NC}"
mkdir -p /data/nfs
chmod 777 /data/nfs
echo -e "${GREEN}✓ 共享目录已创建: /data/nfs${NC}"

#=========================================================================
# 第3步: 配置 NFS 导出
#=========================================================================
echo -e "${GREEN}[3/4] 配置 NFS 导出...${NC}"

# 备份原配置
cp /etc/exports /etc/exports.bak 2>/dev/null || true

# 添加导出配置
cat >> /etc/exports <<EOF
/data/nfs 192.168.100.0/24(rw,sync,no_root_squash,no_subtree_check)
EOF

# 使配置生效
exportfs -a
echo -e "${GREEN}✓ NFS 导出配置已添加${NC}"

#=========================================================================
# 第4步: 启动 NFS 服务
#=========================================================================
echo -e "${GREEN}[4/4] 启动 NFS 服务...${NC}"
systemctl restart nfs-kernel-server
systemctl enable nfs-kernel-server
systemctl status nfs-kernel-server --no-pager || true
echo -e "${GREEN}✓ NFS 服务已启动${NC}"

#=========================================================================
# 第5步: 部署 NFS Provisioner 到 Kubernetes
#=========================================================================
echo -e "${GREEN}[5/5] 部署 NFS Provisioner 到 Kubernetes...${NC}"

# 检查 kubectl 是否可用
if ! command -v kubectl &> /dev/null; then
    echo -e "${YELLOW}kubectl 不可用，跳过 K8s 部署${NC}"
    exit 1
fi

# 创建 nfs-subdir-external-provisioner RBAC
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: nfs-provisioner
  namespace: yolo-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: nfs-provisioner-runner
rules:
  - apiGroups: [""]
    resources: ["persistentvolumes"]
    verbs: ["get", "list", "watch", "create", "delete"]
  - apiGroups: [""]
    resources: ["persistentvolumeclaims"]
    verbs: ["get", "list", "watch", "update"]
  - apiGroups: ["storage.k8s.io"]
    resources: ["storageclasses"]
    verbs: ["get", "list", "watch"]
  - apiGroups: [""]
    resources: ["events"]
    verbs: ["create", "update", "patch"]
  - apiGroups: [""]
    resources: ["services", "endpoints"]
    verbs: ["get", "list", "watch", "create", "update", "patch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: run-nfs-provisioner
roleRef:
  kind: ClusterRole
  name: nfs-provisioner-runner
  apiGroup: rbac.authorization.k8s.io
subjects:
  - kind: ServiceAccount
    name: nfs-provisioner
    namespace: yolo-system
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nfs-provisioner
  namespace: yolo-system
spec:
  replicas: 1
  selector:
    matchLabels:
      app: nfs-provisioner
  template:
    metadata:
      labels:
        app: nfs-provisioner
    spec:
      serviceAccountName: nfs-provisioner
      containers:
        - name: nfs-provisioner
          image: registry.k8s.io/sig-storage/nfs-subdir-external-provisioner:v4.0.2
          volumeMounts:
            - name: nfs-root
              mountPath: /persistentvolumes
          env:
            - name: PROVISIONER_NAME
              value: nfs-storage
            - name: NFS_SERVER
              value: 192.168.100.10
            - name: NFS_PATH
              value: /data/nfs
      volumes:
        - name: nfs-root
          nfs:
            server: 192.168.100.10
            path: /data/nfs
---
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: nfs-storage
provisioner: nfs-storage
parameters:
  archiveOnDelete: "true"
reclaimPolicy: Retain
volumeBindingMode: Immediate
EOF

echo -e "${GREEN}✓ NFS Provisioner 已部署${NC}"

#=========================================================================
# 完成
#=========================================================================
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  NFS 配置完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}验证 NFS 服务:${NC}"
echo "  showmount -e"
echo ""
echo -e "${YELLOW}验证 StorageClass:{NC}"
echo "  kubectl get storageclass"
echo ""
echo -e "${YELLOW}确认 NFS Provisioner 运行状态:{NC}"
echo "  kubectl get pods -n yolo-system"
echo ""
