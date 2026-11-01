# core:ocr

On-device text recognition for searchable PDFs. `TextRecognizer` finds text lines with a DB
detector and reads them with a CTC recognizer, both run by ONNX Runtime; OpenCV does the image
work around them. Word positions come from the recognizer's per-character time steps, so the
PDF text layer can be placed word by word. Nothing leaves the phone.

The models are PaddleOCR's PP-OCRv5 mobile text detector and Latin recognizer (Apache-2.0,
`assets/ocr/LICENSE`), stored at half precision in ONNX Runtime's format: 7 MB together. Where
they come from, how they're converted and why they were chosen: [tools/models](../../tools/models).
