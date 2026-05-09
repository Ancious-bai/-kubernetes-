#!/bin/bash
# 在 master 节点执行，设置 NFS 服务

set -e

echo "=== 开始设置 NFS 服务 ==="

# 安装 NFS
apt-get install -y nfs-kernel-server

# 创建共享目录
mkdir -p /data/nfs
chmod 777 /data/nfs

# 配置 NFS 导出
cat >> /etc/exports <<EOF
/data/nfs 192.168.100.0/24(rw,sync,no_root_squash,no_subtree_check)
EOF

# 重启 NFS
exportfs -a
systemctl restart nfs-kernel-server
systemctl enable nfs-kernel-server

echo "=== NFS 服务设置完成 ==="
echo "共享目录: /data/nfs"
