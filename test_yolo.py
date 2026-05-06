from ultralytics import YOLO
import argparse


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--site', type=str, required=True, help="yaml文件根目录")
    parser.add_argument('--imgsz', type=int, default=640, help="图像尺寸")
    args = parser.parse_args()

    site = args.site
    data_yaml = f"{site}/data.yaml"

    model_path = f"runs/detect/{site}_train/weights/best.pt"
    print(f"Using model: {model_path}")

    import glob
    model_files = glob.glob(model_path)
    if not model_files:
        print(f"没有找到{site}的训练模型！")
        return

    model = YOLO(model_files[0])
    results = model.val(
        data=data_yaml,
        split="test",
        imgsz=args.imgsz,
        batch=4,
        workers=1,
        name=f"{site}_test",
        device="cpu",
        plots=False,
        visualize=False,
        cache=False,
    )

    print(f"Site: {site}")
    print(f"mAP50: {results.box.map50:.3f}")
    print(f"mAP50-95: {results.box.map:.3f}")
    print(f"Precision: {results.box.mp:.3f}")
    print(f"Recall: {results.box.mr:.3f}")


if __name__ == "__main__":
    main()
