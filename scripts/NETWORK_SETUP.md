# 从零配置网络和主机名

## 📋 前提条件

- VMware Workstation / Fusion / Player 已安装
- Ubuntu 22.04 LTS 已安装在 3 台虚拟机
- 3 台虚拟机关闭状态

---

## 🎯 目标配置

| 虚拟机 | IP 地址 | 主机名 | 角色 |
|--------|---------|--------|------|
| VM 1 | 192.168.100.10 | k8s-master | Master 节点 |
| VM 2 | 192.168.100.11 | k8s-worker1 | Worker 节点 |
| VM 3 | 192.168.100.12 | k8s-worker2 | Worker 节点 |

---

## 第一部分：VMware 网络配置

### 步骤 1：检查 VMware NAT 网段

1. 打开 VMware Workstation
2. 点击 **编辑** → **虚拟网络编辑器**
3. 选择 **VMnet8** (NAT 模式)
4. 查看子网 IP，例如显示为 `192.168.100.0`

> **注意**：如果你的网段不是 `192.168.100.x`，记录下来，后面要用你的实际网段

5. 点击 **NAT 设置**，记录网关 IP，例如 `192.168.100.2`

### 步骤 2：确认 DHCP 设置

1. 点击 **DHCP 设置**
2. 记录地址池范围，例如 `192.168.100.128` ~ `192.168.100.254`
3. **重要**：我们将使用 `192.168.100.10-12`，这在 DHCP 池之外，所以没问题

### 步骤 3：配置每台虚拟机网络

对每个虚拟机执行：

1. 关闭虚拟机
2. 右键点击虚拟机 → **设置**
3. 选择 **网络适配器**
4. 选择 **NAT 模式**
5. 确保勾选 **"已连接"**
6. 点击 **确定**
7. 启动虚拟机

---

## 第二部分：配置静态 IP

### 方法 A：使用脚本（推荐）

#### 在 k8s-master 虚拟机中执行：

```bash
# 上传或复制 setup-network.sh 到虚拟机
# 然后执行：

chmod +x setup-network.sh
sudo ./setup-network.sh 192.168.100.10 192.168.100.2
```

#### 在 k8s-worker1 虚拟机中执行：

```bash
chmod +x setup-network.sh
sudo ./setup-network.sh 192.168.100.11 192.168.100.2
```

#### 在 k8s-worker2 虚拟机中执行：

```bash
chmod +x setup-network.sh
sudo ./setup-network.sh 192.168.100.12 192.168.100.2
```

---

### 方法 B：手动配置（图形界面）

#### 在 k8s-master 中执行：

1. 打开终端，输入：
   ```bash
   gnome-control-center network
   ```

2. 点击 **wired** (有线)

3. 点击齿轮图标

4. 选择 **IPv4** 标签

5. 修改为：
   - **方法**：手动
   - **地址**：192.168.100.10
   - **网关**：192.168.100.2
   - **DNS**：8.8.8.8, 8.8.4.4

6. 点击 **应用**，然后重启网络：
   ```bash
   sudo netplan apply
   ```

#### 对 worker1 和 worker2 重复以上步骤，IP 改为：
- worker1: 192.168.100.11
- worker2: 192.168.100.12

---

## 第三部分：配置主机名

### 在 k8s-master 虚拟机中执行：

```bash
chmod +x setup-hostname.sh
sudo ./setup-hostname.sh k8s-master
```

### 在 k8s-worker1 虚拟机中执行：

```bash
chmod +x setup-hostname.sh
sudo ./setup-hostname.sh k8s-worker1
```

### 在 k8s-worker2 虚拟机中执行：

```bash
chmod +x setup-hostname.sh
sudo ./setup-hostname.sh k8s-worker2
```

---

## 第四部分：验证网络配置

### 在任意虚拟机中测试网络连接：

```bash
# 测试网关
ping -c 3 192.168.100.2

# 测试互联网连接
ping -c 3 8.8.8.8

# 测试主机之间连通
ping -c 3 192.168.100.10
ping -c 3 192.168.100.11
ping -c 3 192.168.100.12
```

---

## 第五部分：使用 SSH（可选但推荐）

### 在 Windows 上使用 PowerShell 连接：

```powershell
# 连接 master
ssh ubuntu@192.168.100.10

# 连接 worker1
ssh ubuntu@192.168.100.11

# 连接 worker2
ssh ubuntu@192.168.100.12
```

### 启用 SSH 服务（如果未启用）：

```bash
# 在每台虚拟机上执行
sudo apt update
sudo apt install -y openssh-server
sudo systemctl enable ssh
sudo systemctl start ssh
```

---

## 第六部分：文件传输

### 从 Windows 传输脚本到虚拟机：

在 PowerShell 中执行：

```powershell
# 安装 OpenSSH 客户端（如果还没有）
# 使用 scp 传输

scp -r E:\Ancious\Desktop\毕业设计\Code\scripts\* ubuntu@192.168.100.10:~/

# 输入密码后，文件将传输到虚拟机的 home 目录
```

### 或者在虚拟机中使用共享文件夹

---

## 🎯 验证清单

完成以上步骤后，在任意虚拟机终端执行：

```bash
# 1. 确认主机名
hostname
# 应该显示: k8s-master 或 k8s-worker1 或 k8s-worker2

# 2. 确认 IP 地址
ip addr show
# 应该显示配置的静态 IP

# 3. 确认 /etc/hosts 配置正确
cat /etc/hosts
# 应该包含 3 个节点的 IP 和主机名

# 4. 测试节点间连通
ping -c 2 k8s-master
ping -c 2 k8s-worker1
ping -c 2 k8s-worker2

# 5. 测试外网连通
ping -c 2 baidu.com
```

---

## ⚠️ 常见问题

### 1. 无法获取 IP 地址
- 确认虚拟机网络适配器已启用
- 确认 VMware 网络服务正常运行
- 重启虚拟机

### 2. ping 不通网关
```bash
# 检查网络接口
ip addr show

# 检查路由
ip route

# 重新应用网络配置
sudo netplan apply
```

### 3. ping 不通其他节点
- 确认所有节点使用相同的网关
- 确认防火墙已关闭
  ```bash
  sudo ufw disable
  ```

### 4. 主机名未生效
- 重新登录或重启虚拟机
- 确认 /etc/hosts 第一行已修改

### 5. DNS 无法解析
```bash
# 检查 DNS 配置
systemd-resolve --status

# 编辑 DNS 配置
sudo nano /etc/resolv.conf
# 添加: nameserver 8.8.8.8
```

---

## 📝 快速参考

| 操作 | 命令 |
|------|------|
| 配置静态 IP | `sudo ./setup-network.sh <IP> <网关>` |
| 配置主机名 | `sudo ./setup-hostname.sh <主机名>` |
| 查看 IP | `ip addr show` |
| 查看主机名 | `hostname` |
| 测试连通性 | `ping -c 3 <IP>` |
| 应用网络配置 | `sudo netplan apply` |
| 关闭防火墙 | `sudo ufw disable` |
| 启用 SSH | `sudo systemctl enable ssh` |

---

## ✅ 下一步

网络配置完成后，继续执行：

1. **setup-node.sh** - 安装 Docker 和 Kubernetes
2. **setup-master.sh** - 初始化 Master
3. **setup-worker.sh** - 加入 Worker 节点
4. **setup-nfs.sh** - 配置 NFS 存储
5. **deploy-project.sh** - 部署项目
