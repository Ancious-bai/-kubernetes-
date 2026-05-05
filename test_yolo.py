from ultralytics import YOLO
import argparse


def main():
    # 解析命令行参数
    parser = argparse.ArgumentParser()
    parser.add_argument(
        '--site',
        type=str,
        required=True,
        help="yaml文件根目录"
    )
    args = parser.parse_args()

    site = args.site
    data_yaml = f"{site}/data.yaml"

    model_path = f"runs/detect/{site}_train/weights/best.pt"
    print(f"Using model: {model_path}")

    # 尝试加载模型（使用第一个匹配的模型）
    import glob
    model_files = glob.glob(model_path)
    if not model_files:
        print(f"没有找到{site}的训练模型！")
        return

    model = YOLO(model_files[0])
    results = model.val(
        data=data_yaml,  # 数据集配置文件
        split="test",  # 指定测试集
        imgsz=640,
        batch=4,
        workers=1,
        name=f"{site}_test",
        device="cpu",
        plots=False,  # 跳过图表生成，避免下载字体
        visualize=False,  # 禁用可视化
        cache=False,  # 禁用缓存
    )

    print(f"Site: {site}")
    print(f"mAP50: {results.box.map50:.3f}")
    print(f"mAP50-95: {results.box.map:.3f}")
    print(f"Precision: {results.box.mp:.3f}")  # 使用 mp 而不是 precision
    print(f"Recall: {results.box.mr:.3f}")  # 使用 mr 而不是 recall


if __name__ == "__main__":
    main()