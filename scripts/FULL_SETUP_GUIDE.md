# 从零开始搭建三节点 Kubernetes 集群

## 📋 前提条件

已完成 VMware 虚拟机安装：
- ✅ Ubuntu 22.04 LTS 已安装在 3 台虚拟机
- ✅ 3 台虚拟机网络互通
- ✅ 有 sudo 权限的账号

---

## 🎯 虚拟机配置

| 虚拟机 | IP 地址 | 主机名 | 角色 |
|--------|---------|--------|------|
| VM 1 | 192.168.100.10 | k8s-master | Master 节点 |
| VM 2 | 192.168.100.11 | k8s-worker1 | Worker 节点 |
| VM 3 | 192.168.100.12 | k8s-worker2 | Worker 节点 |

> ⚠️ 如果你的 IP 地址不同，请将教程中的 `192.168.100.x` 替换为你的实际 IP

---

## 📁 准备阶段

### 第 0 步：将脚本上传到虚拟机

将 `scripts` 文件夹上传到每台虚拟机的 `/home/用户名/` 目录下：

```bash
# 在 Windows 上使用 scp 或其他工具上传
scp -r scripts/* user@192.168.100.10:~/scripts/
scp -r scripts/* user@192.168.100.11:~/scripts/
scp -r scripts/* user@192.168.100.12:~/scripts/
```

或者在 VMware 中直接复制粘贴文本内容到虚拟机终端。

---

## 🖥️ 第一阶段：初始化所有节点（3 台虚拟机都执行）

### 在每台虚拟机上执行：

```bash
# 进入脚本目录
cd ~/scripts

# 赋予执行权限
chmod +x setup-node.sh

# 执行初始化脚本
./setup-node.sh
```

### 脚本会自动完成：
1. ✅ 配置 hosts 文件
2. ✅ 关闭防火墙
3. ✅ 关闭 swap
4. ✅ 加载内核模块
5. ✅ 配置内核参数
6. ✅ 安装 Docker
7. ✅ 安装 kubelet kubeadm kubectl
8. ✅ 安装 NFS 客户端

### 验证所有节点就绪：
```bash
# 等待所有节点执行完毕后，在任意节点执行
kubectl version --client
docker --version
```

---

## 🖥️ 第二阶段：初始化 Master 节点（仅在 k8s-master 执行）

### 在 192.168.100.10 (k8s-master) 上执行：

```bash
cd ~/scripts
chmod +x setup-master.sh
./setup-master.sh
```

### 脚本会自动完成：
1. ✅ 设置主机名为 k8s-master
2. ✅ 初始化 Kubernetes 集群
3. ✅ 配置 kubectl
4. ✅ 安装 Calico 网络插件
5. ✅ 允许 master 节点调度 Pod
6. ✅ 生成 Worker 节点加入命令

### 重要：保存输出的 Join 命令！

脚本会输出类似以下的命令，**复制并保存**：

```
kubeadm join 192.168.100.10:6443 --token xxxxx --discovery-token-ca-cert-hash sha256:xxxxx
```

---

## 🖥️ 第三阶段：Worker 节点加入集群（分别在两台 Worker 执行）

### 在 192.168.100.11 (k8s-worker1) 上执行：

```bash
cd ~/scripts
chmod +x setup-worker.sh

# 将第二阶段保存的完整 join 命令作为参数传入
./setup-worker.sh kubeadm join 192.168.100.10:6443 --token xxxxx --discovery-token-ca-cert-hash sha256:xxxxx
```

### 在 192.168.100.12 (k8s-worker2) 上执行：

```bash
cd ~/scripts
chmod +x setup-worker.sh

# 使用相同的 join 命令
./setup-worker.sh kubeadm join 192.168.100.10:6443 --token xxxxx --discovery-token-ca-cert-hash sha256:xxxxx
```

---

## 🖥️ 第四阶段：配置 NFS 共享存储（仅在 k8s-master 执行）

### 在 192.168.100.10 (k8s-master) 上执行：

```bash
cd ~/scripts
chmod +x setup-nfs.sh
./setup-nfs.sh
```

### 脚本会自动完成：
1. ✅ 安装 NFS 服务器
2. ✅ 创建共享目录 `/data/nfs`
3. ✅ 配置 NFS 导出
4. ✅ 启动 NFS 服务
5. ✅ 部署 NFS Provisioner 到 K8s

### 验证 NFS：
```bash
# 在 master 上验证
showmount -e

# 在任意 worker 上验证
showmount -e 192.168.100.10
```

---

## 🖥️ 第五阶段：验证集群状态（仅在 k8s-master 执行）

### 在 192.168.100.10 (k8s-master) 上执行：

```bash
# 查看节点状态
kubectl get nodes -o wide

# 应该看到 3 个节点，都是 Ready 状态
```

### 预期输出：
```
NAME            STATUS   ROLES           AGE   VERSION
k8s-master      Ready    control-plane   5m    v1.28.0
k8s-worker1     Ready    <none>          2m    v1.28.0
k8s-worker2     Ready    <none>          1m    v1.28.0
```

---

## 🖥️ 第六阶段：部署项目（仅在 k8s-master 执行）

### 先决条件：在 Windows 开发机上构建并推送镜像

```powershell
cd E:\Anxious\Desktop\毕业设计\Code\deploy

# 登录 Docker Hub
docker login

# 构建并推送镜像
.\build-and-push.ps1 -Repository "docker.io/anxious" -Version "v1.0.0" -Push
```

### 在 192.168.100.10 (k8s-master) 上执行：

```bash
cd ~/scripts
chmod +x deploy-project.sh

# 执行部署（使用你的镜像仓库地址）
./deploy-project.sh docker.io/anxious v1.0.0
```

### 如果使用阿里云镜像仓库：
```bash
./deploy-project.sh registry.cn-hangzhou.aliyuncs.com/anxious-yolo v1.0.0
```

---

## ✅ 第七阶段：验证部署

### 在 192.168.100.10 (k8s-master) 上执行：

```bash
# 查看所有 Pod
kubectl get pods -n yolo-system -o wide

# 查看 PVC
kubectl get pvc -n yolo-system

# 查看 Service
kubectl get svc -n yolo-system
```

### 预期结果：
- 3 个 yolo-mysql, yolo-backend, yolo-frontend Pod 都处于 Running 状态
- PVC 已 Bound
- Service 已创建

---

## 🌐 访问系统

打开浏览器访问：

```
http://192.168.100.11:30080
```

---

## 🔧 常用运维命令

### 查看集群状态
```bash
kubectl get nodes -o wide
kubectl get pods -A -o wide
kubectl get svc -n yolo-system
```

### 查看日志
```bash
# 后端日志
kubectl logs -f -l app=yolo-backend -n yolo-system

# MySQL 日志
kubectl logs -f -l app=yolo-mysql -n yolo-system

# 训练 Pod 日志
kubectl logs -f <pod-name> -n yolo-system
```

### 进入 Pod
```bash
kubectl exec -it <pod-name> -n yolo-system -- /bin/bash
```

### 重启 Pod
```bash
kubectl rollout restart deployment yolo-backend -n yolo-system
kubectl rollout restart deployment yolo-frontend -n yolo-system
```

### 删除部署
```bash
kubectl delete -f ../yolo-project/k8s/
```

---

## ❓ 常见问题

### 1. kubeadm init 卡住不动
```bash
# 取消重来
kubeadm reset
./setup-master.sh
```

### 2. Worker 加入失败
```bash
# 在 master 上重新生成 token
kubeadm token create --print-join-command
```

### 3. Pod 一直 Pending
```bash
# 查看原因
kubectl describe pod <pod-name> -n yolo-system
```

### 4. NFS PVC 无法绑定
```bash
# 检查 NFS Provisioner
kubectl get pods -n yolo-system -l app=nfs-provisioner

# 查看 Provisioner 日志
kubectl logs -f nfs-provisioner-xxx -n yolo-system
```

### 5. 节点是 NotReady
```bash
# 查看 kubelet 状态
systemctl status kubelet

# 查看详细错误
journalctl -u kubelet --no-pager
```

---

## 📞 故障排查流程

1. **确认网络互通**
   ```bash
   ping 192.168.100.10
   ping 192.168.100.11
   ```

2. **确认 Docker 运行**
   ```bash
   systemctl status docker
   ```

3. **确认 kubelet 运行**
   ```bash
   systemctl status kubelet
   ```

4. **查看集群状态**
   ```bash
   kubectl get nodes
   kubectl get pods -A
   ```

5. **查看详细事件**
   ```bash
   kubectl describe node <node-name>
   kubectl describe pod <pod-name> -n yolo-system
   ```

---

## 📝 快速参考表

| 操作 | 命令 |
|------|------|
| 所有节点初始化 | `./setup-node.sh` |
| Master 初始化 | `./setup-master.sh` |
| Worker 加入 | `./setup-worker.sh <join命令>` |
| 配置 NFS | `./setup-nfs.sh` |
| 部署项目 | `./deploy-project.sh <仓库> <版本>` |
| 查看节点 | `kubectl get nodes` |
| 查看 Pod | `kubectl get pods -A` |
| 查看日志 | `kubectl logs -f <pod> -n yolo-system` |
