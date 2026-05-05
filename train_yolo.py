import os

# 设置环境变量，避免下载字体和更新检查
os.environ['YOLO_CONFIG_DIR'] = '/tmp/Ultralytics'
os.environ['ULTRALYTICS_DISABLE_AUTOUPDATE'] = '1'

from ultralytics import YOLO
import argparse


def main():
    # 解析命令行参数
    parser = argparse.ArgumentParser()
    parser.add_argument(
        '--site',
        type=str,
        required=True,
        help="yaml文件根目录"
    )
    args = parser.parse_args()

    site = args.site
    data_yaml = f"{site}/data.yaml"

    print(f"正在训练{site}样本集")

    model = YOLO("yolov8n.pt")
    model.train(
        data=data_yaml,
        epochs=2,
        imgsz=640,
        batch=4,
        workers=1,
        name=f"{site}_train",
        device="cpu",
        plots=False,  # 跳过图表生成，避免下载字体
        visualize=False,  # 禁用可视化
        cache=False,  # 禁用缓存
    )


if __name__ == "__main__":
    main()