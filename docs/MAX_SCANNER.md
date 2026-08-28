# 3DLiveScanner MAX

## Capture pipeline

- Public ARCore `AUTOMATIC` and `RAW_DEPTH_ONLY` modes only; undocumented enum
  tricks are not used by the production path.
- Depth frames are fused once per unique timestamp and image plane row/pixel
  strides are respected.
- Continuous autofocus, motion/tracking coaching, thermal warnings, and an
  8 mm high-detail default profile. A 5 mm profile is available for small scan
  volumes on high-memory devices.
- Textured OBJ remains the visual master because the existing atlas pipeline
  preserves camera textures there.

## Measurement contract

Measurements are picked on the reconstructed triangle surface and persist while
the model is inspected. The overlay reports direct distance and axis deltas.
TrueScale accepts one known reference distance and uniformly rescales the whole
mesh around the first reference point.

The displayed pick quality describes local pick stability. It is not a certified
metrology uncertainty. Accuracy is limited by depth quality, voxel size, viewing
angle, surface texture, tracking drift, and local mesh density. Software or AI
depth must never be presented as physical LiDAR precision.

Recommended workflow:

1. Use diffuse, textured lighting and avoid reflective or transparent surfaces.
2. Keep roughly 60–80% overlap and move slowly when the coach warns.
3. Include a rigid reference of known length in the same depth range as the part.
4. Finish the full orbit, inspect coverage, then apply TrueScale once.
5. Export 3MF or STL in millimetres for CAD; keep OBJ-ZIP for texture review and
   `.scanpkg` when the raw capture may need later reprocessing.

## Xiaomi 14 Ultra

The phone's laser autofocus assists focusing but is not a full-frame LiDAR/ToF
depth sensor. Runtime diagnostics therefore distinguish physical hardware depth,
ARCore-computed depth, Camera2 `DEPTH_OUTPUT`, and feature-point fallback. Long-
press the scan status panel to create a JSON capability report.

## Export and Drive

STL and 3MF coordinates are written in millimetres. PLY and the raw package keep
metres and declare that fact in metadata. Exports are first generated in the app
cache and then passed to Android's `ACTION_CREATE_DOCUMENT`; Google Drive appears
automatically when its document provider is installed. Sharing uses a narrowly
scoped `FileProvider` URI.
