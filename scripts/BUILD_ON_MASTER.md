# 在 Master 节点上构建镜像和部署

## 第一步：将代码传到 master 节点

### 方法 A：使用 scp（推荐）

在 Windows PowerShell 中执行：

```powershell
# 将整个 Code 目录传到 master
scp -r E:\Ancious\Desktop\毕业设计\Code ancious@192.168.100.10:~/
```

### 方法 B：使用 MobaXterm 的 SFTP

1. 打开 MobaXterm
2. 连接到 master (192.168.100.10)
3. 左侧 SFTP 面板，将 Code 文件夹拖拽到 ~/ 目录

### 方法 C：使用 Git

在 master 上执行：

```bash
# 如果你的代码在 GitHub 上
cd ~
git clone https://github.com/你的用户名/你的仓库.git Code
```

---

## 第二步：在 master 上构建镜像

```bash
# 进入脚本目录
cd ~/Code/scripts

# 赋予执行权限
chmod +x build-and-push.sh

# 构建并推送镜像（替换为你的 Docker Hub 用户名）
./build-and-push.sh docker.io/ancious v1.0.0 push
```

### 如果只想构建不推送（先测试）

```bash
./build-and-push.sh docker.io/ancious v1.0.0 nopush
```

### 构建时间预估

| 镜像 | 预计时间 |
|------|---------|
| yolov8-trainer | 10-20 分钟（需要下载 PyTorch） |
| yolo-backend | 5-10 分钟（Maven 下载依赖） |
| yolo-frontend | 3-5 分钟（npm install） |

---

## 第三步：替换 K8s 配置中的镜像仓库

```bash
cd ~/Code/yolo-project/k8s

# 替换 <REGISTRY> 为你的真实仓库地址
sed -i 's|<REGISTRY>|docker.io/ancious|g' 01-config.yaml
sed -i 's|<REGISTRY>|docker.io/ancious|g' 04-backend.yaml
sed -i 's|<REGISTRY>|docker.io/ancious|g' 05-frontend.yaml

# 验证替换结果
grep -n "REGISTRY\|ancious" 01-config.yaml 04-backend.yaml 05-frontend.yaml
```

---

## 第四步：部署项目

```bash
cd ~/Code/scripts

# 赋予执行权限
chmod +x deploy-all.sh

# 一键部署
./deploy-all.sh
```

---

## 第五步：验证部署

```bash
# 查看 Pod
kubectl get pods -n yolo-system -o wide

# 查看 PVC
kubectl get pvc -n yolo-system

# 查看 Service
kubectl get svc -n yolo-system
```

---

## 第六步：访问系统

```
http://192.168.100.11:30080
```

---

## ⚠️ 如果不想推送到 Docker Hub

如果你不想使用 Docker Hub（网络慢或不想公开镜像），可以在所有节点上直接使用本地镜像：

### 在 master 上构建后，导出镜像

```bash
# 导出镜像为 tar 文件
docker save docker.io/ancious/yolov8-trainer:v1.0.0 -o trainer.tar
docker save docker.io/ancious/yolo-backend:v1.0.0 -o backend.tar
docker save docker.io/ancious/yolo-frontend:v1.0.0 -o frontend.tar
```

### 传到 worker 节点并加载

```bash
# 传到 worker1
scp trainer.tar backend.tar frontend.tar ancious@192.168.100.11:~/

# 传到 worker2
scp trainer.tar backend.tar frontend.tar ancious@192.168.100.12:~/

# 在 worker1 上加载
ssh ancious@192.168.100.11
docker load -i trainer.tar
docker load -i backend.tar
docker load -i frontend.tar

# 在 worker2 上加载
ssh ancious@192.168.100.12
docker load -i trainer.tar
docker load -i backend.tar
docker load -i frontend.tar
```

### 修改 K8s 配置使用本地镜像

```bash
# 将 imagePullPolicy 改为 IfNotPresent
sed -i 's|imagePullPolicy: Always|imagePullPolicy: IfNotPresent|g' ~/Code/yolo-project/k8s/04-backend.yaml
sed -i 's|imagePullPolicy: Always|imagePullPolicy: IfNotPresent|g' ~/Code/yolo-project/k8s/05-frontend.yaml
```

---

## 🔧 常见问题

### Maven 构建后端镜像失败

```bash
# 手动测试 Maven 构建
cd ~/Code/yolo-project
./mvnw clean package -DskipTests

# 如果 Maven 下载依赖慢，配置阿里云镜像
mkdir -p ~/.m2
cat > ~/.m2/settings.xml <<'EOF'
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/central</url>
    </mirror>
  </mirrors>
</settings>
EOF
```

### npm 构建前端镜像失败

```bash
# 手动测试前端构建
cd ~/Code/yolo-project
npm install
npm run build
```

### Docker 构建时网络超时

```bash
# 配置 Docker 镜像加速
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://mirror.ccs.tencentyun.com",
    "https://registry.docker-cn.com"
  ]
}
EOF
sudo systemctl restart docker
```
