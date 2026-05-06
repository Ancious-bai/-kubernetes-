import argparse
import os
import glob
import time
from pathlib import Path

import numpy as np
import cv2
import yaml
from PIL import Image
from tqdm import tqdm
import sys
import io

# 设置标准输出为 UTF-8 编码
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

def flat_field_correction(mei_img, blank_img):
    """
    mei, blank: 灰度图 (uint8 或 uint16)
    返回: uint8 灰度图 (0~255)
    """
    # 根据位深做归一化
    if mei_img.dtype == np.uint16 or blank_img.dtype == np.uint16:
        denom = 65535.0
    else:
        denom = 255.0

    mei = mei_img.astype(np.float32) / denom + 1e-6
    blank = blank_img.astype(np.float32) / denom + 1e-6

    # I_corr = -log(I / I0)
    corr = -np.log(mei / blank)

    # 归一化到 [0,1]
    corr -= corr.min()
    if corr.max() > 0:
        corr /= corr.max()

    return (corr * 255).astype(np.uint8)


def make_pseudo_rgb(low_corr, high_corr):
    """
    low_corr, high_corr: uint8 灰度
    返回: uint8 3 通道伪 RGB
    """
    low = low_corr.astype(np.float32) / 255.0
    high = high_corr.astype(np.float32) / 255.0
    mix = 0.5 * (low + high)

    rgb = np.stack(
        [
            (low * 255).astype(np.uint8),   # R: 低能
            (high * 255).astype(np.uint8),  # G: 高能
            (mix * 255).astype(np.uint8),   # B: 混合
        ],
        axis=-1,
    )
    return rgb


def gangue_mask_from_ratio(low_corr, high_corr, ratio_thresh):
    """
    基于 r = high / (low+eps) 的矸石掩膜
    返回 uint8 二值图: 0(煤) / 255(矸石)
    """
    low = low_corr.astype(np.float32) / 255.0
    high = high_corr.astype(np.float32) / 255.0

    r = high / (low + 1e-6)
    mask = (r > ratio_thresh).astype(np.uint8) * 255
    return mask.astype(np.uint8)


def clean_mask(mask: np.ndarray, min_area: int, kernel_size: int) -> np.ndarray:
    """
    对二值掩膜做简单形态学后处理:
    1. 开运算去掉孤立小噪声
    2. 连接域面积过滤, 只保留面积 >= min_area 的连通块
    mask: uint8, 0/255
    """
    if mask.dtype != np.uint8:
        mask = mask.astype(np.uint8)

    # 先转成 0/1
    bin_mask = (mask > 0).astype(np.uint8) * 255

    # 形态学开运算 (先腐蚀再膨胀) 清除孤立噪声
    kernel = np.ones((kernel_size, kernel_size), np.uint8)
    opened = cv2.morphologyEx(bin_mask, cv2.MORPH_OPEN, kernel)

    # 连通域分析, 去掉面积太小的块
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(opened, connectivity=8)
    # stats: [label, x, y, w, h, area]
    out = np.zeros_like(opened)
    for label in range(1, num_labels):  # 0 是背景
        area = stats[label, cv2.CC_STAT_AREA]
        if area >= min_area:
            out[labels == label] = 255

    return out


def read_image_gray_anydepth(path: str):
    """
    使用 Pillow 读图，兼容 8/16 位灰度 PNG，再转成 numpy 数组。
    """
    img = Image.open(path)
    # 转灰度/单通道，但保持位深
    if img.mode not in ("L", "I;16", "I"):
        # 如果是 RGB 等，先转灰度
        img = img.convert("L")
    arr = np.array(img)
    return arr


def process_single_sample(
    sample_dir: str,
    out_root: str,
    ratio_thresh: float,
    min_area: int,
    kernel_size: int,
    start_idx: int
):
    out_root = Path(out_root)

    # 读取空场第一张 High / Low
    blank_high_files = sorted(glob.glob(str(sample_dir / "blank" / "High" / "*")))
    blank_low_files = sorted(glob.glob(str(sample_dir / "blank" / "Low" / "*")))
    if not blank_high_files or not blank_low_files:
        print(f"[WARN] {sample_dir} 空场图缺失，跳过该目录")
        return

    # 使用 Pillow 以兼容 16 位 PNG
    try:
        blank_high = read_image_gray_anydepth(blank_high_files[0])
        blank_low = read_image_gray_anydepth(blank_low_files[0])
    except Exception as e:
        print(f"[WARN] {sample_dir} 空场图读取异常: {e}，跳过该目录")
        return

    if blank_high is None or blank_low is None:
        print(f"[WARN] {sample_dir} 空场图读取失败，跳过该目录")
        return start_idx

    mei_high_files = sorted(glob.glob(str(sample_dir / "mei" / "High" / "*")))
    mei_low_files = sorted(glob.glob(str(sample_dir / "mei" / "Low" / "*")))

    if len(mei_high_files) == 0 or len(mei_low_files) == 0:
        print(f"[WARN] {sample_dir} mei/high 或 mei/low 为空，跳过该目录")
        return start_idx

    if len(mei_high_files) != len(mei_low_files):
        print(
            f"[WARN] {sample_dir} high({len(mei_high_files)}) / low({len(mei_low_files)}) 数量不一致，按最小值截断"
        )
    n = min(len(mei_high_files), len(mei_low_files))

    sample_name = sample_dir.name

    for idx in range(n):
        #调整输出目录
        start_idx += 1
        remainder = start_idx % 5
        if remainder < 3:
            split = "train"
        elif remainder == 3:
            split = "val"
        else:
            split = "test"
        img_out_dir = out_root / "images" / split
        img_out_dir.mkdir(parents=True, exist_ok=True)

        try:
            mei_high = read_image_gray_anydepth(mei_high_files[idx])
            mei_low = read_image_gray_anydepth(mei_low_files[idx])
        except Exception as e:
            print(f"[WARN] {sample_dir} 第 {idx} 张 mei 图读取异常: {e}，跳过这一张")
            continue

        if mei_high is None or mei_low is None:
            print(f"[WARN] {sample_dir} 第 {idx} 张 mei 图读取失败，跳过这一张")
            continue

        # 空场校正
        high_corr = flat_field_correction(mei_high, blank_high)
        low_corr = flat_field_correction(mei_low, blank_low)

        # 伪彩色图
        rgb = make_pseudo_rgb(low_corr, high_corr)

        # 矸石掩膜 (原始比值阈值 -> 形态学清理)
        raw_mask = gangue_mask_from_ratio(low_corr, high_corr, ratio_thresh)
        mask = clean_mask(raw_mask, min_area, kernel_size)

        # 输出文件名基干：sampleName_00000
        base_name = f"{sample_name}_{idx:05d}"

        rgb_path = img_out_dir / f"{base_name}_rgb.png"
        low_path = img_out_dir / f"{base_name}_low.png"
        mask_path = img_out_dir / f"{base_name}_mask.png"

        # 使用 Pillow 保存，避免 OpenCV 在中文路径下写入失败的问题
        Image.fromarray(rgb).save(str(rgb_path))
        Image.fromarray(low_corr).save(str(low_path))
        Image.fromarray(mask).save(str(mask_path))
    return start_idx


def process_dataset(
    data_root: str,
    out_root:str,
    ratio_thresh: float,
    min_area: int,
    kernel_size: int
):

    data_root = Path(data_root)
    samples = sorted([p for p in data_root.iterdir() if p.is_dir()])
    count=0
    for d in tqdm(samples, desc="\n🔄 处理数据中"):
        # 处理单个样本，并更新全局计数器
        count = process_single_sample(
            d,
            out_root,
            ratio_thresh,
            min_area,
            kernel_size,
            count
        )

    print(f"\n🎉 数据处理完成！共处理图片{count}张")

def generate_yaml(data_root: Path):
    """
    生成 YOLO 格式的 YAML 配置文件
    :param data_root: 数据集根目录（包含 images, labels）
    """
    data_root.mkdir(parents=True, exist_ok=True)
    output_path = data_root / "data.yaml"

    # 构建 YAML 数据
    yaml_data = {
        "path": "./"+str(os.path.basename(data_root)),  # 相对路径（如 "./data_processed"）
        "train": "images/train",  # 训练集图像路径（相对于 path）
        "val": "images/val",      # 验证集图像路径
        "test": "images/test",    # 测试集图像路径
        "nc": 1,                  # 类别数量
        "names": ["gangue"]      # 类别名称
    }

    # 写入 YAML 文件
    with open(output_path, "w", encoding="utf-8") as f:
        yaml.dump(yaml_data, f, allow_unicode=True, default_flow_style=False)

def generate_k8s_job_yaml(data_name: str, job_type: str, output_dir: str = "k8s_jobs", epochs: int = 2, imgsz: int = 640):
    """
    生成 Kubernetes Job YAML 配置文件

    :param data_name: 数据集名称（如 data1, data2）
    :param job_type: 任务类型（train 或 test）
    :param output_dir: 输出目录
    :param epochs: 训练轮数（仅train类型有效）
    :param imgsz: 图像尺寸
    """
    command = ["python3", f"{job_type}_yolo.py", "--site", f"{data_name}_processed"]
    if job_type == "train":
        command.extend(["--epochs", str(epochs), "--imgsz", str(imgsz)])
    elif job_type == "test":
        command.extend(["--imgsz", str(imgsz)])

    job_config = {
        "apiVersion": "batch/v1",
        "kind": "Job",
        "metadata": {
            "name": f"{data_name}-{job_type}-job",
            "labels": {
                "site": data_name,
                "type": job_type
            }
        },
        "spec": {
            "parallelism": 1,
            "completions": 1,
            "template": {
                "spec": {
                    "containers": [
                        {
                            "name": "yolo-container",
                            "image": "yolov8-project:latest",
                            "imagePullPolicy": "IfNotPresent",
                            "command": command,
                            "env": [
                                {
                                    "name": "PYTHONUNBUFFERED",
                                    "value": "1"
                                }
                            ],
                            "volumeMounts": [
                                {
                                    "name": "app-volume",
                                    "mountPath": "/app"
                                }
                            ]
                        }
                    ],
                    "restartPolicy": "Never",
                    "volumes": [
                        {
                            "name": "app-volume",
                            "hostPath": {
                                "path": "/mnt/host/e/Ancious/Desktop/毕业设计/Code",
                                "type": "Directory"
                            }
                        }
                    ]
                }
            },
            "backoffLimit": 4
        }
    }

    # 确保输出目录存在
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    # 生成文件名
    yaml_filename = f"{data_name}-{job_type}.yaml"
    yaml_path = output_path / yaml_filename

    # 写入 YAML 文件
    with open(yaml_path, "w", encoding="utf-8") as f:
        yaml.dump(job_config, f, allow_unicode=True, default_flow_style=False, sort_keys=False)

    return yaml_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="煤炭矸石数据集处理脚本")
    parser.add_argument(
        "--input_dir",
        type=str,
        required=True,
        help="要处理的数据集文件夹路径"
    )
    parser.add_argument("--epochs", type=int, default=2, help="训练轮数")
    parser.add_argument("--imgsz", type=int, default=640, help="图像尺寸")
    args = parser.parse_args()
    data_root = os.path.abspath(args.input_dir)
    if not os.path.exists(data_root):
        print(f"[ERROR] 输入路径不存在: {data_root}")
        exit(1)

    project_root = os.path.dirname(os.path.abspath(__file__))
    data_name = os.path.basename(data_root)
    out_root = os.path.join(project_root, f"{data_name}_processed")

    print(f"输入数据路径: {data_root}")
    print(f"输出结果路径: {out_root}")
    generate_yaml(Path(out_root))

    process_dataset(
        data_root=data_root,
        out_root=out_root,
        ratio_thresh=1.05,
        min_area=30,
        kernel_size=2
    )
    time.sleep(0.1)

    import mask_yolo

    for split in ["train", "val", "test"]:
        # frac_thresh: rock 内高比值像素占比门限, 可按需要微调
        mask_yolo.generate_labels_for_split(Path(out_root), split, frac_thresh=0.01)
    print("\n🎉 数据标记完成！")

    # 生成 Kubernetes Job YAML 文件
    output_dir = "k8s_jobs"
    generate_k8s_job_yaml(data_name, "train", output_dir, epochs=args.epochs, imgsz=args.imgsz)
    generate_k8s_job_yaml(data_name, "test", output_dir, imgsz=args.imgsz)
    print(f"\n🎉 所有配置文件已生成！使用命令部署:")
    print(f"  kubectl apply -f {project_root}/{output_dir}/{data_name}-train.yaml")
    print(f"  kubectl apply -f {project_root}/{output_dir}/{data_name}-test.yaml")