#!/bin/bash
#=============================================================================
# 脚本名称: setup-master.sh
# 脚本用途: 初始化 Kubernetes Master 节点（仅在 k8s-master 执行）
# 使用方法: chmod +x setup-master.sh && ./setup-master.sh
#=============================================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

MASTER_IP="192.168.100.10"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  开始初始化 Kubernetes Master${NC}"
echo -e "${GREEN}========================================${NC}"

#=========================================================================
# 第1步: 设置主机名
#=========================================================================
echo -e "${GREEN}[1/6] 设置主机名为 k8s-master...${NC}"
hostnamectl set-hostname k8s-master
hostnamectl set-hostname --static k8s-master
echo -e "${GREEN}✓ 主机名已设置为 k8s-master${NC}"

#=========================================================================
# 第2步: 初始化 Kubernetes 集群
#=========================================================================
echo -e "${GREEN}[2/6] 初始化 Kubernetes 集群...${NC}"

# 检查是否已存在集群配置
if [ -f /etc/kubernetes/admin.conf ]; then
    echo -e "${YELLOW}集群已初始化，跳过...${NC}"
else
    kubeadm init \
      --apiserver-advertise-address=${MASTER_IP} \
      --image-repository registry.aliyuncs.com/google_containers \
      --kubernetes-version v1.28.0 \
      --service-cidr=10.96.0.0/12 \
      --pod-network-cidr=10.244.0.0/16
fi
echo -e "${GREEN}✓ 集群已初始化${NC}"

#=========================================================================
# 第3步: 配置 kubectl
#=========================================================================
echo -e "${GREEN}[3/6] 配置 kubectl...${NC}"
mkdir -p $HOME/.kube
cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
chown $(id -u):$(id -g) $HOME/.kube/config
echo -e "${GREEN}✓ kubectl 已配置${NC}"

#=========================================================================
# 第4步: 安装 Calico 网络插件
#=========================================================================
echo -e "${GREEN}[4/6] 安装 Calico 网络插件...${NC}"
kubectl apply -f https://docs.projectcalico.org/manifests/calico.yaml
echo -e "${GREEN}✓ Calico 已安装${NC}"

#=========================================================================
# 第5步: 允许 master 节点调度 Pod（可选）
#=========================================================================
echo -e "${GREEN}[5/6] 配置 master 节点调度...${NC}"
kubectl taint nodes --all node-role.kubernetes.io/control-plane:NoSchedule- 2>/dev/null || true
echo -e "${GREEN}✓ master 节点已允许调度 Pod${NC}"

#=========================================================================
# 第6步: 生成并保存加入命令
#=========================================================================
echo -e "${GREEN}[6/6] 生成 Worker 节点加入命令...${NC}"

JOIN_COMMAND=$(kubeadm token create --print-join-command)

cat > /tmp/worker-join-command.sh <<EOF
#!/bin/bash
# 在 Worker 节点执行此命令加入集群
${JOIN_COMMAND}
EOF

chmod +x /tmp/worker-join-command.sh

echo -e "${GREEN}✓ 加入命令已生成${NC}"

#=========================================================================
# 完成
#=========================================================================
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Master 节点初始化完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${BLUE}Worker 节点加入命令（复制到 Worker 节点执行）:${NC}"
echo -e "${YELLOW}========================================${NC}"
echo "${JOIN_COMMAND}"
echo -e "${YELLOW}========================================${NC}"
echo ""
echo -e "${YELLOW}或者将以下内容保存为 worker-join-command.sh 并执行:${NC}"
echo "  chmod +x worker-join-command.sh"
echo "  ./worker-join-command.sh"
echo ""
echo -e "${YELLOW}验证集群状态:${NC}"
echo "  kubectl get nodes"
echo ""
echo -e "${YELLOW}确认所有 Pod 就绪后，继续执行 setup-nfs.sh${NC}"
