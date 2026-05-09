# 系统架构说明

## 一、整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        用户浏览器                            │
└────────────────────────────┬────────────────────────────────┘
                             │ http://192.168.100.11:30080
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                    NodePort Service                          │
│              yolo-frontend (30080)                           │
└────────────────────────────┬────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
        ▼                    ▼                    ▼
┌───────────────┐   ┌───────────────┐   ┌───────────────┐
│  k8s-master   │   │ k8s-worker1   │   │ k8s-worker2   │
│  (控制平面)   │   │  (应用节点)   │   │  (数据节点)   │
├───────────────┤   ├───────────────┤   ├───────────────┤
│ - API Server  │   │ - Frontend    │   │ - MySQL       │
│ - Scheduler   │   │ - Backend     │   │ - Training    │
│ - Controller  │   │ - Training    │   │   Jobs        │
│ - etcd        │   │   Jobs        │   └───────────────┘
└───────┬───────┘   └───────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│                    NFS 共享存储                              │
│              /data/nfs (PVC: yolo-workspace)                 │
│  ┌───────────────────────────────────────────────────────┐ │
│  │ datasets/   - 训练数据集                               │ │
│  │ models/     - 模型文件                                 │ │
│  │ results/    - 训练结果                                 │ │
│  │ logs/       - 日志文件                                 │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## 二、节点分配详情

### k8s-master (192.168.100.10)
**职责：**
- Kubernetes 控制平面
- API Server、Scheduler、Controller Manager
- etcd 数据存储
- NFS 共享存储服务

**运行组件：**
- 无应用 Pod（仅控制平面）

### k8s-worker1 (192.168.100.11)
**职责：**
- 运行前端和后端应用
- 运行部分训练任务

**运行 Pod：**
- yolo-frontend
- yolo-backend
- yolov8-trainer (部分)

### k8s-worker2 (192.168.100.12)
**职责：**
- 运行 MySQL 数据库
- 运行部分训练任务

**运行 Pod：**
- yolo-mysql
- yolov8-trainer (部分)

## 三、数据流向

### 3.1 用户请求流程

```
用户浏览器
    ↓
前端 Pod (Vue.js)
    ↓
后端 Pod (Spring Boot)
    ↓
MySQL Pod (数据持久化)
    ↓
返回结果
```

### 3.2 训练任务流程

```
用户提交训练任务
    ↓
后端 API 接收请求
    ↓
创建 Kubernetes Job
    ↓
K8s Scheduler 调度到 worker1/worker2
    ↓
训练 Pod 启动
    ↓
挂载 PVC (/app/workspace)
    ↓
读取数据集 → 训练模型 → 保存结果
    ↓
任务完成，Pod 自动清理
```

## 四、存储架构

### 4.1 PVC 配置

| PVC 名称 | 用途 | 访问模式 | 存储类 |
|---------|------|---------|--------|
| yolo-workspace | 训练数据、模型、结果 | ReadWriteMany | nfs-storage |
| yolo-mysql-data | MySQL 数据 | ReadWriteOnce | nfs-storage |

### 4.2 目录结构

```
/data/nfs/
├── yolo-system-yolo-workspace-pvc-xxx/
│   ├── datasets/
│   │   ├── train/
│   │   ├── val/
│   │   └── test/
│   ├── models/
│   │   ├── yolov8n.pt
│   │   └── trained_model.pt
│   ├── results/
│   │   ├── exp1/
│   │   └── exp2/
│   └── logs/
│       └── training.log
└── yolo-system-yolo-mysql-data-pvc-xxx/
    └── mysql/
```

## 五、网络架构

### 5.1 Service 配置

| Service 名称 | 类型 | 端口 | 用途 |
|-------------|------|------|------|
| yolo-frontend | NodePort | 80:30080 | 前端访问 |
| yolo-backend | ClusterIP | 8080 | 后端 API |
| yolo-mysql | ClusterIP | 3306 | 数据库 |

### 5.2 Pod 网络

所有 Pod 通过 Calico CNI 插件通信，Pod 之间可直接互访。

## 六、高可用设计

### 6.1 应用层
- 后端可扩展为多副本 (replicas: 2+)
- 前端可扩展为多副本
- 通过 Service 负载均衡

### 6.2 数据层
- MySQL 可配置为主从复制
- NFS 可替换为分布式存储 (Longhorn/Ceph)

### 6.3 调度层
- 训练 Job 自动故障转移
- Pod 异常自动重启

## 七、资源分配

### 7.1 推荐配置

| 组件 | CPU 请求 | CPU 限制 | 内存请求 | 内存限制 |
|------|---------|---------|---------|---------|
| Frontend | 100m | 500m | 128Mi | 256Mi |
| Backend | 500m | 2000m | 512Mi | 1Gi |
| MySQL | 500m | 2000m | 512Mi | 2Gi |
| Trainer | 1000m | 4000m | 2Gi | 8Gi |

## 八、监控与日志

### 8.1 日志收集
- Pod 日志：`kubectl logs`
- 应用日志：PVC 持久化
- 系统日志：journald

### 8.2 监控建议
- Prometheus + Grafana
- 节点资源监控
- Pod 状态监控
- Job 执行时间统计
