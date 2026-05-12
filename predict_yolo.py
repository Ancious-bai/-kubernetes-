#!/usr/bin/env python3
import os, shutil, sys, glob

def setup_env():
    YOLO_DIR = '/tmp/Ultralytics'
    os.environ['YOLO_CONFIG_DIR'] = YOLO_DIR
    os.environ['ULTRALYTICS_DISABLE_AUTOUPDATE'] = '1'
    os.makedirs(YOLO_DIR, exist_ok=True)

    FONT_PATH = os.path.join(YOLO_DIR, 'Arial.ttf')
    if not os.path.exists(FONT_PATH) or os.path.getsize(FONT_PATH) == 0:
        candidates = [
            '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
            '/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',
            '/usr/share/fonts/TTF/DejaVuSans.ttf',
        ]
        for src in candidates:
            if os.path.exists(src):
                try:
                    shutil.copy(src, FONT_PATH)
                    break
                except:
                    pass
        if not os.path.exists(FONT_PATH):
            with open(FONT_PATH, 'w') as f:
                f.write('')

setup_env()

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
    parser.add_argument('--conf', type=float, default=0.05, help='置信度阈值(默认0.05)')
    args = parser.parse_args()

    data_root = os.environ.get('DATA_ROOT', '/app/data')
    project_dir = os.path.join(data_root, "runs", "detect")

    model_path = args.model
    if not os.path.isabs(model_path):
        model_path = os.path.join(data_root, model_path)

    print(f"[PREDICT] Model path: {model_path}")
    if not os.path.exists(model_path):
        print(f"[ERROR] Model file not found: {model_path}")
        sys.exit(1)

    source = args.source
    if not os.path.isabs(source):
        source = os.path.join(data_root, source)

    images_dir = source
    if os.path.isdir(source) and os.path.isdir(os.path.join(source, 'images')):
        images_dir = os.path.join(source, 'images')

    print(f"[PREDICT] Source directory: {images_dir}")

    all_images = collect_all_images(images_dir)
    print(f"[PREDICT] Found {len(all_images)} images")

    if len(all_images) == 0:
        print("[WARNING] No images found!")
        if os.path.isdir(images_dir):
            for item in os.listdir(images_dir):
                full = os.path.join(images_dir, item)
                if os.path.isdir(full):
                    sub_images = collect_all_images(full)
                    print(f"  {item}/: {len(sub_images)} images")
        sys.exit(1)

    output_name = args.name or (os.path.basename(model_path).replace('.pt', '') + "_predict")
    print(f"[PREDICT] Output name: {output_name}")
    print(f"[PREDICT] Confidence threshold: {args.conf}")

    model = YOLO(model_path)
    print(f"[PREDICT] Model classes: {model.names}")

    has_top_level_images = any(
        f.lower().endswith(('.jpg', '.jpeg', '.png', '.bmp', '.webp'))
        and not os.path.basename(os.path.dirname(f)).startswith('.')
        for f in glob.glob(os.path.join(images_dir, '*'))
        if os.path.isfile(f)
    )

    print(f"[PREDICT] Starting prediction on {len(all_images)} images...")

    total_detections = 0
    predict_kwargs = dict(
        imgsz=args.imgsz,
        save=True,
        save_txt=True,
        save_conf=True,
        project=project_dir,
        name=output_name,
        exist_ok=True,
        conf=args.conf,
        iou=0.45,
    )

    if has_top_level_images:
        print(f"[PREDICT] Using directory mode (images found at top level)")
        results = model.predict(source=images_dir, verbose=True, **predict_kwargs)
        for r in results:
            n = len(r.boxes) if r.boxes is not None else 0
            total_detections += n
            if n > 0:
                print(f"  {os.path.basename(r.path)}: {n} detections")
    else:
        print(f"[PREDICT] Using list mode (images in subdirectories)")
        batch_size = min(len(all_images), 16)
        for i in range(0, len(all_images), batch_size):
            batch = all_images[i:i + batch_size]
            batch_num = i // batch_size + 1
            total_batches = (len(all_images) + batch_size - 1) // batch_size
            print(f"[PREDICT] Processing batch {batch_num}/{total_batches} ({len(batch)} images)")
            try:
                results = model.predict(source=batch, verbose=False, **predict_kwargs)
                for r in results:
                    n = len(r.boxes) if r.boxes is not None else 0
                    total_detections += n
                    if n > 0:
                        print(f"  {os.path.basename(r.path)}: {n} detections")
            except Exception as e:
                print(f"[WARNING] Batch {batch_num} failed: {e}")
                continue

    print(f"[PREDICT] Total detections: {total_detections}")

    if total_detections == 0:
        print("[WARNING] No objects detected! Try lowering --conf threshold (current: {})".format(args.conf))

    output_dir = os.path.join(project_dir, output_name)
    result_count = 0
    if os.path.exists(output_dir):
        result_images = [f for f in collect_all_images(output_dir) if 'labels' not in f]
        result_count = len(result_images)
        print(f"[PREDICT] Generated {result_count} prediction images")

        labels_dir = os.path.join(output_dir, 'labels')
        if os.path.exists(labels_dir):
            label_files = [f for f in os.listdir(labels_dir) if f.endswith('.txt')]
            print(f"[PREDICT] Generated {len(label_files)} label files (detection results in YOLO format)")
    else:
        print(f"[WARNING] Output directory not created: {output_dir}")

    print("PREDICTION_COMPLETE")


if __name__ == "__main__":
    main()
