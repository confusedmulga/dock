"""
Converts ONNX models to ORT format for Dock's minimal ONNX Runtime build, and writes the list
of operators that build must include (required_operators.config).

Constant folding is left out so weights stored as float16 by fp16_weights.py stay float16:
their Casts remain in the graph and run with the model (a minimal build has no optimizer).
They cost little next to the convolutions. Optimization stops at "extended": "all" would add
layout changes for the machine running this script.

    python to_ort.py <folder of .onnx models> <output folder>

Needs the onnxruntime Python package of the same version as the Android build (1.31.0).
"""
import glob
import os
import pathlib
import sys

import onnxruntime as ort
from onnxruntime.tools.ort_format_model import utils


def main(src_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    outputs = []
    for src in sorted(glob.glob(os.path.join(src_dir, "*.onnx"))):
        out = os.path.join(out_dir, os.path.basename(src)[:-5] + ".ort")
        options = ort.SessionOptions()
        options.log_severity_level = 3
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
        options.optimized_model_filepath = out
        options.add_session_config_entry("session.save_model_format", "ORT")
        ort.InferenceSession(src, options, providers=["CPUExecutionProvider"], disabled_optimizers=["ConstantFolding"])
        print(f"{os.path.basename(src)}: {os.path.getsize(src)} -> {os.path.getsize(out)} bytes")
        outputs.append(pathlib.Path(out))
    utils.create_config_from_models(outputs, pathlib.Path(out_dir, "required_operators.config"), enable_type_reduction=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
