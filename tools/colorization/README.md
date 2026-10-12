# Colorization graphs

The Android app runs the same two models as iOS (DDColor Large, ECCV16) as ONNX graphs on ONNX
Runtime. They are not in the repository: the app downloads them from the model host on demand.

1. `export_models.py <out>` exports both graphs from the colorize-lab checkout (torch weights).
   The graph contracts are in its docstring and in `app/OrtColorizationModels.kt`.
2. `check_parity.py <out> eccv16|ddcolor` compares ONNX Runtime with torch on the parity frames
   (`app/src/test/resources/fixtures/ios-parity`) and prints the model stage next to the
   reference's `4_model_*` statistics.
3. `package_models.py <out> <dist>` zips the graphs and writes `manifest.json`; upload `<dist>`
   to the host under `models/android/v1` (or build with `-Prewind.colorizationModelsUrl=…`).
