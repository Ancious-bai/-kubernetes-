# 快速开始指南

## 前置条件

已完成 VMware 虚拟机创建：
- k8s-master (192.168.100.10)
- k8s-worker1 (192.168.100.11)
- k8s-worker2 (192.168.100.12)

## 第 1 步：准备所有节点

在 k8s-master、k8s-worker1、k8s-worker2 上执行：

```bash
# 设置主机名（分别执行）
# 在 k8s-master:
hostnamectl set-hostname k8s-master

# 在 k8s-worker1:
hostnamectl set-hostname k8s-worker1

# 在 k8s-worker2:
hostnamectl set-hostname k8s-worker2

# 上传并执行初始化脚本
chmod +x 01-prepare-all-nodes.sh
./01-prepare-all-nodes.sh
```

## 第 2 步：初始化 Master

在 k8s-master 上执行：

```bash
chmod +x 02-init-master.sh
./02-init-master.sh

# 保存输出的 join 命令，稍后在 Worker 节点使用
```

## 第 3 步：设置 NFS

在 k8s-master 上执行：

```bash
chmod +x 03-setup-nfs.sh
./03-setup-nfs.sh
```

## 第 4 步：加入 Worker 节点

在 k8s-worker1 和 k8s-worker2 上执行：

```bash
# 使用第 2 步保存的 join 命令，例如：
kubeadm join 192.168.100.10:6443 --token xxx --discovery-token-ca-cert-hash sha256:xxx
```

在 k8s-master 验证：

```bash
kubectl get nodes
# 应该看到 3 个 Ready 节点
```

## 第 5 步：构建并推送镜像（在 Windows 开发机）

```powershell
cd deploy

# 登录 Docker Hub
docker login

# 构建并推送镜像
.\build-and-push.ps1 -Repository "docker.io/ancious" -Version "v1.0.0" -Push
```

## 第 6 步：部署项目（在 k8s-master）

```bash
chmod +x 04-deploy-project.sh
./04-deploy-project.sh "docker.io/ancious" "v1.0.0"
```

## 第 7 步：访问系统

在浏览器打开：
```
http://192.168.100.11:30080
```

## 验证部署

```bash
# 查看 Pod
kubectl get pods -n yolo-system -o wide

# 查看 Service
kubectl get svc -n yolo-system

# 查看 PVC
kubectl get pvc -n yolo-system
```
