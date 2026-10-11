"""Exports the two colorization models Rewind ships to ONNX, for ONNX Runtime on Android.

The graph contracts mirror the iOS Core ML packages (see Rewind/Colorization/Models/*.swift):

  DDColorLarge.onnx  input  `grayRGB`   float32 (1, 3, 384, 384), gray replicated, 0...1
                     output `ab`        float32 (1, 2, 384, 384), Lab units
  ECCV16.onnx        input  `lightness` float32 (1, 1, 256, 256), Lab L 0...100
                     input  `rebalance` float32 (1)
                     output `ab`        float32 (1, 2, 256, 256), Lab units, the lab's decode
                                        (T 0.3, violet wedge masked, chroma^rebalance weighting)

DDColor's weights are stored fp16 with a Cast to fp32 in front of each, so the download is the
size of the iOS fp16 package while ONNX Runtime's CPU kernels compute in fp32 (it folds the casts
when the session is built).

Run with the colorize-lab venv and the onnx tooling on PYTHONPATH:
  PYTHONPATH=<pydeps> ~/Documents/colorize-lab/.venv/bin/python export_models.py <out dir>
"""

import pathlib
import sys

import numpy as np
import onnx
import torch
from onnx import helper, numpy_helper, TensorProto

LAB = pathlib.Path.home() / "Documents/colorize-lab"
sys.path.insert(0, str(LAB))
import bench as B  # noqa: E402

OUT = pathlib.Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
CPU = torch.device("cpu")
OPSET = 17


class ECCV16Decoded(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model
        # Constants of the weights (the bin centers are model_out's own kernel), computed once
        # here so the graph carries them as tensors rather than as hypot/atan2.
        with torch.no_grad():
            self.register_buffer("chroma", B.eccv16_bin_chroma(model).view(1, -1, 1, 1).clone())
            self.register_buffer("keep", B.eccv16_hue_mask(model).float().view(1, -1, 1, 1))

    def forward(self, lightness, rebalance):
        logits = B.eccv16_logits(self.model, lightness)
        p = torch.softmax(logits / 0.3, dim=1)
        p = p * self.keep
        p = p * torch.pow(self.chroma, rebalance.view(1, 1, 1, 1))
        p = p / p.sum(1, keepdim=True).clamp(min=1e-8)
        m = self.model
        return m.unnormalize_ab(m.upsample4(m.model_out(p)))


def strip_spectral_norm(model):
    for module in model.modules():
        try:
            torch.nn.utils.remove_spectral_norm(module)
        except ValueError:
            pass


def fp16_storage(path):
    """Stores every large float32 initializer as float16 behind a Cast back to float32."""
    model = onnx.load(str(path), load_external_data=True)
    graph = model.graph
    new_inits, casts = [], []
    for init in graph.initializer:
        arr = numpy_helper.to_array(init)
        if arr.dtype == np.float32 and arr.size >= 1024:
            half = numpy_helper.from_array(arr.astype(np.float16), init.name + "_fp16")
            new_inits.append(half)
            casts.append(helper.make_node("Cast", [half.name], [init.name], to=TensorProto.FLOAT))
        else:
            new_inits.append(init)
    del graph.initializer[:]
    graph.initializer.extend(new_inits)
    nodes = list(graph.node)
    del graph.node[:]
    graph.node.extend(casts + nodes)
    onnx.checker.check_model(model, full_check=False)
    onnx.save(model, str(path))


def export_ddcolor():
    net = B.get_model("ddcolor-large", CPU).float().eval()
    strip_spectral_norm(net)
    x = torch.rand(1, 3, 384, 384)
    path = OUT / "DDColorLarge.onnx"
    torch.onnx.export(net, (x,), str(path), input_names=["grayRGB"], output_names=["ab"],
                      opset_version=OPSET, dynamo=False, do_constant_folding=True)
    fp16_storage(path)
    return net


def export_eccv16():
    net = ECCV16Decoded(B.get_model("eccv16", CPU).float().eval()).eval()
    L = torch.rand(1, 1, 256, 256) * 100
    r = torch.tensor([2.0])
    path = OUT / "ECCV16.onnx"
    torch.onnx.export(net, (L, r), str(path), input_names=["lightness", "rebalance"],
                      output_names=["ab"], opset_version=OPSET, dynamo=False,
                      do_constant_folding=True)
    return net


if __name__ == "__main__":
    which = sys.argv[2:] or ["eccv16", "ddcolor"]
    if "eccv16" in which:
        export_eccv16()
        print("eccv16 done")
    if "ddcolor" in which:
        export_ddcolor()
        print("ddcolor done")
