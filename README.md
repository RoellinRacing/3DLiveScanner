# 3DLiveScanner MAX

The Android scanner in `scanner/` is being modernized for high-detail, textured
3D capture on current arm64 devices, with a dedicated Xiaomi 14 Ultra runtime
profile. The MAX build adds stable public ARCore depth modes, persistent point
measurements, TrueScale calibration, a scan-quality coach, device diagnostics,
and engineering export through Android's document picker.

Exports include millimetre-based STL and 3MF, mesh PLY, textured OBJ-ZIP, and a
versioned raw `.scanpkg`. The Android document picker can save directly to any
installed provider, including Google Drive.

See [`docs/MAX_SCANNER.md`](docs/MAX_SCANNER.md) for the feature and accuracy
contract. Installable APKs are produced by the Android Scanner APK workflow.

# Repository content

## night_vision
Viewer of ToF sensor data. This project could be used as night vision: https://www.xda-developers.com/huawei-p30-pro-honor-view-20-night-vision/

## scanner
The main project of this repository containing 3D Live Scanner: https://youtu.be/ku_Slo-li3c

## Libraries
* arcore and arengine are AR SDKs which makes the 3D scanning possible
* common contains source codes used in multiple apps
* third_party contains several libraries with different licences

## Tests
* dataset_extractor is a Linux program to extract point cloud from dataset captured by 3D scanner in PLY format
* dataset_viewer is a Linux program for viewing dataset captured by 3D scanner

## Build

```bash
cd scanner
./gradlew testDebugUnitTest lintDebug assembleDebug
```
