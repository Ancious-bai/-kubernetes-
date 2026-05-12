import os, shutil

YOLO_DIR = '/tmp/Ultralytics'
os.environ['YOLO_CONFIG_DIR'] = YOLO_DIR
os.environ['ULTRALYTICS_DISABLE_AUTOUPDATE'] = '1'
os.makedirs(YOLO_DIR, exist_ok=True)

SYSTEM_FONT_CANDIDATES = [
    '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
    '/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',
    '/usr/share/fonts/TTF/DejaVuSans.ttf',
]

FONT_PATH = os.path.join(YOLO_DIR, 'Arial.ttf')
if not os.path.exists(FONT_PATH):
    for src in SYSTEM_FONT_CANDIDATES:
        if os.path.exists(src):
            shutil.copy(src, FONT_PATH)
            print(f"已复制系统字体 {src} -> {FONT_PATH}")
            break
    else:
        with open(FONT_PATH, 'w') as f:
            f.write('')
        print(f"警告: 未找到系统字体，已创建空占位 {FONT_PATH}")


from ultralytics import YOLO
import argparse


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--site', type=str, required=True, help="yaml文件根目录")
    parser.add_argument('--epochs', type=int, default=2, help="训练轮数")
    parser.add_argument('--imgsz', type=int, default=640, help="图像尺寸")
    parser.add_argument('--name', type=str, default=None, help="训练输出目录名")
    args = parser.parse_args()

    data_root = os.environ.get('DATA_ROOT', '/app/data')
    project_dir = os.path.join(data_root, "runs", "detect")
    site = args.site
    if not os.path.isabs(site):
        site = os.path.join(data_root, site)
    data_yaml = f"{site}/data.yaml"

    base_name = args.name if args.name else site
    train_name = base_name + "_train"

    print(f"正在训练{site}样本集")
    print(f"训练参数: epochs={args.epochs}, imgsz={args.imgsz}, name={train_name}")
    print(f"输出目录: {project_dir}/{train_name}")

    model_path = "/app/workspace/yolov8n.pt" if os.path.exists("/app/workspace/yolov8n.pt") else "yolov8n.pt"
    model = YOLO(model_path)
    model.train(
        data=data_yaml,
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=4,
        workers=1,
        name=train_name,
        project=project_dir,
        device="cpu",
        plots=True,
        visualize=True,
        cache=False,
    )


if __name__ == "__main__":
    main()
