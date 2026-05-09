#!/bin/bash
# 仅在 master 节点执行

set -e

MASTER_IP="192.168.100.10"

echo "=== 开始初始化 Master 节点 ==="

# 设置主机名
hostnamectl set-hostname k8s-master

# 初始化集群
echo "初始化 Kubernetes 集群..."
kubeadm init \
  --apiserver-advertise-address=${MASTER_IP} \
  --image-repository registry.aliyuncs.com/google_containers \
  --kubernetes-version v1.28.0 \
  --service-cidr=10.96.0.0/12 \
  --pod-network-cidr=10.244.0.0/16

# 配置 kubectl
echo "配置 kubectl..."
mkdir -p $HOME/.kube
cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
chown $(id -u):$(id -g) $HOME/.kube/config

# 安装 Calico
echo "安装 Calico 网络插件..."
kubectl apply -f https://docs.projectcalico.org/manifests/calico.yaml

# 保存加入命令
echo "保存加入集群命令..."
kubeadm token create --print-join-command > /tmp/join-command.sh

echo "=== Master 节点初始化完成 ==="
echo "请保存以下命令，在 Worker 节点执行："
cat /tmp/join-command.sh
