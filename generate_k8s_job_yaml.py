#!/usr/bin/env python3
"""
Kubernetes Job YAML 生成脚本
用于生成 YOLO 训练/测试任务的 Kubernetes Job 配置文件
"""

import argparse
import os
import sys
import yaml
from pathlib import Path, PureWindowsPath, PurePosixPath


def windows_to_wsl_pathlib(win_path):
    """
    使用 pathlib 将 Windows 路径转换为挂载在 /mnt/host/ 下的 WSL 路径。
    """
    # 1. 创建一个 Windows 路径对象
    win_obj = PureWindowsPath(win_path)

    # 2. 获取盘符（如 'E:'）并转为小写，去掉冒号
    drive = win_obj.drive.lower().rstrip(':')

    # 3. 【修正点】使用 .parts 属性来安全地获取路径部分
    # win_obj.parts 会返回一个元组，例如：('E:\\', 'Ancious', 'Desktop', '毕业设计', 'Code')
    # 我们通过切片 [1:] 来获取除盘符外的所有部分
    path_parts = win_obj.parts[1:]

    # 4. 使用 PurePosixPath 拼接最终的 WSL 路径
    # 从 "/mnt/host" 开始，依次拼接盘符和路径的各个部分
    wsl_path = PurePosixPath("/mnt/host", drive, *path_parts)
    return str(wsl_path)


def generate_k8s_job_yaml(data_name: str, job_type: str, output_dir: str = "k8s_jobs",
                          epochs: int = 2, imgsz: int = 640, record_name: str = None):
    """
    生成 Kubernetes Job YAML 配置文件

    :param data_name: 数据集名称（如 data1, data2）
    :param job_type: 任务类型（train 或 test）
    :param output_dir: 输出目录
    :param epochs: 训练轮数（仅train类型有效）
    :param imgsz: 图像尺寸
    :param record_name: 训练记录名称（可选，默认由data_name、epochs、imgsz生成）
    """
    if record_name is None:
        record_name = f"{data_name}-e{epochs}-i{imgsz}"

    # 构建命令
    command = ["python3", f"{job_type}_yolo.py", "--site", f"{data_name}_processed"]
    # 添加 --name 参数来指定结果目录名称
    command.extend(["--name", f"{record_name}_train"])
    if job_type == "train":
        command.extend(["--epochs", str(epochs), "--imgsz", str(imgsz)])
    elif job_type == "test":
        command.extend(["--imgsz", str(imgsz)])

    project_root = os.path.dirname(os.path.abspath(__file__))
    wsl_path = windows_to_wsl_pathlib(project_root)

    # 构建 Job 配置
    job_config = {
        "apiVersion": "batch/v1",
        "kind": "Job",
        "metadata": {
            "name": f"{record_name}-{job_type}-job",
            "labels": {
                "site": data_name,
                "type": job_type
            }
        },
        "spec": {
            "parallelism": 1,
            "completions": 1,
            "template": {
                "spec": {
                    "containers": [
                        {
                            "name": "yolo-container",
                            "image": "yolov8-project:latest",
                            "imagePullPolicy": "IfNotPresent",
                            "command": command,
                            "env": [
                                {
                                    "name": "PYTHONUNBUFFERED",
                                    "value": "1"
                                }
                            ],
                            "volumeMounts": [
                                {
                                    "name": "app-volume",
                                    "mountPath": "/app"
                                }
                            ]
                        }
                    ],
                    "restartPolicy": "Never",
                    "volumes": [
                        {
                            "name": "app-volume",
                            "hostPath": {
                                "path": wsl_path,
                                "type": "Directory"
                            }
                        }
                    ]
                }
            },
            "backoffLimit": 4
        }
    }

    # 确保输出目录存在
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    # 生成文件名
    yaml_filename = f"{record_name}-{job_type}.yaml"
    yaml_path = output_path / yaml_filename

    # 写入 YAML 文件
    with open(yaml_path, "w", encoding="utf-8") as f:
        yaml.dump(job_config, f, allow_unicode=True, default_flow_style=False, sort_keys=False)

    print(f"Generated {job_type} YAML: {yaml_path}")
    return str(yaml_path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="生成 Kubernetes Job YAML 配置文件")
    parser.add_argument("--data_name", type=str, required=True, help="数据集名称")
    parser.add_argument("--job_type", type=str, choices=["train", "test"], required=True, help="任务类型 (train/test)")
    parser.add_argument("--output_dir", type=str, default="k8s_jobs", help="输出目录 (默认: k8s_jobs)")
    parser.add_argument("--epochs", type=int, default=2, help="训练轮数 (仅train类型，默认: 2)")
    parser.add_argument("--imgsz", type=int, default=640, help="图像尺寸 (默认: 640)")
    parser.add_argument("--record_name", type=str, default=None, help="训练记录名称 (可选)")

    args = parser.parse_args()

    try:
        yaml_path = generate_k8s_job_yaml(
            data_name=args.data_name,
            job_type=args.job_type,
            output_dir=args.output_dir,
            epochs=args.epochs,
            imgsz=args.imgsz,
            record_name=args.record_name
        )
        print(f"SUCCESS: {yaml_path}")
        sys.exit(0)
    except Exception as e:
        print(f"ERROR: {str(e)}", file=sys.stderr)
        sys.exit(1)
