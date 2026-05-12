#!/usr/bin/env python3
import os, shutil, sys, glob, json


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
from PIL import Image


def collect_all_images(source_dir):
    extensions = ('*.jpg', '*.jpeg', '*.png', '*.bmp', '*.webp', '*.JPG', '*.JPEG', '*.PNG')
    results = []
    for ext in extensions:
        results.extend(glob.glob(os.path.join(source_dir, '**', ext), recursive=True))
    return sorted(set(results))


def save_labels_and_summary(results, output_dir, labels_dir, model_names, all_image_paths, conf_thresh, imgsz):
    os.makedirs(labels_dir, exist_ok=True)
    total_detections = 0
    class_counts = {}
    per_image_results = []

    for idx, r in enumerate(results):
        img_path = getattr(r, 'path', '')
        original_name = os.path.basename(img_path) if img_path else f'unknown_{idx}'
        n = len(r.boxes) if r.boxes is not None else 0
        total_detections += n

        image_info = {
            'originalName': original_name,
            'detections': n,
            'boxes': []
        }

        if n > 0:
            label_path = os.path.join(labels_dir, original_name.rsplit('.', 1)[0] + '.txt')
            with open(label_path, 'w') as f:
                for box in r.boxes:
                    cls_id = int(box.cls[0])
                    conf = float(box.conf[0])
                    xywhn = box.xywhn[0].tolist()
                    f.write(f"{cls_id} {xywhn[0]:.6f} {xywhn[1]:.6f} {xywhn[2]:.6f} {xywhn[3]:.6f} {conf:.6f}\n")

                    cls_name = model_names.get(cls_id, str(cls_id))
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

    summary = {
        'totalImages': len(all_image_paths),
        'totalDetections': total_detections,
        'imagesWithDetections': sum(1 for p in per_image_results if p['detections'] > 0),
        'imagesWithoutDetections': sum(1 for p in per_image_results if p['detections'] == 0),
        'confidenceThreshold': conf_thresh,
        'imageSize': imgsz,
        'classCounts': class_counts,
        'modelClasses': model_names,
        'perImageResults': per_image_results
    }

    summary_path = os.path.join(output_dir, 'detection_summary.json')
    with open(summary_path, 'w', encoding='utf-8') as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)

    return total_detections, class_counts


def save_annotated_images(results, output_dir):
    annotated_dir = os.path.join(output_dir, 'annotated')
    os.makedirs(annotated_dir, exist_ok=True)

    saved_files = []
    for idx, r in enumerate(results):
        img_path = getattr(r, 'path', '')
        original_name = os.path.basename(img_path) if img_path else f'unknown_{idx}.jpg'

        try:
            plotted = r.plot()
            if plotted is not None:
                img_array = plotted
                from numpy import ndarray
                if isinstance(img_array, ndarray):
                    pil_img = Image.fromarray(img_array)
                else:
                    pil_img = Image.open(io.BytesIO(img_array))

                safe_name = "".join(c if c.isalnum() or c in '-_.()' else '_' for c in original_name)
                out_path = os.path.join(annotated_dir, safe_name)
                pil_img.save(out_path)
                saved_files.append({'savedName': safe_name, 'originalName': original_name})
        except Exception as e:
            print(f"[WARNING] Failed to plot image {original_name}: {e}")

    return saved_files


def create_prediction_grid(results, output_dir, cols=4):
    try:
        from numpy import array, zeros_like, uint8
        from PIL import Image as PILImage

        plotted_images = []
        names = []

        for r in results:
            img_path = getattr(r, 'path', '')
            name = os.path.basename(img_path) if img_path else '?'

            try:
                plotted = r.plot()
                if plotted is not None:
                    if isinstance(plotted, array):
                        img = PILImage.fromarray(plotted)
                    else:
                        img = PILImage.open(io.BytesIO(plotted))
                    plotted_images.append(img)
                    names.append(name)
            except Exception:
                pass

        if not plotted_images:
            return None

        n = len(plotted_images)
        rows = (n + cols - 1) // cols

        w, h = plotted_images[0].size
        grid_w = w * cols + (cols - 1) * 4
        grid_h = h * rows + (rows - 1) * 4

        grid_img = PILImage.new('RGB', (grid_w, grid_h), color=(255, 255, 255))

        for idx, (img, name) in enumerate(zip(plotted_images, names)):
            row_idx = idx // cols
            col_idx = idx % cols
            x = col_idx * (w + 4)
            y = row_idx * (h + 4)
            grid_img.paste(img, (x, y))

            short_name = name[:30] + ('...' if len(name) > 30 else '')

        grid_path = os.path.join(output_dir, 'prediction_grid.jpg')
        grid_img.save(grid_path, quality=95)
        print(f"[PREDICT] Grid saved: {grid_path} ({cols}x{rows}, {n} images)")
        return grid_path

    except ImportError:
        print("[WARNING] Cannot create grid: PIL/numpy not available")
        return None
    except Exception as e:
        print(f"[WARNING] Grid creation failed: {e}")
        return None


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
    os.makedirs(output_dir, exist_ok=True)

    print(f"[PREDICT] Starting prediction on {len(all_images)} images...")

    predict_kwargs = dict(
        imgsz=args.imgsz,
        save=False,
        project=project_dir,
        name=output_name,
        exist_ok=True,
        conf=args.conf,
        iou=0.45,
        verbose=True
    )

    results = model.predict(source=all_images, **predict_kwargs)

    print(f"[PREDICT] Prediction done ({len(results)} results). Plotting annotations...")

    labels_dir = os.path.join(output_dir, 'labels')

    total_detections, class_counts = save_labels_and_summary(
        results, output_dir, labels_dir, model.names, all_images, args.conf, args.imgsz
    )

    print(f"[PREDICT] Saving annotated images...")
    saved_files = save_annotated_images(results, output_dir)
    print(f"[PREDICT] Saved {len(saved_files)} annotated images to annotated/")

    print(f"[PREDICT] Creating prediction grid...")
    grid_path = create_prediction_grid(results, output_dir, cols=min(5, max(2, len(all_images))))

    print(f"\n[PREDICT] === RESULT SUMMARY ===")
    print(f"  Total images:     {len(all_images)}")
    print(f"  Total detections: {total_detections}")
    if class_counts:
        print(f"  Class breakdown:")
        for cls_name, count in sorted(class_counts.items(), key=lambda x: -x[1]):
            print(f"    {cls_name}: {count}")
    else:
        print("  No objects detected!")
    print(f"  Confidence:       {args.conf}")
    print(f"  Annotated images: {len(saved_files)}")
    if grid_path:
        print(f"  Grid image:       {grid_path}")
    print("PREDICTION_COMPLETE")


if __name__ == "__main__":
    main()
