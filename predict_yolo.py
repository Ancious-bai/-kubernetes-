#!/usr/bin/env python3
import os, shutil, sys, glob, json, io


def setup_env():
    YOLO_DIR = '/tmp/Ultralytics'
    os.environ['YOLO_CONFIG_DIR'] = YOLO_DIR
    os.environ['ULTRALYTICS_DISABLE_AUTOUPDATE'] = '1'
    os.makedirs(YOLO_DIR, exist_ok=True)
    FONT_PATH = os.path.join(YOLO_DIR, 'Arial.ttf')
    if not os.path.exists(FONT_PATH) or os.path.getsize(FONT_PATH) == 0:
        for src in ['/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
                    '/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',
                    '/usr/share/fonts/TTF/DejaVuSans.ttf']:
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
import numpy as np
from PIL import Image


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
    parser.add_argument('--conf', type=float, default=0.05)
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
        sys.exit(1)

    output_name = args.name or (os.path.basename(model_path).replace('.pt', '') + "_predict")
    print(f"[PREDICT] Output name: {output_name}")
    print(f"[PREDICT] Confidence threshold: {args.conf}")

    model = YOLO(model_path)
    print(f"[PREDICT] Model classes: {model.names}")

    output_dir = os.path.join(project_dir, output_name)
    if os.path.exists(output_dir):
        shutil.rmtree(output_dir)
    os.makedirs(output_dir, exist_ok=True)

    print(f"[PREDICT] Starting prediction on {len(all_images)} images...")

    results = model.predict(
        source=all_images,
        imgsz=args.imgsz,
        conf=args.conf,
        iou=0.45,
        save=False,
        verbose=True
    )

    print(f"[PREDICT] Prediction done ({len(results)} results). Processing...")

    labels_dir = os.path.join(output_dir, 'labels')
    os.makedirs(labels_dir, exist_ok=True)
    annotated_dir = os.path.join(output_dir, 'annotated')
    os.makedirs(annotated_dir, exist_ok=True)

    total_detections = 0
    class_counts = {}
    per_image_results = []
    plotted_images = []

    for idx, r in enumerate(results):
        img_path = getattr(r, 'path', '')
        original_name = os.path.basename(img_path) if img_path else f'unknown_{idx}.jpg'

        rel_path = os.path.relpath(img_path, images_dir) if img_path and images_dir else original_name

        n = len(r.boxes) if r.boxes is not None else 0
        total_detections += n

        image_info = {
            'originalName': original_name,
            'relativePath': rel_path,
            'detections': n,
            'boxes': []
        }

        if n > 0:
            label_name = original_name.rsplit('.', 1)[0] + '.txt'
            label_path = os.path.join(labels_dir, label_name)
            with open(label_path, 'w') as f:
                for box in r.boxes:
                    cls_id = int(box.cls[0])
                    conf = float(box.conf[0])
                    xywhn = box.xywhn[0].tolist()
                    f.write(f"{cls_id} {xywhn[0]:.6f} {xywhn[1]:.6f} {xywhn[2]:.6f} {xywhn[3]:.6f} {conf:.6f}\n")

                    cls_name = model.names.get(cls_id, str(cls_id))
                    class_counts[cls_name] = class_counts.get(cls_name, 0) + 1

                    x1, y1, x2, y2 = box.xyxy[0].tolist()
                    image_info['boxes'].append({
                        'class': cls_name,
                        'classId': cls_id,
                        'confidence': round(conf, 4),
                        'bboxXyxy': [round(v, 2) for v in [x1, y1, x2, y2]],
                        'bboxXywhn': [round(v, 6) for v in xywhn]
                    })

        per_image_results.append(image_info)

        try:
            plotted_bgr = r.plot()
            if plotted_bgr is not None:
                plotted_rgb = plotted_bgr[..., ::-1].copy()
                pil_img = Image.fromarray(plotted_rgb)

                annotated_rel = os.path.splitext(rel_path)[0] + '.jpg'
                annotated_path = os.path.join(annotated_dir, annotated_rel)
                os.makedirs(os.path.dirname(annotated_path), exist_ok=True)
                pil_img.save(annotated_path, quality=95)

                plotted_images.append(pil_img)
        except Exception as e:
            print(f"[WARNING] Failed to plot {original_name}: {e}")

    print(f"[PREDICT] Saved {len(plotted_images)} annotated images")

    if plotted_images:
        try:
            cols = min(5, max(2, len(plotted_images)))
            rows = (len(plotted_images) + cols - 1) // cols
            thumb_w, thumb_h = 320, 320

            resized = []
            for img in plotted_images:
                resized.append(img.resize((thumb_w, thumb_h), Image.LANCZOS))

            grid_w = thumb_w * cols + (cols - 1) * 2
            grid_h = thumb_h * rows + (rows - 1) * 2
            grid_img = Image.new('RGB', (grid_w, grid_h), color=(255, 255, 255))

            for i, img in enumerate(resized):
                r_idx = i // cols
                c_idx = i % cols
                x = c_idx * (thumb_w + 2)
                y = r_idx * (thumb_h + 2)
                grid_img.paste(img, (x, y))

            grid_path = os.path.join(output_dir, 'prediction_grid.jpg')
            grid_img.save(grid_path, quality=90)
            print(f"[PREDICT] Grid saved: {grid_path} ({cols}x{rows})")
        except Exception as e:
            print(f"[WARNING] Grid creation failed: {e}")

    summary = {
        'totalImages': len(all_images),
        'totalDetections': total_detections,
        'imagesWithDetections': sum(1 for p in per_image_results if p['detections'] > 0),
        'imagesWithoutDetections': sum(1 for p in per_image_results if p['detections'] == 0),
        'confidenceThreshold': args.conf,
        'imageSize': args.imgsz,
        'classCounts': class_counts,
        'modelClasses': model.names,
        'perImageResults': per_image_results
    }

    summary_path = os.path.join(output_dir, 'detection_summary.json')
    with open(summary_path, 'w', encoding='utf-8') as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)

    print(f"\n[PREDICT] === RESULT SUMMARY ===")
    print(f"  Total images:     {len(all_images)}")
    print(f"  Total detections: {total_detections}")
    if class_counts:
        for cls_name, count in sorted(class_counts.items(), key=lambda x: -x[1]):
            print(f"    {cls_name}: {count}")
    else:
        print("  No objects detected!")
    print(f"  Confidence:       {args.conf}")
    print(f"  Annotated images: {len(plotted_images)}")
    print("PREDICTION_COMPLETE")


if __name__ == "__main__":
    main()
