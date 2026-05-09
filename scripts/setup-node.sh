#!/bin/bash
#=============================================================================
# 脚本名称: setup-node.sh
# 脚本用途: 初始化所有 Kubernetes 节点（所有节点都执行）
# 使用方法: chmod +x setup-node.sh && ./setup-node.sh
#=============================================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  开始初始化 Kubernetes 节点${NC}"
echo -e "${GREEN}========================================${NC}"

# 获取当前主机名
CURRENT_HOSTNAME=$(hostname)
echo -e "${YELLOW}当前主机名: $CURRENT_HOSTNAME${NC}"

#=========================================================================
# 第1步: 配置 hosts 文件
#=========================================================================
echo -e "${GREEN}[1/8] 配置 hosts 文件...${NC}"
cat >> /etc/hosts <<EOF
192.168.100.10 k8s-master
192.168.100.11 k8s-worker1
192.168.100.12 k8s-worker2
EOF
echo -e "${GREEN}✓ hosts 文件已配置${NC}"

#=========================================================================
# 第2步: 关闭防火墙
#=========================================================================
echo -e "${GREEN}[2/8] 关闭防火墙...${NC}"
systemctl stop ufw 2>/dev/null || true
systemctl disable ufw 2>/dev/null || true
echo -e "${GREEN}✓ 防火墙已关闭${NC}"

#=========================================================================
# 第3步: 关闭 swap
#=========================================================================
echo -e "${GREEN}[3/8] 关闭 swap...${NC}"
swapoff -a
sed -i '/ swap / s/^\(.*\)$/#\1/g' /etc/fstab
echo -e "${GREEN}✓ swap 已关闭${NC}"

#=========================================================================
# 第4步: 加载内核模块
#=========================================================================
echo -e "${GREEN}[4/8] 加载内核模块...${NC}"
cat > /etc/modules-load.d/k8s.conf <<EOF
overlay
br_netfilter
EOF

modprobe overlay 2>/dev/null || true
modprobe br_netfilter 2>/dev/null || true
echo -e "${GREEN}✓ 内核模块已加载${NC}"

#=========================================================================
# 第5步: 配置内核参数
#=========================================================================
echo -e "${GREEN}[5/8] 配置内核参数...${NC}"
cat > /etc/sysctl.d/k8s.conf <<EOF
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF

sysctl --system > /dev/null 2>&1
echo -e "${GREEN}✓ 内核参数已配置${NC}"

#=========================================================================
# 第6步: 安装 Docker
#=========================================================================
echo -e "${GREEN}[6/8] 安装 Docker...${NC}"

# 添加 Docker GPG 密钥
mkdir -p /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg 2>/dev/null

# 添加 Docker 仓库
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null

# 安装 Docker
apt-get update -qq
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin > /dev/null 2>&1

# 配置 containerd
mkdir -p /etc/containerd
containerd config default > /etc/containerd/config.toml
sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
systemctl restart containerd
systemctl enable containerd

# 配置 Docker daemon
cat > /etc/docker/daemon.json <<EOF
{
  "exec-opts": ["native.cgroupdriver=systemd"],
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "100m"
  },
  "storage-driver": "overlay2"
}
EOF

systemctl restart docker
systemctl enable docker
echo -e "${GREEN}✓ Docker 已安装并启动${NC}"

#=========================================================================
# 第7步: 安装 Kubernetes 组件
#=========================================================================
echo -e "${GREEN}[7/8] 安装 Kubernetes 组件...${NC}"

# 添加 Kubernetes GPG 密钥
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.28/deb/Release.key | gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg 2>/dev/null

# 添加 Kubernetes 仓库
echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.28/deb/ /' | tee /etc/apt/sources.list.d/kubernetes.list > /dev/null

# 安装 kubelet kubeadm kubectl
apt-get update -qq
apt-get install -y kubelet kubeadm kubectl > /dev/null 2>&1
apt-mark hold kubelet kubeadm kubectl

systemctl enable --now kubelet
echo -e "${GREEN}✓ Kubernetes 组件已安装${NC}"

#=========================================================================
# 第8步: 安装 NFS 客户端
#=========================================================================
echo -e "${GREEN}[8/8] 安装 NFS 客户端...${NC}"
apt-get install -y nfs-common > /dev/null 2>&1
echo -e "${GREEN}✓ NFS 客户端已安装${NC}"

#=========================================================================
# 完成
#=========================================================================
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  节点初始化完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}下一步操作：${NC}"
echo ""
echo -e "${YELLOW}如果是 k8s-master 节点，执行:${NC}"
echo "  ./setup-master.sh"
echo ""
echo -e "${YELLOW}如果是 k8s-worker 节点，执行:${NC}"
echo "  kubeadm join 192.168.100.10:6443 --token <token> --discovery-token-ca-cert-hash sha256:<hash>"
echo ""
