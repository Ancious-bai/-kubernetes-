import os, shutil, sys, glob

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


def collect_all_images(source_dir):
    extensions = ('*.jpg', '*.jpeg', '*.png', '*.bmp', '*.webp', '*.JPG', '*.JPEG', '*.PNG')
    results = []
    for ext in extensions:
        results.extend(glob.glob(os.path.join(source_dir, '**', ext), recursive=True))
    return sorted(set(results))


def main():
    parser = argparse.ArgumentParser(description='YOLO Predict Script')
    parser.add_argument('--model', required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--name', default=None)
    parser.add_argument('--imgsz', type=int, default=640)
    parser.add_argument('--conf', type=float, default=0.2)
    args = parser.parse_args()

    data_root = os.environ.get('DATA_ROOT', '/app/data')
    project_dir = os.path.join(data_root, "runs", "detect")

    model_path = args.model
    if not os.path.isabs(model_path):
        model_path = os.path.join(data_root, model_path)

    source = args.source
    if not os.path.isabs(source):
        source = os.path.join(data_root, source)

    images_dir = source
    if os.path.isdir(source) and os.path.isdir(os.path.join(source, 'images')):
        images_dir = os.path.join(source, 'images')

    all_images = collect_all_images(images_dir)

    if len(all_images) == 0:
        print("没有图片可供预测")
        sys.exit(1)

    output_name = args.name or (os.path.basename(model_path).replace('.pt', '') + "_predict")

    model = YOLO(model_path)

    output_dir = os.path.join(project_dir, output_name)
    if os.path.exists(output_dir):
        shutil.rmtree(output_dir)
    os.makedirs(output_dir, exist_ok=True)

    print(f"正在预测 {len(all_images)} 个图像...")

    model.predict(
        source=all_images,
        imgsz=args.imgsz,
        conf=args.conf,
        save=True,
        verbose=True
    )


if __name__ == "__main__":
    main()
