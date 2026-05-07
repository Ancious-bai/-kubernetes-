import os, shutil

os.environ['YOLO_CONFIG_DIR'] = '/tmp/Ultralytics'
os.environ['ULTRALYTICS_DISABLE_AUTOUPDATE'] = '1'

# 离线环境：确保字体文件存在，避免 check_font() 触发网络下载而崩溃
YOLO_DIR = '/tmp/Ultralytics'
os.makedirs(YOLO_DIR, exist_ok=True)
FONT_PATH = os.path.join(YOLO_DIR, 'Arial.ttf')
if not os.path.exists(FONT_PATH):
    for src in ['/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
                '/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',
                '/usr/share/fonts/TTF/DejaVuSans.ttf']:
        if os.path.exists(src):
            shutil.copy(src, FONT_PATH)
            break
    else:
        with open(FONT_PATH, 'w') as f:
            f.write('')

from ultralytics import YOLO
import argparse


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--site', type=str, required=True, help="yaml文件根目录")
    parser.add_argument('--epochs', type=int, default=2, help="训练轮数")
    parser.add_argument('--imgsz', type=int, default=640, help="图像尺寸")
    parser.add_argument('--name', type=str, default=None, help="训练输出目录名")
    args = parser.parse_args()

    site = args.site
    data_yaml = f"{site}/data.yaml"

    train_name = args.name if args.name else f"{site}_train"

    print(f"正在训练{site}样本集")
    print(f"训练参数: epochs={args.epochs}, imgsz={args.imgsz}, name={train_name}")

    model = YOLO("yolov8n.pt")
    model.train(
        data=data_yaml,
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=4,
        workers=1,
        name=train_name,
        device="cpu",
        plots=False,
        visualize=False,
        cache=False,
    )


if __name__ == "__main__":
    main()
