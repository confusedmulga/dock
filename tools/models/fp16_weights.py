"""
Stores an ONNX model's float32 weights as float16, each followed by a Cast back to float32.

The model still computes in float32; only the file is half the size. Accuracy is unchanged
(measured: same OCR errors, corner model outputs within 0.002). See README.md for the commands.

    python fp16_weights.py <model.onnx> <output.onnx>

Needs numpy and onnx.
"""
import os
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

# Small tensors (scales, shapes) stay as they are.
MIN_ELEMENTS = 16


def to_half(tensor, name, casts, initializers):
    half = numpy_helper.from_array(numpy_helper.to_array(tensor).astype(np.float16), name + "__fp16")
    initializers.append(half)
    casts.append(helper.make_node("Cast", [half.name], [name], to=TensorProto.FLOAT, name=name + "__cast"))


def is_weight(tensor):
    return tensor.data_type == TensorProto.FLOAT and int(np.prod(tensor.dims or [1])) >= MIN_ELEMENTS


def main(src, dst):
    model = onnx.load(src)
    graph = model.graph
    casts, initializers, nodes = [], [], []
    for init in graph.initializer:
        if is_weight(init):
            to_half(init, init.name, casts, initializers)
        else:
            initializers.append(init)
    # Paddle2ONNX stores some models' weights as Constant nodes instead of initializers. They
    # all become initializers (ORT format conversion loses Constant nodes it isn't allowed to fold);
    # unused ones are dropped, which old (IR 3) models need.
    used = {name for node in graph.node for name in node.input} | {out.name for out in graph.output}
    for node in graph.node:
        value = next((a for a in node.attribute if a.name == "value"), None) if node.op_type == "Constant" else None
        if value is None:
            nodes.append(node)
        elif node.output[0] not in used:
            continue
        elif is_weight(value.t):
            to_half(value.t, node.output[0], casts, initializers)
        else:
            tensor = onnx.TensorProto()
            tensor.CopyFrom(value.t)
            tensor.name = node.output[0]
            initializers.append(tensor)
    del graph.initializer[:]
    graph.initializer.extend(initializers)
    del graph.node[:]
    graph.node.extend(casts + nodes)
    onnx.save(model, dst)
    print(f"{src}: {os.path.getsize(src)} -> {os.path.getsize(dst)} bytes, {len(casts)} weights")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
