# Third-party OCR components

The manual 0.4 probe uses Tesseract4Android 4.9.0 (standard, single-threaded variant), distributed through JitPack. Upstream source and licensing: https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0 . Its native dependencies include Tesseract, Leptonica, libjpeg and libpng under their respective licenses. See the upstream release for native dependency notices. No ML Kit dependency is used.

The bundled English LSTM model is from tesseract-ocr/tessdata_fast tag 4.1.0:
https://github.com/tesseract-ocr/tessdata_fast/blob/4.1.0/eng.traineddata

Model SHA-256: 7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2
Model size: 4,113,088 bytes.
The model's Apache-2.0 license is included beside it in app/src/main/assets/tessdata/LICENSE.

Only model data is copied to private app storage at runtime. Screen pixels and recognized screen text are not written to disk. Recognition is manually requested once, not polled; only exact Skip-label candidates, coordinates, confidence and counts are logged. There is no analytics client or network permission. This is a diagnostic experiment, not evidence of reliable ad detection or safe automatic input.
