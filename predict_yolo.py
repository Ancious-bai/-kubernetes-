import os, shutil, sys, glob

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


def collect_all_images(source_dir):
    image_extensions = ('*.jpg', '*.jpeg', '*.png', '*.bmp', '*.webp')
    all_images = []
    for ext in image_extensions:
        all_images.extend(glob.glob(os.path.join(source_dir, '**', ext), recursive=True))
        all_images.extend(glob.glob(os.path.join(source_dir, '**', ext.upper()), recursive=True))
    return sorted(set(all_images))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', type=str, required=True, help="模型文件路径")
    parser.add_argument('--source', type=str, required=True, help="推理数据源(预处理数据集目录)")
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

    images_dir = os.path.join(source, 'images') if os.path.isdir(source) else source
    if not os.path.exists(images_dir):
        print(f"ERROR: 图片目录不存在: {images_dir}")
        sys.exit(1)

    all_images = collect_all_images(images_dir)
    print(f"Found {len(all_images)} images to predict")

    if len(all_images) == 0:
        print("WARNING: No images found in source directory!")
        subdirs = [d for d in os.listdir(images_dir) if os.path.isdir(os.path.join(images_dir, d))]
        print(f"Subdirectories in images/: {subdirs}")

    base_name = args.name if args.name else os.path.basename(model_path).replace('.pt', '') + "_predict"
    output_name = base_name

    model = YOLO(model_path)
    if not os.path.exists(FONT_PATH) or os.path.getsize(FONT_PATH) == 0:
        for src in SYSTEM_FONT_CANDIDATES:
            if os.path.exists(src):
                shutil.copy(src, FONT_PATH); break

    results = model.predict(
        source=images_dir,
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
        result_images = collect_all_images(output_dir)
        print(f"Generated {len(result_images)} prediction results")

    print("PREDICTION_COMPLETE")


if __name__ == "__main__":
    main()
