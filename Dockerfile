# 使用官方 Python 3.10 slim 镜像作为基础
FROM python:3.10-slim

# 设置工作目录
WORKDIR /app

# 更新包列表并安装 opencv-python 所需的系统依赖库
# 使用适用于较新 Debian 版本的包名
RUN apt-get update && apt-get install -y \
    libglib2.0-0 \
    libsm6 \
    libxext6 \
    libxrender-dev \
    libgomp1 \
    libxcb1 \
    libgl1 \
    && rm -rf /var/lib/apt/lists/*

# 复制 requirements.txt 文件
COPY requirements.txt .

# 安装 Python 依赖包
RUN pip3 install torch torchvision --extra-index-url https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir -r requirements.txt
