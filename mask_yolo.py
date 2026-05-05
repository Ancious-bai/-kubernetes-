import os
from pathlib import Path

import cv2
import numpy as np
from PIL import Image
from tqdm import tqdm


def load_mask(path: str) -> np.ndarray:
    """
    读取 *_mask.png 为二值图 (0/255)
    """
    img = Image.open(path).convert("L")
    arr = np.array(img)
    bin_mask = (arr > 0).astype(np.uint8) * 255
    return bin_mask


def segment_rocks_from_low(low_path: str, rock_thresh: int, min_area: int) -> np.ndarray:
    """
    从 *_low.png 中分割出整块煤/石头:
    - 低能校正图中, 煤/石头是明显的黑块, 背景是白色
    - 这里简单用一个亮度阈值 + 连通域面积过滤
    返回: uint8 掩膜, 0/255, 255 表示属于某一块 rock
    """
    img = Image.open(low_path).convert("L")
    arr = np.array(img).astype(np.uint8)

    # 背景接近白色(高值), 物料是黑色(低值), 所以用反阈值
    # rock_thresh 可以根据图像直方图微调, 200 表示只保留较暗区域
    rock_mask = (arr < rock_thresh).astype(np.uint8) * 255

    # 去掉特别小的噪声连通块
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(rock_mask, connectivity=8)
    clean = np.zeros_like(rock_mask)
    for label in range(1, num_labels):
        area = stats[label, cv2.CC_STAT_AREA]
        if area >= min_area:
            clean[labels == label] = 255
    return clean


def gangue_rocks_from_masks(
    rock_mask: np.ndarray,
    gangue_pixel_mask: np.ndarray,
    frac_thresh: float,
    min_rock_area: int,
):
    """
    根据:
    - rock_mask: 整块煤/石头区域
    - gangue_pixel_mask: r>阈值 的高能比值像素(我们已有的 *_mask.png)
    判定哪些整块 rock 是 "含矸煤块":
    - 在 rock 内, gangue_pixel 所占比例 >= frac_thresh 即认为该 rock 含矸
    返回: [ (x_center, y_center, w, h) ... ], 像素坐标
    """
    h, w = rock_mask.shape

    # 确保都是二值 0/255
    rock_mask = (rock_mask > 0).astype(np.uint8) * 255
    gangue_pixel_mask = (gangue_pixel_mask > 0).astype(np.uint8) * 255

    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(rock_mask, connectivity=8)
    boxes = []
    for label in range(1, num_labels):
        x, y, bw, bh, area = stats[label]
        if area < min_rock_area:
            continue

        # 该 rock 内的像素
        rock_region = (labels == label)
        rock_area = rock_region.sum()
        if rock_area == 0:
            continue

        # rock 内的高比值像素
        gangue_pixels = (gangue_pixel_mask.astype(bool) & rock_region).sum()
        frac = gangue_pixels / float(rock_area)

        if frac >= frac_thresh:
            xc = x + bw / 2.0
            yc = y + bh / 2.0
            boxes.append((xc, yc, bw, bh))

    return boxes, (w, h)


def generate_labels_for_split(data_root: Path, split: str, frac_thresh: float):
    """
    根据 images/{split} 下的:
    - *_low.png: 分割出整块煤/石头 (rock)
    - *_mask.png: 高能比值像素(疑似矸石)
    自动生成 "含矸煤块" 的 YOLO 矩形框标签到 labels/{split}
    """
    img_dir = data_root / "images" / split
    label_dir = data_root / "labels" / split
    label_dir.mkdir(parents=True, exist_ok=True)

    rgb_files = sorted(img_dir.glob("*_rgb.png"))

    for rgb_path in tqdm(rgb_files, desc=f"\n🔄 正在标记{split}样本集"):
        label_path = label_dir / (rgb_path.stem + ".txt")

        # 对应的 low 和 gangue 像素 mask
        low_path = rgb_path.with_name(rgb_path.name.replace("_rgb.png", "_low.png"))
        gangue_mask_path = rgb_path.with_name(rgb_path.name.replace("_rgb.png", "_mask.png"))

        if (not low_path.exists()) or (not gangue_mask_path.exists()):
            # 缺少必要信息, 写空标签
            label_path.write_text("")
            continue

        rock_mask = segment_rocks_from_low(str(low_path), rock_thresh=200, min_area=50)
        gangue_pixel_mask = load_mask(str(gangue_mask_path))

        boxes, (w, h) = gangue_rocks_from_masks(
            rock_mask,
            gangue_pixel_mask,
            frac_thresh=frac_thresh,
            min_rock_area=50,
        )

        lines = []
        for (xc, yc, bw, bh) in boxes:
            # 归一化到 0~1
            x_rel = xc / w
            y_rel = yc / h
            w_rel = bw / w
            h_rel = bh / h
            cls_id = 0  # 只有一个类: gangue rock
            lines.append(f"{cls_id} {x_rel:.6f} {y_rel:.6f} {w_rel:.6f} {h_rel:.6f}")

        label_path.write_text("\n".join(lines))
