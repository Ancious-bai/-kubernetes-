# 三节点 Kubernetes 集群完整部署指南

## 一、硬件环境准备（VMware）

### 1.1 虚拟机配置建议

| 节点角色 | CPU | 内存 | 磁盘 | IP 地址 | 主机名 |
|---------|-----|------|------|---------|--------|
| k8s-master | 4核 | 8GB | 100GB | 192.168.100.10 | k8s-master |
| k8s-worker1 | 4核 | 8GB | 100GB | 192.168.100.11 | k8s-worker1 |
| k8s-worker2 | 4核 | 8GB | 100GB | 192.168.100.12 | k8s-worker2 |

### 1.2 操作系统
- Ubuntu 22.04 LTS（推荐）或 CentOS 7.x

---

## 二、系统环境准备（所有节点执行）

### 2.1 设置主机名

```bash
# k8s-master
hostnamectl set-hostname k8s-master

# k8s-worker1
hostnamectl set-hostname k8s-worker1

# k8s-worker2
hostnamectl set-hostname k8s-worker2
```

### 2.2 配置 hosts 文件

```bash
cat >> /etc/hosts <<EOF
192.168.100.10 k8s-master
192.168.100.11 k8s-worker1
192.168.100.12 k8s-worker2
EOF
```

### 2.3 关闭防火墙、swap、SELinux

```bash
# 关闭防火墙
systemctl stop ufw
systemctl disable ufw

# 关闭 swap
swapoff -a
sed -i '/ swap / s/^\(.*\)$/#\1/g' /etc/fstab

# 关闭 SELinux（如果是 CentOS）
setenforce 0
sed -i 's/^SELINUX=enforcing$/SELINUX=disabled/' /etc/selinux/config
```

### 2.4 加载内核模块

```bash
cat > /etc/modules-load.d/k8s.conf <<EOF
overlay
br_netfilter
EOF

modprobe overlay
modprobe br_netfilter
```

### 2.5 配置内核参数

```bash
cat > /etc/sysctl.d/k8s.conf <<EOF
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF

sysctl --system
```

### 2.6 安装 Docker/containerd

```bash
# 安装依赖
apt-get update
apt-get install -y ca-certificates curl gnupg lsb-release

# 添加 Docker GPG 密钥
mkdir -p /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg

# 添加 Docker 仓库
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null

# 安装 Docker
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

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
```

### 2.7 安装 kubectl、kubeadm、kubelet

```bash
# 添加 Kubernetes GPG 密钥
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.28/deb/Release.key | gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg

# 添加 Kubernetes 仓库
echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.28/deb/ /' | tee /etc/apt/sources.list.d/kubernetes.list

# 安装
apt-get update
apt-get install -y kubelet kubeadm kubectl
apt-mark hold kubelet kubeadm kubectl

systemctl enable --now kubelet
```

---

## 三、初始化 Kubernetes 集群（仅在 k8s-master 执行）

### 3.1 初始化集群

```bash
kubeadm init \
  --apiserver-advertise-address=192.168.100.10 \
  --image-repository registry.aliyuncs.com/google_containers \
  --kubernetes-version v1.28.0 \
  --service-cidr=10.96.0.0/12 \
  --pod-network-cidr=10.244.0.0/16
```

### 3.2 配置 kubectl

```bash
mkdir -p $HOME/.kube
cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
chown $(id -u):$(id -g) $HOME/.kube/config
```

### 3.3 安装 Calico 网络插件

```bash
kubectl apply -f https://docs.projectcalico.org/manifests/calico.yaml
```

### 3.4 保存加入集群命令

```bash
# 查看加入命令
kubeadm token create --print-join-command
```

---

## 四、加入 Worker 节点（在 k8s-worker1 和 k8s-worker2 执行）

```bash
# 使用上一步保存的命令，示例：
kubeadm join 192.168.100.10:6443 --token xxx --discovery-token-ca-cert-hash sha256:xxx
```

在 k8s-master 验证：

```bash
kubectl get nodes -o wide
```

应该看到三个节点都处于 Ready 状态。

---

## 五、配置 NFS 共享存储（在 k8s-master 执行）

### 5.1 安装 NFS 服务

```bash
apt-get install -y nfs-kernel-server

# 创建共享目录
mkdir -p /data/nfs
chmod 777 /data/nfs

# 配置 NFS 导出
cat >> /etc/exports <<EOF
/data/nfs 192.168.100.0/24(rw,sync,no_root_squash,no_subtree_check)
EOF

# 重启 NFS 服务
exportfs -a
systemctl restart nfs-kernel-server
systemctl enable nfs-kernel-server
```

### 5.2 在所有 Worker 节点安装 NFS 客户端

```bash
apt-get install -y nfs-common
```

### 5.3 部署 NFS Provisioner

```bash
kubectl apply -f yolo-project/k8s/00-nfs-provisioner.yaml
```

验证 StorageClass：

```bash
kubectl get storageclass
```

---

## 六、部署项目（在 k8s-master 执行）

### 6.1 配置镜像仓库（使用 Docker Hub 示例）

替换 k8s 配置文件中的 `<REGISTRY>` 为 `docker.io/ancious`：

```bash
cd yolo-project/k8s

# 修改配置文件
sed -i 's/<REGISTRY>/docker.io\/ancious/g' 01-config.yaml 04-backend.yaml 05-frontend.yaml
```

### 6.2 构建并推送镜像

在 Windows 开发机上执行：

```powershell
# 登录 Docker Hub
docker login

# 构建并推送
cd deploy
.\build-and-push.ps1 -Repository "docker.io/ancious" -Version "v1.0.0" -Push
```

### 6.3 部署到 Kubernetes

```powershell
.\deploy-k8s.ps1
```

### 6.4 验证部署

```bash
# 查看 Pod 状态
kubectl get pods -n yolo-system -o wide

# 查看 Service
kubectl get svc -n yolo-system

# 查看 PVC
kubectl get pvc -n yolo-system
```

---

## 七、节点分配策略

### 7.1 给 Worker 节点打标签

```bash
# worker1: 运行前端、后端、部分训练任务
kubectl label node k8s-worker1 node-role.kubernetes.io/worker=worker1

# worker2: 运行 MySQL、部分训练任务
kubectl label node k8s-worker2 node-role.kubernetes.io/worker=worker2
```

### 7.2 更新 MySQL 部署使用 NodeSelector

修改 `03-mysql.yaml`，添加 nodeSelector：

```yaml
spec:
  template:
    spec:
      nodeSelector:
        kubernetes.io/hostname: k8s-worker2
```

---

## 八、系统运行流程

### 8.1 用户访问流程

```
用户浏览器
    ↓
http://192.168.100.11:30080 (前端 NodePort)
    ↓
yolo-frontend Pod (k8s-worker1)
    ↓
yolo-backend Service
    ↓
yolo-backend Pod (k8s-worker1)
    ↓
MySQL Pod (k8s-worker2)
```

### 8.2 训练任务流程

1. 用户在前端提交训练任务
2. 后端接收请求，创建 Kubernetes Job
3. Job 调度到 worker1 或 worker2
4. 训练 Pod 挂载共享 PVC `/app/workspace`
5. 从 PVC 读取数据集、写入模型、保存结果
6. 后端监控训练进度并实时推送
7. 训练完成后自动执行测试

---

## 九、能达到的效果

### 9.1 功能效果

| 功能 | 说明 |
|------|------|
| 多节点调度 | 训练任务自动分布到 worker1/worker2 |
| 共享存储 | 所有节点可访问同一份数据集和模型 |
| 高可用 | 单节点故障不影响系统整体运行 |
| 资源隔离 | 通过 Pod requests/limits 限制资源 |
| 弹性伸缩 | 可随时添加新 worker 节点 |
| 日志监控 | 实时查看训练 Pod 日志 |
| 持久化数据 | MySQL 数据和训练结果持久化 |

### 9.2 性能效果

- **并发训练**：可同时运行多个训练 Job
- **负载均衡**：K8s 自动分配任务到空闲节点
- **快速恢复**：Pod 异常自动重启
- **资源利用率**：CPU/内存按需分配

---

## 十、优化建议

### 10.1 性能优化

1. **GPU 支持**（如果有 GPU）
```yaml
# 训练镜像使用 CUDA 版本
FROM nvidia/cuda:12.1.1-cudnn8-runtime-ubuntu22.04
```

2. **资源请求优化**
```yaml
resources:
  requests:
    cpu: "2"
    memory: "4Gi"
  limits:
    cpu: "4"
    memory: "8Gi"
```

3. **节点亲和性**
```yaml
affinity:
  nodeAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
    - weight: 1
      preference:
        matchExpressions:
        - key: gpu
          operator: In
          values: ["true"]
```

### 10.2 高可用优化

1. **后端多副本**
```yaml
replicas: 2
```

2. **MySQL 主从复制**
- 使用 StatefulSet 部署 MySQL 主从

3. **Ingress 高可用**
- 部署多个 Ingress Controller 副本

### 10.3 存储优化

1. **使用 Longhorn 替代 NFS**
- 提供更好的性能和可靠性

2. **PVC 扩容**
```yaml
resources:
  requests:
    storage: 100Gi
```

### 10.4 监控优化

1. **部署 Prometheus + Grafana**
```bash
helm install prometheus prometheus-community/kube-prometheus-stack
```

2. **添加资源监控面板**
- CPU/内存使用率
- Pod 状态
- Job 执行时间

---

## 十一、常用运维命令

### 11.1 查看集群状态

```bash
# 节点状态
kubectl get nodes -o wide

# 所有 Pod
kubectl get pods -A -o wide

# 服务状态
kubectl get svc -n yolo-system
```

### 11.2 查看日志

```bash
# 后端日志
kubectl logs -f -l app=yolo-backend -n yolo-system

# 训练 Pod 日志
kubectl logs -f <pod-name> -n yolo-system
```

### 11.3 进入 Pod

```bash
kubectl exec -it <pod-name> -n yolo-system -- /bin/bash
```

### 11.4 扩容/缩容

```bash
# 扩容后端到 2 副本
kubectl scale deployment yolo-backend -n yolo-system --replicas=2
```

---

## 十二、故障排查

### 12.1 Pod 一直 Pending

```bash
kubectl describe pod <pod-name> -n yolo-system
# 检查: 资源不足、PVC 未绑定、镜像拉取失败
```

### 12.2 PVC 一直 Pending

```bash
# 检查 NFS Provisioner
kubectl get pods -n nfs-provisioner

# 检查 StorageClass
kubectl get storageclass
```

### 12.3 节点 NotReady

```bash
# 查看节点状态
kubectl describe node <node-name>

# 检查 kubelet 状态
systemctl status kubelet
```

---

## 十三、访问系统

### 13.1 前端访问

```
http://192.168.100.11:30080
```

### 13.2 后端 API

```
http://192.168.100.11:30080/api/...
```

### 13.3 Kubernetes Dashboard（可选）

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.7.0/aio/deploy/recommended.yaml
kubectl proxy
# 访问: http://localhost:8001/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard:/proxy/
```
