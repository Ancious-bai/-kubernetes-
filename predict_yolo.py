import os, shutil, sys

YOLO_DIR = '/tmp/Ultralytics'
os.environ['YOLO_CONFIG_DIR'] = YOLO_DIR
os.environ['ULTRALYTICS_DISABLE_AUTOUPDATE'] = '1'
os.makedirs(YOLO_DIR, exist_ok=True)

SYSTEM_FONT_CANDIDATES = [
    '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
    '/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',
    '/usr/share/fonts/TTF/DejaVuSans.ttf',
    '/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf',
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
    parser.add_argument('--model', type=str, required=True, help="模型文件路径")
    parser.add_argument('--source', type=str, required=True, help="推理数据源(图片目录或单张图片)")
    parser.add_argument('--name', type=str, default=None, help="输出目录名")
    parser.add_argument('--imgsz', type=int, default=640, help="图像尺寸")
    args = parser.parse_args()

    data_root = os.environ.get('DATA_ROOT', '/app/data')
    project_dir = os.path.join(data_root, "runs", "detect")

    model_path = args.model
    if not os.path.isabs(model_path):
        model_path = os.path.join(data_root, model_path)
    print(f"Using model: {model_path}")
    if not os.path.exists(model_path):
        print(f"ERROR: 模型文件不存在: {model_path}")
        sys.exit(1)

    source = args.source
    if not os.path.isabs(source):
        source = os.path.join(data_root, source)
    print(f"Source: {source}")

    base_name = args.name if args.name else os.path.basename(model_path).replace('.pt', '') + "_predict"
    output_name = base_name

    model = YOLO(model_path)
    if not os.path.exists(FONT_PATH) or os.path.getsize(FONT_PATH) == 0:
        for src in SYSTEM_FONT_CANDIDATES:
            if os.path.exists(src):
                shutil.copy(src, FONT_PATH); break

    results = model.predict(
        source=source,
        imgsz=args.imgsz,
        save=True,
        project=project_dir,
        name=output_name,
        exist_ok=True,
        conf=0.25,
        iou=0.45,
        verbose=True,
    )

    output_dir = os.path.join(project_dir, output_name)
    print(f"Output directory: {output_dir}")
    if os.path.exists(output_dir):
        files = os.listdir(output_dir)
        image_files = [f for f in files if f.lower().endswith(('.jpg', '.jpeg', '.png'))]
        print(f"Generated {len(image_files)} prediction images")

    print("PREDICTION_COMPLETE")


if __name__ == "__main__":
    main()
