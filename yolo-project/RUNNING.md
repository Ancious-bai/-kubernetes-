# YOLO 训练管理系统 - 运行说明

## 环境要求

1. **Java 21** - 后端运行环境
2. **Node.js 18+** - 前端运行环境
3. **Maven** - Java构建工具
4. **kubectl** - Kubernetes命令行工具（已配置连接到集群）
5. **Python 3.x** - 数据预处理脚本运行环境

## 运行步骤

### 1. 设置环境变量

```bash
# Windows PowerShell
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"

# 验证Java版本
java -version
```

### 2. 启动后端服务

```bash
cd E:\Ancious\Desktop\毕业设计\Code\yolo-project

# 编译项目
mvn clean compile

# 运行项目
mvn spring-boot:run
```

后端服务将运行在 `http://localhost:8080`

### 3. 启动前端服务

```bash
cd E:\Ancious\Desktop\毕业设计\Code\yolo-project

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

前端服务将运行在 `http://localhost:5173`

## 使用说明

### 界面操作

1. **输入数据路径**: 在输入框中输入数据目录路径，例如：
   ```
   E:\Ancious\Desktop\data1
   ```

2. **预处理**: 点击「预处理」按钮，系统将自动执行：
   ```
   python preprocess.py --input_dir <输入路径>
   ```

3. **训练**: 点击「开始训练」按钮，系统将：
   - 提交训练Job到Kubernetes
   - 监控训练进度
   - 训练完成后自动执行测试
   - 显示评估指标（mAP@0.5, mAP@0.95等）

4. **查看Pods**: 点击「刷新Pods」按钮查看当前运行的Pods列表

5. **查看Pod日志**: 点击Pods列表中的「查看日志」按钮查看具体Pod的日志

### API接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/preprocess` | POST | 启动预处理任务 |
| `/api/train` | POST | 启动训练任务 |
| `/api/status/{jobId}` | GET | 查询任务状态 |
| `/api/pods` | GET | 获取Pods列表 |
| `/api/pods/{podName}/logs` | GET | 获取Pod日志 |

## 项目结构

```
yolo-project/
├── src/
│   ├── main/
│   │   ├── java/com/example/yoloproject/
│   │   │   ├── YoloProjectApplication.java   # 启动类
│   │   │   ├── config/                        # 配置类
│   │   │   │   ├── WebConfig.java            # 跨域配置
│   │   │   │   └── WebSocketConfig.java      # WebSocket配置
│   │   │   ├── controller/                    # 控制器
│   │   │   │   ├── YoloController.java       # REST API
│   │   │   │   └── WebSocketController.java  # WebSocket推送
│   │   │   ├── service/                       # 服务层
│   │   │   │   └── YoloService.java          # 核心业务逻辑
│   │   │   └── dto/                           # 数据传输对象
│   │   │       ├── ProcessRequest.java
│   │   │       ├── JobStatus.java
│   │   │       ├── PodLog.java
│   │   │       └── MetricsResponse.java
│   │   └── resources/
│   │       └── application.properties         # 应用配置
│   ├── App.vue                                # 前端主组件
│   └── main.js                                # 前端入口文件
├── index.html
├── package.json
├── vite.config.js
└── pom.xml                                    # Maven配置
```

## 注意事项

1. 确保kubectl已正确配置并连接到目标Kubernetes集群
2. 确保Python环境已安装所需依赖（可通过 `pip install -r requirements.txt` 安装）
3. 预处理脚本 `preprocess.py` 需要位于项目根目录
4. Kubernetes Job配置文件将自动生成在 `k8s_jobs/` 目录

## 故障排除

### 编译错误
- 确保JAVA_HOME正确配置
- 确保网络通畅，Maven能下载依赖

### 连接错误
- 确保后端服务已启动在8080端口
- 确保前端代理配置正确

### Pod日志无法获取
- 确保kubectl配置正确
- 确保Pod名称正确

## 许可证

MIT License
