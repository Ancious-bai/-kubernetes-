#!/usr/bin/env python3
import os, shutil, sys, glob, json
from pathlib import Path


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


def save_labels_from_results(results, labels_dir, model_names):
    os.makedirs(labels_dir, exist_ok=True)
    total_detections = 0
    class_counts = {}
    per_image_results = []

    for r in results:
        img_path = getattr(r, 'path', '')
        img_name = os.path.basename(img_path) if img_path else 'unknown'
        img_stem = os.path.splitext(img_name)[0]
        n = len(r.boxes) if r.boxes is not None else 0
        total_detections += n

        image_info = {
            'image': img_name,
            'detections': n,
            'boxes': []
        }

        if n > 0:
            label_path = os.path.join(labels_dir, img_stem + '.txt')
            with open(label_path, 'w') as f:
                for box in r.boxes:
                    cls_id = int(box.cls[0])
                    conf = float(box.conf[0])
                    xywhn = box.xywhn[0].tolist()
                    x_center = xywhn[0]
                    y_center = xywhn[1]
                    width = xywhn[2]
                    height = xywhn[3]
                    f.write(f"{cls_id} {x_center:.6f} {y_center:.6f} {width:.6f} {height:.6f} {conf:.6f}\n")

                    cls_name = model_names.get(cls_id, str(cls_id))
                    class_counts[cls_name] = class_counts.get(cls_name, 0) + 1

                    x1, y1, x2, y2 = box.xyxy[0].tolist()
                    image_info['boxes'].append({
                        'class': cls_name,
                        'class_id': cls_id,
                        'confidence': round(conf, 4),
                        'bbox_xyxy': [round(v, 2) for v in [x1, y1, x2, y2]],
                        'bbox_xywhn': [round(v, 6) for v in xywhn]
                    })

        per_image_results.append(image_info)

    return total_detections, class_counts, per_image_results


def main():
    parser = argparse.ArgumentParser(description='YOLO Predict Script')
    parser.add_argument('--model', required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--name', default=None)
    parser.add_argument('--imgsz', type=int, default=640)
    parser.add_argument('--conf', type=float, default=0.25, help='confidence threshold')
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

    output_dir = os.path.join(project_dir, output_name)
    if os.path.exists(output_dir):
        print(f"[PREDICT] Cleaning existing output directory: {output_dir}")
        shutil.rmtree(output_dir)

    print(f"[PREDICT] Starting prediction on {len(all_images)} images...")

    predict_kwargs = dict(
        imgsz=args.imgsz,
        save=True,
        project=project_dir,
        name=output_name,
        exist_ok=True,
        conf=args.conf,
        iou=0.45,
    )

    results = model.predict(source=all_images, verbose=True, **predict_kwargs)

    print(f"[PREDICT] Prediction done, saving labels and summary...")

    labels_dir = os.path.join(output_dir, 'labels')
    total_detections, class_counts, per_image_results = save_labels_from_results(
        results, labels_dir, model.names
    )

    summary = {
        'total_images': len(all_images),
        'total_detections': total_detections,
        'class_counts': class_counts,
        'confidence_threshold': args.conf,
        'image_size': args.imgsz,
        'model_path': args.model,
        'model_classes': model.names,
        'per_image_results': per_image_results
    }

    summary_path = os.path.join(output_dir, 'detection_summary.json')
    with open(summary_path, 'w', encoding='utf-8') as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)

    print(f"[PREDICT] Total detections: {total_detections} in {len(all_images)} images")
    if class_counts:
        print(f"[PREDICT] Class breakdown:")
        for cls_name, count in sorted(class_counts.items(), key=lambda x: -x[1]):
            print(f"  {cls_name}: {count}")
    else:
        print("[WARNING] No objects detected!")

    label_files = [f for f in os.listdir(labels_dir) if f.endswith('.txt')] if os.path.exists(labels_dir) else []
    print(f"[PREDICT] Label files: {len(label_files)}")
    print(f"[PREDICT] Summary saved to: {summary_path}")
    print("PREDICTION_COMPLETE")


if __name__ == "__main__":
    main()
