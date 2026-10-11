"""ONNX Runtime against torch on the same inputs, and against the iOS parity reference.

Feeds each parity frame through the iOS geometry for the model (ECCV16: squash to 256, L of the
squashed gray; DDColor: fit to 384, reflect-101 pad, crop) and reports mean |Δab| between ORT and
torch, plus the mean a / b / chroma of the model stage next to reference.json's `4_model_*`.
"""

import json
import pathlib
import sys

import cv2
import numpy as np
import onnxruntime as ort
import torch
from PIL import Image

LAB = pathlib.Path.home() / "Documents/colorize-lab"
sys.path.insert(0, str(LAB))
import bench as B  # noqa: E402

OUT = pathlib.Path(sys.argv[1])
which = sys.argv[2]
PARITY = pathlib.Path(__file__).resolve().parents[2] / "app/src/test/resources/fixtures/ios-parity"
ref = json.load(open(PARITY / "reference.json"))


import colorizers  # noqa: E402  (bench put it on the path)


def eccv16_input(rgb):
    gray = B.clahe(B.to_gray(rgb), 1.5)
    _, tens_l = colorizers.preprocess_img(gray, HW=(256, 256))
    return tens_l.float()


def ddcolor_input(rgb):
    gray = B.clahe(B.to_gray(rgb), 1.0).astype(np.float32) / 255.0
    h, w = gray.shape[:2]
    s = 384 / max(h, w)
    nw, nh = round(w * s), round(h * s)
    small = cv2.resize(gray, (nw, nh), interpolation=cv2.INTER_AREA)
    padded = cv2.copyMakeBorder(small, 0, 384 - nh, 0, 384 - nw, cv2.BORDER_REFLECT_101)
    return torch.from_numpy(padded.transpose(2, 0, 1))[None].float(), (nh, nw)


sess = ort.InferenceSession(str(OUT / ("ECCV16.onnx" if which == "eccv16" else "DDColorLarge.onnx")),
                            providers=["CPUExecutionProvider"])
for cid in ("2209460", "2504212", "166360"):
    rgb = np.array(Image.open(PARITY / f"{cid}-input.png").convert("RGB"))
    with torch.inference_mode():
        if which == "eccv16":
            x = eccv16_input(rgb)
            m = B.get_model("eccv16", torch.device("cpu"))
            t = B.eccv16_decode(m, B.eccv16_logits(m, x), 0.3, 2.0, "mean", 0.0, 1.0).numpy()
            o = sess.run(["ab"], {"lightness": x.numpy(), "rebalance": np.array([2.0], np.float32)})[0]
            crop = (256, 256)
        else:
            x, crop = ddcolor_input(rgb)
            m = B.get_model("ddcolor-large", torch.device("cpu")).float()
            t = m(x).numpy()
            o = sess.run(["ab"], {"grayRGB": x.numpy()})[0]
    t, o = t[:, :, :crop[0], :crop[1]], o[:, :, :crop[0], :crop[1]]
    d = np.abs(t - o)
    chroma = np.hypot(o[0, 0], o[0, 1])
    r = ref["cases"][f"{cid}|{'eccv16' if which == 'eccv16' else 'ddcolor-large'}"]["stages"]
    print(f"{cid}: |Δab| mean {d.mean():.4f} max {d.max():.3f} | ort a {o[0,0].mean():.2f} b {o[0,1].mean():.2f} "
          f"chroma {chroma.mean():.2f} | ref a {r['4_model_ab_a']['mean']:.2f} b {r['4_model_ab_b']['mean']:.2f} "
          f"chroma {r['4_model_chroma']['mean']:.2f}")
