"""Packs exported graphs for the model host: one `<name>.zip` per graph plus `manifest.json`.

The manifest is iOS's schema (`ColorizationManifest`): `unpackedName` is the file the archive
unpacks to, `bytes` and `sha256` describe the archive itself, which the app checks before
unpacking. Upload the output directory's contents under the prefix the app is built with
(`rewind.colorizationModelsUrl`, by default `.../models/android/v1`).

  python3 package_models.py <dir with DDColorLarge.onnx, ECCV16.onnx> <out dir>
"""

import hashlib
import json
import pathlib
import sys
import zipfile

SRC = pathlib.Path(sys.argv[1])
OUT = pathlib.Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)

models = []
for name in ("DDColorLarge.onnx", "ECCV16.onnx"):
    archive = OUT / f"{name}.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.write(SRC / name, arcname=name)
    data = archive.read_bytes()
    models.append({
        "unpackedName": name,
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    })
    print(f"{archive.name}: {len(data) / 1e6:.1f} MB")

(OUT / "manifest.json").write_text(json.dumps({"models": models}, indent=2) + "\n")
print("manifest.json written")
