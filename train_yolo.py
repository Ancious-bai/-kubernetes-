import os

os.environ['YOLO_CONFIG_DIR'] = '/tmp/Ultralytics'
os.environ['ULTRALYTICS_DISABLE_AUTOUPDATE'] = '1'

from ultralytics import YOLO
import argparse


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--site', type=str, required=True, help="yaml文件根目录")
    parser.add_argument('--epochs', type=int, default=2, help="训练轮数")
    parser.add_argument('--imgsz', type=int, default=640, help="图像尺寸")
    args = parser.parse_args()

    site = args.site
    data_yaml = f"{site}/data.yaml"

    print(f"正在训练{site}样本集")
    print(f"训练参数: epochs={args.epochs}, imgsz={args.imgsz}")

    model = YOLO("yolov8n.pt")
    model.train(
        data=data_yaml,
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=4,
        workers=1,
        name=f"{site}_train",
        device="cpu",
        plots=False,
        visualize=False,
        cache=False,
    )


if __name__ == "__main__":
    main()
