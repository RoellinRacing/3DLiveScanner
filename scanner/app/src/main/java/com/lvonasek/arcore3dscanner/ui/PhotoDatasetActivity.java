package com.lvonasek.arcore3dscanner.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.ar.core.ArCoreApk;
import com.lvonasek.arcore3dscanner.R;
import com.lvonasek.utils.Compatibility;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ARCore-independent calibrated photo capture for unsupported devices.
 * It intentionally uses only Camera2 and Android sensors, so a broken ARCore
 * native runtime can never be loaded from this Activity.
 */
public final class PhotoDatasetActivity extends AbstractActivity
    implements SensorEventListener {

  private static final int REQUEST_SAVE_DATASET = 0x3D20;
  private static final long AUTO_INTERVAL_MS = 1100;
  private static final SparseIntArray DISPLAY_ORIENTATIONS = new SparseIntArray();

  static {
    DISPLAY_ORIENTATIONS.append(Surface.ROTATION_0, 90);
    DISPLAY_ORIENTATIONS.append(Surface.ROTATION_90, 0);
    DISPLAY_ORIENTATIONS.append(Surface.ROTATION_180, 270);
    DISPLAY_ORIENTATIONS.append(Surface.ROTATION_270, 180);
  }

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Object frameLock = new Object();
  private final List<JSONObject> frames = new ArrayList<>();
  private final float[] rotationVector = new float[5];

  private TextureView preview;
  private TextView status;
  private Button autoButton;
  private Button captureButton;
  private Button finishButton;

  private HandlerThread cameraThread;
  private Handler cameraHandler;
  private CameraDevice camera;
  private CameraCaptureSession captureSession;
  private CaptureRequest.Builder previewRequest;
  private ImageReader imageReader;
  private CameraCharacteristics cameraCharacteristics;
  private String cameraId;
  private Size jpegSize;
  private Size previewSize;
  private int sensorOrientation;
  private boolean continuousAutoFocus;

  private SensorManager sensorManager;
  private Sensor rotationSensor;
  private Sensor gyroSensor;
  private Sensor accelerationSensor;
  private volatile float angularSpeed;
  private volatile float linearAcceleration;

  private File datasetDirectory;
  private File archive;
  private String sessionName;
  private int nextImageIndex;
  private volatile PendingCapture pendingCapture;
  private volatile boolean capturePending;
  private volatile boolean autoCapture;
  private volatile boolean cameraReady;
  private volatile boolean cameraOpening;
  private volatile boolean activityActive;
  private volatile boolean exporting;
  private volatile long lastSavedAt;

  private final TextureView.SurfaceTextureListener textureListener =
      new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
          openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
          configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
          closeCamera();
          return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }
      };

  private final Runnable autoCaptureRunnable = new Runnable() {
    @Override
    public void run() {
      if (!autoCapture || isFinishing() || isDestroyed()) return;
      long now = SystemClock.elapsedRealtime();
      if (cameraReady && !capturePending && now - lastSavedAt >= AUTO_INTERVAL_MS) {
        if (angularSpeed < 0.85f && linearAcceleration < 2.8f) {
          requestCapture();
        } else {
          status.setText(R.string.photo_scan_hold_steady);
        }
      }
      mainHandler.postDelayed(this, 220);
    }
  };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_photo_dataset);

    preview = findViewById(R.id.photo_preview);
    status = findViewById(R.id.photo_scan_status);
    autoButton = findViewById(R.id.photo_scan_auto);
    captureButton = findViewById(R.id.photo_scan_capture);
    finishButton = findViewById(R.id.photo_scan_finish);
    preview.setSurfaceTextureListener(textureListener);

    sessionName = "PhotoScan_" + new SimpleDateFormat(
        "yyyyMMdd_HHmmss", Locale.US).format(new Date());
    File root = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
    if (root == null) root = getFilesDir();
    datasetDirectory = new File(new File(root, "3D Live Scanner/PhotoScans"), sessionName);
    if (!datasetDirectory.isDirectory() && !datasetDirectory.mkdirs()) {
      Toast.makeText(this, R.string.photo_scan_capture_failed, Toast.LENGTH_LONG).show();
      finish();
      return;
    }

    autoButton.setOnClickListener(view -> toggleAutoCapture());
    captureButton.setOnClickListener(view -> requestCapture());
    finishButton.setOnClickListener(view -> createArchive());

    sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    if (sensorManager != null) {
      rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
      gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
      accelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
    }
    updateCount();
  }

  @Override
  protected void onResume() {
    super.onResume();
    activityActive = true;
    startCameraThread();
    registerSensors();
    if (preview.isAvailable()) {
      openCamera(preview.getWidth(), preview.getHeight());
    } else {
      preview.setSurfaceTextureListener(textureListener);
    }
  }

  @Override
  protected void onPause() {
    activityActive = false;
    setAutoCapture(false);
    unregisterSensors();
    closeCamera();
    stopCameraThread();
    super.onPause();
  }

  @Override
  public int getNavigationBarColor() {
    return Color.BLACK;
  }

  @Override
  public int getStatusBarColor() {
    return Color.BLACK;
  }

  private void startCameraThread() {
    if (cameraThread != null) return;
    cameraThread = new HandlerThread("photo-dataset-camera");
    cameraThread.start();
    cameraHandler = new Handler(cameraThread.getLooper());
  }

  private void stopCameraThread() {
    HandlerThread thread = cameraThread;
    cameraThread = null;
    cameraHandler = null;
    if (thread == null) return;
    thread.quitSafely();
    try {
      thread.join(1500);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  @SuppressLint("MissingPermission")
  private void openCamera(int width, int height) {
    if (!activityActive || camera != null || cameraOpening ||
        cameraHandler == null || isFinishing()) return;
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      showCameraFailure(null);
      return;
    }
    try {
      CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
      if (manager == null || !selectCamera(manager)) {
        showCameraFailure(null);
        return;
      }
      configureTransform(width, height);
      cameraOpening = true;
      manager.openCamera(cameraId, new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice openedCamera) {
          cameraOpening = false;
          if (!activityActive || isFinishing() || !preview.isAvailable()) {
            openedCamera.close();
            return;
          }
          camera = openedCamera;
          createPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice disconnectedCamera) {
          cameraOpening = false;
          disconnectedCamera.close();
          if (camera == disconnectedCamera) camera = null;
          cameraReady = false;
        }

        @Override
        public void onError(CameraDevice failedCamera, int error) {
          cameraOpening = false;
          failedCamera.close();
          if (camera == failedCamera) camera = null;
          cameraReady = false;
          showCameraFailure(null);
        }
      }, cameraHandler);
    } catch (Throwable error) {
      cameraOpening = false;
      showCameraFailure(error);
    }
  }

  private boolean selectCamera(CameraManager manager) throws CameraAccessException {
    if (cameraId != null && jpegSize != null && previewSize != null) return true;
    for (String id : manager.getCameraIdList()) {
      CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
      Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
      if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
      StreamConfigurationMap map = characteristics.get(
          CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      if (map == null) continue;
      Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
      Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
      if (jpegSizes == null || jpegSizes.length == 0 ||
          previewSizes == null || previewSizes.length == 0) continue;

      cameraId = id;
      cameraCharacteristics = characteristics;
      jpegSize = chooseJpegSize(jpegSizes);
      previewSize = choosePreviewSize(previewSizes, jpegSize);
      Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
      sensorOrientation = orientation == null ? 90 : orientation;
      int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
      continuousAutoFocus = contains(modes,
          CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
      return true;
    }
    return false;
  }

  private static Size chooseJpegSize(Size[] sizes) {
    Size best = null;
    Size smallest = null;
    for (Size size : sizes) {
      long area = (long) size.getWidth() * size.getHeight();
      if (smallest == null || area < (long) smallest.getWidth() * smallest.getHeight()) {
        smallest = size;
      }
      if (area <= 12_600_000L && Math.max(size.getWidth(), size.getHeight()) <= 4608) {
        if (best == null || area > (long) best.getWidth() * best.getHeight()) best = size;
      }
    }
    return best == null ? smallest : best;
  }

  private static Size choosePreviewSize(Size[] sizes, Size capture) {
    double aspect = (double) capture.getWidth() / capture.getHeight();
    Size best = null;
    double bestScore = Double.MAX_VALUE;
    for (Size size : sizes) {
      if (size.getWidth() > 1920 || size.getHeight() > 1080) continue;
      double sizeAspect = (double) size.getWidth() / size.getHeight();
      double score = Math.abs(sizeAspect - aspect) * 10.0
          + Math.abs((long) size.getWidth() * size.getHeight() - 1920L * 1080L)
          / (1920.0 * 1080.0);
      if (score < bestScore) {
        bestScore = score;
        best = size;
      }
    }
    if (best != null) return best;
    return Arrays.stream(sizes).max(Comparator.comparingLong(
        size -> (long) size.getWidth() * size.getHeight())).orElse(sizes[0]);
  }

  private static boolean contains(int[] values, int expected) {
    if (values == null) return false;
    for (int value : values) if (value == expected) return true;
    return false;
  }

  private void createPreviewSession() {
    CameraDevice activeCamera = camera;
    SurfaceTexture texture = preview.getSurfaceTexture();
    Handler handler = cameraHandler;
    if (activeCamera == null || texture == null || handler == null) return;
    try {
      texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
      Surface previewSurface = new Surface(texture);
      imageReader = ImageReader.newInstance(
          jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
      imageReader.setOnImageAvailableListener(this::saveImage, handler);
      previewRequest = activeCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      previewRequest.addTarget(previewSurface);
      configureAutoControls(previewRequest);
      activeCamera.createCaptureSession(
          Arrays.asList(previewSurface, imageReader.getSurface()),
          new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
              if (camera == null) {
                session.close();
                return;
              }
              captureSession = session;
              try {
                session.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
                cameraReady = true;
                runOnUiThread(PhotoDatasetActivity.this::updateCount);
              } catch (Throwable error) {
                showCameraFailure(error);
              }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
              cameraReady = false;
              showCameraFailure(null);
            }
          }, handler);
    } catch (Throwable error) {
      showCameraFailure(error);
    }
  }

  private void configureAutoControls(CaptureRequest.Builder request) {
    request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
    request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
    if (continuousAutoFocus) {
      request.set(CaptureRequest.CONTROL_AF_MODE,
          CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
    }
  }

  private void requestCapture() {
    if (!cameraReady || capturePending || exporting) return;
    Handler handler = cameraHandler;
    if (handler != null) {
      capturePending = true;
      handler.post(this::captureStill);
    }
  }

  private void captureStill() {
    CameraDevice activeCamera = camera;
    CameraCaptureSession session = captureSession;
    ImageReader reader = imageReader;
    if (activeCamera == null || session == null || reader == null || !capturePending) {
      capturePending = false;
      return;
    }
    int index = ++nextImageIndex;
    float[] rotation;
    synchronized (rotationVector) {
      rotation = rotationVector.clone();
    }
    PendingCapture pending = new PendingCapture(index, System.nanoTime(),
        rotation, angularSpeed, linearAcceleration, getJpegOrientation());
    pendingCapture = pending;

    try {
      CaptureRequest.Builder still = activeCamera.createCaptureRequest(
          CameraDevice.TEMPLATE_STILL_CAPTURE);
      still.addTarget(reader.getSurface());
      configureAutoControls(still);
      still.set(CaptureRequest.JPEG_ORIENTATION, pending.jpegOrientation);
      still.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
      session.capture(still.build(), new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureFailed(CameraCaptureSession captureSession,
                                    CaptureRequest request, CaptureFailure failure) {
          failPendingCapture(pending);
        }
      }, cameraHandler);
      mainHandler.postDelayed(() -> {
        if (pendingCapture == pending && capturePending) failPendingCapture(pending);
      }, 6000);
    } catch (Throwable error) {
      failPendingCapture(pending);
    }
  }

  private void saveImage(ImageReader reader) {
    Image image = null;
    PendingCapture pending = pendingCapture;
    try {
      image = reader.acquireNextImage();
      if (image == null || pending == null) return;
      ByteBuffer buffer = image.getPlanes()[0].getBuffer();
      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);

      String fileName = String.format(Locale.US, "image_%04d.jpg", pending.index);
      File output = new File(datasetDirectory, fileName);
      try (FileOutputStream stream = new FileOutputStream(output)) {
        stream.write(bytes);
        stream.getFD().sync();
      }

      JSONObject frame = new JSONObject();
      frame.put("file", fileName);
      frame.put("capture_index", pending.index);
      frame.put("timestamp_ns", pending.timestampNanos);
      frame.put("jpeg_orientation", pending.jpegOrientation);
      frame.put("rotation_vector", jsonArray(pending.rotation));
      frame.put("angular_speed_rad_s", pending.angularSpeed);
      frame.put("linear_acceleration_m_s2", pending.linearAcceleration);
      synchronized (frameLock) {
        frames.add(frame);
      }
      lastSavedAt = SystemClock.elapsedRealtime();
      pendingCapture = null;
      capturePending = false;
      runOnUiThread(this::updateCount);
    } catch (Throwable error) {
      failPendingCapture(pending);
    } finally {
      if (image != null) image.close();
    }
  }

  private void failPendingCapture(PendingCapture pending) {
    if (pending == null || pendingCapture != pending) return;
    pendingCapture = null;
    capturePending = false;
    runOnUiThread(() -> Toast.makeText(
        this, R.string.photo_scan_capture_failed, Toast.LENGTH_LONG).show());
  }

  private int getJpegOrientation() {
    int rotation = getWindowManager().getDefaultDisplay().getRotation();
    return (DISPLAY_ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360;
  }

  private void configureTransform(int viewWidth, int viewHeight) {
    if (preview == null || previewSize == null || viewWidth == 0 || viewHeight == 0) return;
    int rotation = getWindowManager().getDefaultDisplay().getRotation();
    Matrix matrix = new Matrix();
    RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
    RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
    float centerX = viewRect.centerX();
    float centerY = viewRect.centerY();
    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
      bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
      matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
      float scale = Math.max(
          (float) viewHeight / previewSize.getHeight(),
          (float) viewWidth / previewSize.getWidth());
      matrix.postScale(scale, scale, centerX, centerY);
      matrix.postRotate(90 * (rotation - 2), centerX, centerY);
    } else if (rotation == Surface.ROTATION_180) {
      matrix.postRotate(180, centerX, centerY);
    }
    preview.setTransform(matrix);
  }

  private void toggleAutoCapture() {
    setAutoCapture(!autoCapture);
  }

  private void setAutoCapture(boolean enabled) {
    autoCapture = enabled;
    mainHandler.removeCallbacks(autoCaptureRunnable);
    if (autoButton != null) {
      autoButton.setText(enabled ? R.string.photo_scan_auto_stop
          : R.string.photo_scan_auto_start);
      autoButton.setSelected(enabled);
    }
    if (enabled) {
      lastSavedAt = 0;
      mainHandler.post(autoCaptureRunnable);
    }
  }

  private void updateCount() {
    int count;
    synchronized (frameLock) {
      count = frames.size();
    }
    status.setText(getString(R.string.photo_scan_ready, count));
  }

  private void createArchive() {
    if (capturePending) {
      Toast.makeText(this, R.string.photo_scan_wait, Toast.LENGTH_LONG).show();
      return;
    }
    int count;
    synchronized (frameLock) {
      count = frames.size();
    }
    if (count < 12) {
      Toast.makeText(this, R.string.photo_scan_need_photos, Toast.LENGTH_LONG).show();
      return;
    }
    if (exporting) return;
    setAutoCapture(false);
    exporting = true;
    setControlsEnabled(false);
    status.setText(R.string.working);
    new Thread(() -> {
      try {
        writeDatasetManifest();
        File exportDirectory = new File(getCacheDir(), "exports");
        if (!exportDirectory.isDirectory() && !exportDirectory.mkdirs()) {
          throw new IllegalStateException("Cannot create export directory");
        }
        archive = new File(exportDirectory, sessionName + ".photoscan.zip");
        zipDataset(archive);
        runOnUiThread(() -> {
          exporting = false;
          setControlsEnabled(true);
          updateCount();
          showArchiveActions(count);
        });
      } catch (Throwable error) {
        runOnUiThread(() -> {
          exporting = false;
          setControlsEnabled(true);
          updateCount();
          Toast.makeText(this, R.string.photo_scan_export_failed, Toast.LENGTH_LONG).show();
        });
      }
    }, "photo-dataset-export").start();
  }

  private void setControlsEnabled(boolean enabled) {
    autoButton.setEnabled(enabled);
    captureButton.setEnabled(enabled);
    finishButton.setEnabled(enabled);
  }

  private void writeDatasetManifest() throws Exception {
    JSONObject manifest = new JSONObject();
    manifest.put("schema", 1);
    manifest.put("type", "3DLiveScanner calibrated photo dataset");
    manifest.put("created_utc", new SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()));
    manifest.put("manufacturer", Build.MANUFACTURER);
    manifest.put("model", Build.MODEL);
    manifest.put("device", Build.DEVICE);
    manifest.put("android_api", Build.VERSION.SDK_INT);
    manifest.put("build_fingerprint", Build.FINGERPRINT);
    manifest.put("metric_camera_translation_available", false);
    manifest.put("note", "Use feature matching / photogrammetry to estimate camera poses and mesh.");

    JSONObject cameraJson = new JSONObject();
    cameraJson.put("camera_id", cameraId);
    cameraJson.put("jpeg_width", jpegSize.getWidth());
    cameraJson.put("jpeg_height", jpegSize.getHeight());
    cameraJson.put("sensor_orientation", sensorOrientation);
    cameraJson.put("camera2_depth_output", Compatibility.hasCamera2DepthOutput(this));
    if (cameraCharacteristics != null) {
      float[] intrinsics = cameraCharacteristics.get(
          CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
      float[] focalLengths = cameraCharacteristics.get(
          CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
      SizeF sensorSize = cameraCharacteristics.get(
          CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
      if (intrinsics != null) cameraJson.put("intrinsic_calibration", jsonArray(intrinsics));
      if (focalLengths != null) cameraJson.put("focal_lengths_mm", jsonArray(focalLengths));
      if (sensorSize != null) {
        cameraJson.put("sensor_width_mm", sensorSize.getWidth());
        cameraJson.put("sensor_height_mm", sensorSize.getHeight());
      }
    }
    ArCoreApk.Availability availability = Compatibility.getArCoreAvailability(this);
    manifest.put("arcore_catalogue_availability", availability.name());
    manifest.put("camera", cameraJson);

    JSONArray frameArray = new JSONArray();
    synchronized (frameLock) {
      for (JSONObject frame : frames) frameArray.put(frame);
    }
    manifest.put("frames", frameArray);

    File file = new File(datasetDirectory, "manifest.json");
    try (FileOutputStream stream = new FileOutputStream(file);
         OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
      writer.write(manifest.toString(2));
      writer.flush();
      stream.getFD().sync();
    }

    File readme = new File(datasetDirectory, "README.txt");
    try (FileOutputStream stream = new FileOutputStream(readme);
         OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
      writer.write("3D Live Scanner calibrated photo dataset\n\n");
      writer.write("The images are intended for photogrammetry / feature matching.\n");
      writer.write("manifest.json contains camera calibration, device data and motion metadata.\n");
      writer.write("No ARCore session was used and no metric camera translation is claimed.\n");
    }
  }

  private void zipDataset(File output) throws Exception {
    File[] files = datasetDirectory.listFiles(File::isFile);
    if (files == null) throw new IllegalStateException("Dataset is not readable");
    Arrays.sort(files, Comparator.comparing(File::getName));
    byte[] buffer = new byte[64 * 1024];
    try (ZipOutputStream zip = new ZipOutputStream(
        new BufferedOutputStream(new FileOutputStream(output)))) {
      for (File file : files) {
        ZipEntry entry = new ZipEntry(file.getName());
        entry.setTime(file.lastModified());
        zip.putNextEntry(entry);
        try (FileInputStream input = new FileInputStream(file)) {
          int read;
          while ((read = input.read(buffer)) >= 0) {
            if (read > 0) zip.write(buffer, 0, read);
          }
        }
        zip.closeEntry();
      }
    }
  }

  private void showArchiveActions(int count) {
    new AlertDialog.Builder(this)
        .setTitle(R.string.photo_scan_dataset_ready)
        .setMessage(getString(R.string.photo_scan_dataset_ready_message, count))
        .setPositiveButton(R.string.photo_scan_save_drive,
            (dialog, which) -> saveArchiveToDocument())
        .setNegativeButton(R.string.photo_scan_share,
            (dialog, which) -> shareArchive())
        .setNeutralButton(R.string.photo_scan_continue, null)
        .show();
  }

  private void saveArchiveToDocument() {
    if (archive == null || !archive.isFile()) return;
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("application/zip");
    intent.putExtra(Intent.EXTRA_TITLE, archive.getName());
    startActivityForResult(intent, REQUEST_SAVE_DATASET);
  }

  private void shareArchive() {
    if (archive == null || !archive.isFile()) return;
    try {
      Uri uri = FileProvider.getUriForFile(
          this, getPackageName() + ".provider", archive);
      Intent intent = new Intent(Intent.ACTION_SEND);
      intent.setType("application/zip");
      intent.putExtra(Intent.EXTRA_STREAM, uri);
      intent.setClipData(ClipData.newRawUri("photo-to-3D dataset", uri));
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(
          intent, getString(R.string.photo_scan_share_chooser)));
    } catch (Throwable error) {
      Toast.makeText(this, R.string.photo_scan_export_failed, Toast.LENGTH_LONG).show();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQUEST_SAVE_DATASET || resultCode != RESULT_OK ||
        data == null || data.getData() == null || archive == null) return;
    Uri destination = data.getData();
    new Thread(() -> {
      try (FileInputStream input = new FileInputStream(archive);
           OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
        if (output == null) throw new IllegalStateException("Destination unavailable");
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (read > 0) output.write(buffer, 0, read);
        }
        output.flush();
        runOnUiThread(() -> Toast.makeText(
            this, R.string.photo_scan_saved, Toast.LENGTH_LONG).show());
      } catch (Throwable error) {
        runOnUiThread(() -> Toast.makeText(
            this, R.string.photo_scan_export_failed, Toast.LENGTH_LONG).show());
      }
    }, "photo-dataset-save").start();
  }

  private void closeCamera() {
    cameraReady = false;
    cameraOpening = false;
    capturePending = false;
    pendingCapture = null;
    CameraCaptureSession session = captureSession;
    captureSession = null;
    if (session != null) session.close();
    CameraDevice activeCamera = camera;
    camera = null;
    if (activeCamera != null) activeCamera.close();
    ImageReader reader = imageReader;
    imageReader = null;
    if (reader != null) reader.close();
  }

  private void showCameraFailure(Throwable error) {
    cameraReady = false;
    if (error != null) Log.e(TAG, "Photo scan camera failure", error);
    runOnUiThread(() -> {
      if (!activityActive || isFinishing() || isDestroyed()) return;
      status.setText(R.string.photo_scan_camera_failed);
      Toast.makeText(this, R.string.photo_scan_camera_failed, Toast.LENGTH_LONG).show();
    });
  }

  private void registerSensors() {
    if (sensorManager == null) return;
    try {
      if (rotationSensor != null) sensorManager.registerListener(
          this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
      if (gyroSensor != null) sensorManager.registerListener(
          this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
      if (accelerationSensor != null) sensorManager.registerListener(
          this, accelerationSensor, SensorManager.SENSOR_DELAY_GAME);
    } catch (Throwable ignored) {
    }
  }

  private void unregisterSensors() {
    if (sensorManager == null) return;
    try {
      sensorManager.unregisterListener(this);
    } catch (Throwable ignored) {
    }
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (event == null || event.sensor == null || event.values == null) return;
    int type = event.sensor.getType();
    if (type == Sensor.TYPE_ROTATION_VECTOR) {
      synchronized (rotationVector) {
        Arrays.fill(rotationVector, 0f);
        System.arraycopy(event.values, 0, rotationVector, 0,
            Math.min(rotationVector.length, event.values.length));
      }
    } else if (type == Sensor.TYPE_GYROSCOPE && event.values.length >= 3) {
      angularSpeed = smoothedMagnitude(angularSpeed, event.values);
    } else if (type == Sensor.TYPE_LINEAR_ACCELERATION && event.values.length >= 3) {
      linearAcceleration = smoothedMagnitude(linearAcceleration, event.values);
    }
  }

  private static float smoothedMagnitude(float previous, float[] values) {
    float magnitude = (float) Math.sqrt(values[0] * values[0]
        + values[1] * values[1] + values[2] * values[2]);
    return previous * 0.78f + magnitude * 0.22f;
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
  }

  @Override
  public void onBackPressed() {
    int count;
    synchronized (frameLock) {
      count = frames.size();
    }
    if (count == 0 || archive != null) {
      finish();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(R.string.photo_scan_discard_title)
        .setMessage(R.string.photo_scan_discard_message)
        .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private static JSONArray jsonArray(float[] values) {
    JSONArray array = new JSONArray();
    // Use the Object overload: Android's primitive-double overload declares
    // JSONException even though these sensor/calibration values are finite.
    if (values != null) for (float value : values) array.put(Float.valueOf(value));
    return array;
  }

  private static final class PendingCapture {
    final int index;
    final long timestampNanos;
    final float[] rotation;
    final float angularSpeed;
    final float linearAcceleration;
    final int jpegOrientation;

    PendingCapture(int index, long timestampNanos, float[] rotation,
                   float angularSpeed, float linearAcceleration,
                   int jpegOrientation) {
      this.index = index;
      this.timestampNanos = timestampNanos;
      this.rotation = rotation;
      this.angularSpeed = angularSpeed;
      this.linearAcceleration = linearAcceleration;
      this.jpegOrientation = jpegOrientation;
    }
  }
}
