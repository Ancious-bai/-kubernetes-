# VMware 虚拟机网络配置指南

## 📋 前提条件

- VMware Workstation / Fusion / Player 已安装
- Ubuntu 22.04 LTS 已安装（在 3 台虚拟机中）

---

## 🎯 目标配置

| 虚拟机 | IP 地址 | 主机名 | 角色 |
|--------|---------|--------|------|
| VM 1 | 192.168.100.10 | k8s-master | Master 节点 |
| VM 2 | 192.168.100.11 | k8s-worker1 | Worker 节点 |
| VM 3 | 192.168.100.12 | k8s-worker2 | Worker 节点 |

> **重要**: 如果你的 VMware 网段不是 `192.168.100.x`，需要替换为实际网段

---

## 第一部分：VMware 虚拟网络配置

### 步骤 1.1：打开 VMware 虚拟网络编辑器

**Windows/Linux:**
1. 打开 VMware Workstation
2. 点击 `编辑` → `虚拟网络编辑器`

**macOS:**
1. 打开 VMware Fusion
2. 点击 `虚拟机` → `设置` → `网络适配器`

### 步骤 1.2：配置 NAT 模式网络

1. 选择 **VMnet8** (NAT 模式)
2. 勾选 **"将主机虚拟适配器连接到此网络"**
3. 记录子网 IP，例如：`192.168.100.0`
4. 点击 **NAT 设置**，记录网关 IP，例如：`192.168.100.2`

> 如果你的网段不是 `192.168.100.x`，修改为你要使用的网段

### 步骤 1.3：确认 DHCP 设置

1. 点击 **DHCP 设置**
2. 记录地址池范围，例如：`192.168.100.128` ~ `192.168.100.254`
3. 确保你使用的静态 IP (10-12) 不在 DHCP 池中，或禁用 DHCP

---

## 第二部分：配置虚拟机网络适配器

### 步骤 2.1：为每台虚拟机设置网络

对每个虚拟机 (k8s-master, k8s-worker1, k8s-worker2) 执行：

1. 关闭虚拟机
2. 编辑虚拟机设置
3. 选择 **网络适配器**
4. 选择 **NAT 模式**
5. 确保已勾选 **"连接"**
6. 启动虚拟机

---

## 第三部分：Ubuntu 静态 IP 配置

### 方法 A：使用图形界面（推荐新手）

#### 在每个虚拟机中执行：

1. **打开设置**
   ```bash
   # 或点击右上角网络图标 → 有线设置
   gnome-control-center network
   ```

2. **配置有线网络**
   - 点击 "有线" (Wired)
   - 点击齿轮图标
   - 选择 "IPv4" 标签

3. **设置静态 IP**
   - 方法：改为 "手动" (Manual)
   - 地址：填入 IP（如 `192.168.100.0`）
   - 网关：填入 VMware NAT 网关（如 `192.168.100.2`）
   - DNS：填入 `8.8.8.8,8.8.4.4`

4. **保存并应用**

---

### 方法 B：使用命令行（推荐）

#### 创建网络配置脚本
<minimax:tool_call>
<invoke name="Write">
<parameter name="content">#!/bin/bash
#=============================================================================
# 脚本名称: setup-network.sh
# 脚本用途: 配置 Ubuntu 静态 IP 地址
# 使用方法: ./setup-network.sh <IP地址> <网关>
# 示例:
#   ./setup-network.sh 192.168.100.10 192.168.100.2
#=============================================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 检查参数
if [ -z "$1" ] || [ -z "$2" ]; then
    echo ""
    echo -e "${RED}错误: 缺少参数！${NC}"
    echo ""
    echo -e "${YELLOW}使用方法:${NC}"
    echo "  $0 <IP地址> <网关>"
    echo ""
    echo -e "${YELLOW}示例:${NC}"
    echo "  $0 192.168.100.10 192.168.100.2"
    echo ""
    exit 1
fi

STATIC_IP="$1"
GATEWAY="$2"
DNS_SERVER="8.8.8.8,8.8.4.4"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  开始配置静态 IP${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}IP 地址: ${STATIC_IP}${NC}"
echo -e "${YELLOW}网关: ${GATEWAY}${NC}"
echo ""

#=========================================================================
# 第1步: 确定网络接口名称
#=========================================================================
echo -e "${GREEN}[1/4] 确定网络接口...${NC}"

# 列出所有网络接口
echo "可用网络接口:"
ip -o link show | awk -F': ' '{print $2}'

# 通常是 ens33 或 eth0，选择第一个非 loopback 接口
INTERFACE=$(ip -o link show | grep -v "lo:" | awk -F': ' '{print $2}' | head -n1)

if [ -z "$INTERFACE" ]; then
    echo -e "${RED}错误: 无法确定网络接口${NC}"
    exit 1
fi

echo -e "${GREEN}✓ 使用接口: ${INTERFACE}${NC}"

#=========================================================================
# 第2步: 备份原配置
#=========================================================================
echo -e "${GREEN}[2/4] 备份原配置...${NC}"

NETPLAN_FILE=$(ls /etc/netplan/*.yaml 2>/dev/null | head -n1)

if [ -f "$NETPLAN_FILE" ]; then
    cp "$NETPLAN_FILE" "${NETPLAN_FILE}.backup"
    echo -e "${GREEN}✓ 已备份到: ${NETPLAN_FILE}.backup${NC}"
else
    echo -e "${YELLOW}未找到 netplan 配置文件，将创建新配置${NC}"
fi

#=========================================================================
# 第3步: 配置静态 IP (使用 netplan)
#=========================================================================
echo -e "${GREEN}[3/4] 配置静态 IP...${NC}"

# 创建新的 netplan 配置
cat > /etc/netplan/01-netcfg.yaml <<EOF
network:
  version: 2
  renderer: networkd
  ethernets:
    ${INTERFACE}:
      dhcp4: no
      addresses:
        - ${STATIC_IP}/24
      gateway4: ${GATEWAY}
      nameservers:
        addresses:
          - 8.8.8.8
          - 8.8.4.4
EOF

chmod 600 /etc/netplan/01-netcfg.yaml
echo -e "${GREEN}✓ netplan 配置已创建${NC}"

#=========================================================================
# 第4步: 应用配置
#=========================================================================
echo -e "${GREEN}[4/4] 应用网络配置...${NC}"

# 应用配置
netplan apply

# 验证
sleep 2
echo -e "${GREEN}✓ 配置已应用${NC}"

# 显示新配置
echo ""
echo -e "${YELLOW}新 IP 配置:${NC}"
ip addr show "$INTERFACE" | grep "inet "

#=========================================================================
# 完成
#=========================================================================
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  网络配置完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}验证网络连接:${NC}"
echo "  ping -c 3 ${GATEWAY}"
echo "  ping -c 3 8.8.8.8"
echo ""
