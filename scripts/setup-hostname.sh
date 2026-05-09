#!/bin/bash
#=============================================================================
# 脚本名称: setup-hostname.sh
# 脚本用途: 配置主机名和 hosts 文件
# 使用方法: ./setup-hostname.sh <主机名>
# 示例:
#   ./setup-hostname.sh k8s-master
#   ./setup-hostname.sh k8s-worker1
#   ./setup-hostname.sh k8s-worker2
#=============================================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 检查参数
if [ -z "$1" ]; then
    echo ""
    echo -e "${RED}错误: 缺少主机名参数！${NC}"
    echo ""
    echo -e "${YELLOW}使用方法:${NC}"
    echo "  $0 <主机名>"
    echo ""
    echo -e "${YELLOW}示例:${NC}"
    echo "  $0 k8s-master"
    echo "  $0 k8s-worker1"
    echo "  $0 k8s-worker2"
    echo ""
    exit 1
fi

NEW_HOSTNAME="$1"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  开始配置主机名${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}新主机名: ${NEW_HOSTNAME}${NC}"
echo ""

#=========================================================================
# 第1步: 设置主机名
#=========================================================================
echo -e "${GREEN}[1/3] 设置主机名...${NC}"

hostnamectl set-hostname "$NEW_HOSTNAME"
hostnamectl set-hostname --static "$NEW_HOSTNAME"
hostnamectl set-hostname --pretty "$NEW_HOSTNAME"

echo -e "${GREEN}✓ 主机名已设置为: ${NEW_HOSTNAME}${NC}"

#=========================================================================
# 第2步: 更新 /etc/hosts
#=========================================================================
echo -e "${GREEN}[2/3] 更新 /etc/hosts...${NC}"

# 备份原文件
cp /etc/hosts /etc/hosts.backup

# 创建新的 hosts 文件
cat > /etc/hosts <<EOF
127.0.0.1 localhost
127.0.1.1 ${NEW_HOSTNAME}

# Kubernetes 集群节点
192.168.100.10 k8s-master
192.168.100.11 k8s-worker1
192.168.100.12 k8s-worker2

# IPv6 addresses (如果需要)
::1     ip6-localhost ip6-loopback
fe00::0 ip6-localnet
ff00::0 ip6-mcastprefix
ff02::1 ip6-allnodes
ff02::2 ip6-allrouters
EOF

echo -e "${GREEN}✓ /etc/hosts 已更新${NC}"

#=========================================================================
# 第3步: 验证配置
#=========================================================================
echo -e "${GREEN}[3/3] 验证配置...${NC}"

echo -e "${YELLOW}当前主机名:${NC}"
hostname

echo ""
echo -e "${YELLOW}hosts 文件内容:${NC}"
cat /etc/hosts

#=========================================================================
# 完成
#=========================================================================
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  主机名配置完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}建议重新登录以使主机名完全生效${NC}"
