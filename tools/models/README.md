# Dock's models

Three small models ship in the app, all run on the phone by Dock's minimal ONNX Runtime build
([third_party/onnxruntime](../../third_party/onnxruntime)). All three are under the Apache
License 2.0; their license texts ship next to them and in the app's About screen.

| Shipped file | What it does | Upstream (license) | Upstream file (SHA-256) |
|---|---|---|---|
| `core/ocr/src/main/assets/ocr/det.ort` | Finds lines of text | [PaddlePaddle/PP-OCRv5_mobile_det_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx) @ `e6f4fa8` (Apache-2.0, © 2016 PaddlePaddle Authors) | `inference.onnx` `a4319856…b6e61d` |
| `core/ocr/src/main/assets/ocr/rec.ort` | Reads Latin-script text | [PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx](https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx) @ `89d3a50` (Apache-2.0, © 2016 PaddlePaddle Authors) | `inference.onnx` `78881130…1498` |
| `core/ocr/src/main/assets/ocr/rec_dict.txt` | The recognizer's characters | `character_dict` in the same repo's `inference.yml` | |
| `core/scanner/src/main/assets/scanner/corners.ort` | Finds a page's four corners | [DocsaidLab/DocAligner](https://github.com/DocsaidLab/DocAligner), heatmap model `lcnet100_h_e_bifpn_256_fp32.onnx` (Apache-2.0, © DocsaidLab) | `f4117b78…9594e` |

DocAligner's repository is Apache-2.0; its authors confirmed in
[issue #13](https://github.com/DocsaidLab/DocAligner/issues/13) (October 2026) that the
pretrained weights are under the same terms and may be bundled in apps. The weights are
downloaded from the Google Drive link in the project's own code
(`docaligner/heatmap_reg/infer.py`).

## How the shipped files are made

Dock changes the models, as the Apache License asks to state: their weights are stored at half
precision and they're converted to ONNX Runtime's own format. Nothing is retrained.

```bash
python fp16_weights.py PP-OCRv5_mobile_det_onnx/inference.onnx onnx/det.onnx
python fp16_weights.py latin_PP-OCRv5_mobile_rec_onnx/inference.onnx onnx/rec.onnx
python fp16_weights.py lcnet100_h_e_bifpn_256_fp32.onnx onnx/corners.onnx
python to_ort.py onnx ort        # also writes ort/required_operators.config
```

Needs Python with `numpy`, `onnx` and `onnxruntime==1.31.0` (the same version as the Android
build). `required_operators.config` goes to `third_party/onnxruntime` and decides what the
minimal build includes; rebuild it there whenever a model changes.

Measured on desktop CPU with ONNX Runtime 1.31 (as the minimal build runs them):

| Model | Upstream | Shipped | Speed | Outputs |
|---|---|---|---|---|
| Text detection | 4.8 MB | 2.7 MB | 260 → 241 ms per page | identical |
| Text recognition | 8.0 MB | 4.3 MB | 56 → 53 ms per line | max difference 0.007 (same text read) |
| Corners | 4.8 MB | 2.6 MB | 30 → 36 ms per image | max difference 0.002 |

## Why these models

Text: PaddleOCR's PP-OCRv5 mobile pair had the fewest errors on rendered A4 pages (clean,
photographed, black & white, 150 dpi): 0.07–0.11% of characters, against 0.24% for the English
recognizer and PP-OCRv6 small (31 MB), 0.46% for PP-OCRv6 tiny (drops spaces, reads 0 as o) and
57% for int8 quantization (the detector breaks). Vision-language OCR models (DeepSeek-OCR,
GLM-OCR, olmOCR and similar) need gigabytes of GPU memory.

Corners: DocAligner's lcnet100 found the page in the test photos with a page in it (ID cards on a
dark desk, an invoice on wood, a beige desk, a cluttered background; corners within 3–4 px of
1600) and nothing in photos without one; a white page on a near-white desk still beats it.
Grayscale input works as well as color. On a phone it double-checks OpenCV's outline: they agree
within 1% on ordinary photos, and where OpenCV was fooled (the beige desk) the model was right.
