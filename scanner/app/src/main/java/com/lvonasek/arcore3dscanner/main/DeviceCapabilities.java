package com.lvonasek.arcore3dscanner.main;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Size;

import androidx.core.content.FileProvider;

import com.google.ar.core.ArCoreApk;
import com.lvonasek.arcore3dscanner.R;
import com.lvonasek.utils.Compatibility;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Runtime capability report and low-cost scan-quality signals. */
public final class DeviceCapabilities implements SensorEventListener {

  private static volatile ArCoreInfo cachedArCoreInfo;

  public enum Level { GOOD, WARNING, CRITICAL }

  public static final class ArCoreInfo {
    public String availability = "UNKNOWN";
    public boolean availabilitySupported;
    public boolean supported;
    public boolean installed;
    public boolean runtimeSessionCreated;
    public boolean automaticDepth;
    public boolean rawDepth;
    public boolean hardwareDepthCameraConfig;
    public String error = "";

    JSONObject toJson() throws Exception {
      JSONObject json = new JSONObject();
      json.put("availability", availability);
      json.put("availability_reports_supported", availabilitySupported);
      json.put("supported", supported);
      json.put("installed", installed);
      json.put("runtime_session_created", runtimeSessionCreated);
      json.put("automatic_depth_supported", automaticDepth);
      json.put("raw_depth_supported", rawDepth);
      json.put("hardware_depth_camera_config", hardwareDepthCameraConfig);
      json.put("probe_error", error);
      return json;
    }
  }

  public static final class CoachState {
    public final Level level;
    public final String message;
    public final String tracking;
    public final String depth;
    public final String thermal;
    public final float angularSpeed;

    CoachState(Level level, String message, String tracking, String depth,
               String thermal, float angularSpeed) {
      this.level = level;
      this.message = message;
      this.tracking = tracking;
      this.depth = depth;
      this.thermal = thermal;
      this.angularSpeed = angularSpeed;
    }
  }

  private final Context app;
  private final ArCoreInfo arCore;
  private final SensorManager sensors;
  private final PowerManager power;
  private final ActivityManager activityManager;
  private final Sensor gyro;
  private final Sensor linearAcceleration;
  private final boolean camera2DepthOutput;

  private volatile float angularSpeed;
  private volatile float linearAccelerationMagnitude;
  private volatile int runtimeArMode = -1;
  private volatile boolean started;

  public DeviceCapabilities(Context context, ArCoreInfo arCoreInfo) {
    Context application = context.getApplicationContext();
    app = application == null ? context : application;
    arCore = arCoreInfo == null ? new ArCoreInfo() : arCoreInfo;
    sensors = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
    power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
    activityManager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
    gyro = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    linearAcceleration = sensors == null ? null
        : sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
    camera2DepthOutput = probeBackCameraDepthOutput(app);
  }

  /** Catalogue-only ARCore probe; deliberately never constructs a Session. */
  public static ArCoreInfo probeArCore(Context context) {
    if (context == null) {
      ArCoreInfo result = new ArCoreInfo();
      result.error = "IllegalArgumentException: context is null";
      return result;
    }
    ArCoreInfo cached = cachedArCoreInfo;
    if (cached != null) return cached;
    synchronized (DeviceCapabilities.class) {
      cached = cachedArCoreInfo;
      if (cached != null) return cached;
      ArCoreInfo probed = probeArCoreUncached(context);
      // A transient availability must be allowed to recover on the next call.
      boolean installedProbe = probed.installed && probed.error.length() == 0;
      if (installedProbe) cachedArCoreInfo = probed;
      return probed;
    }
  }

  private static ArCoreInfo probeArCoreUncached(Context context) {
    ArCoreInfo result = new ArCoreInfo();
    try {
      ArCoreApk.Availability availability =
          ArCoreApk.getInstance().checkAvailability(context.getApplicationContext());
      // Availability can be transient on the first call. Keep the bound short
      // because this probe intentionally runs before the native session starts.
      for (int retry = 0;
           availability == ArCoreApk.Availability.UNKNOWN_CHECKING && retry < 8;
           retry++) {
        SystemClock.sleep(75);
        availability = ArCoreApk.getInstance()
            .checkAvailability(context.getApplicationContext());
      }
      result.availability = availability.name();
      switch (availability) {
        case SUPPORTED_INSTALLED:
          result.availabilitySupported = true;
          result.supported = true;
          result.installed = true;
          break;
        case SUPPORTED_APK_TOO_OLD:
        case SUPPORTED_NOT_INSTALLED:
          result.availabilitySupported = true;
          result.supported = true;
          return result;
        default:
          // Never bypass ARCore certification with a Java Session constructor.
          // On some Android 16 OEM builds that constructor performs a native
          // null call and kills the process before Java can catch anything.
          break;
      }
    } catch (Throwable error) {
      result.error = error.getClass().getSimpleName() + ": "
          + String.valueOf(error.getMessage());
    }
    return result;
  }

  public ArCoreInfo getArCoreInfo() {
    return arCore;
  }

  public boolean hasCamera2DepthOutput() {
    return camera2DepthOutput;
  }

  /** Native modes: 0 Google SFM, 1 Google hardware depth, 2 Google face,
   * 3 Huawei SFM, 4 Huawei hardware depth, 5 Huawei face. */
  public void setRuntimeArMode(int mode) {
    runtimeArMode = mode;
  }

  public synchronized void start() {
    if (started || sensors == null) return;
    try {
      if (gyro != null) {
        sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
      }
      if (linearAcceleration != null) {
        sensors.registerListener(this, linearAcceleration, SensorManager.SENSOR_DELAY_GAME);
      }
      started = true;
    } catch (Throwable ignored) {
      // Scan coaching is optional. A broken vendor sensor implementation must
      // never take down the AR session or project viewer.
      started = false;
    }
  }

  public synchronized void stop() {
    if (!started || sensors == null) return;
    try {
      sensors.unregisterListener(this);
    } catch (Throwable ignored) {
    }
    started = false;
    angularSpeed = 0f;
    linearAccelerationMagnitude = 0f;
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    float x = event.values[0];
    float y = event.values[1];
    float z = event.values[2];
    float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
    if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
      angularSpeed = 0.82f * angularSpeed + 0.18f * magnitude;
    } else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
      linearAccelerationMagnitude = 0.82f * linearAccelerationMagnitude
          + 0.18f * magnitude;
    }
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
  }

  public CoachState evaluate(String rawNativeEvent) {
    String raw = rawNativeEvent == null ? "" : rawNativeEvent;
    int thermal = currentThermalStatus();
    String depth = depthLabel();

    if (raw.contains("MT_JUMP")) {
      return state(Level.CRITICAL,
          app.getString(R.string.scan_coach_tracking_jump),
          "jump", depth, thermal);
    }
    if (raw.contains("MT_LOST")) {
      return state(Level.CRITICAL,
          app.getString(R.string.scan_coach_tracking_lost),
          "lost", depth, thermal);
    }
    if (thermal >= 3) {
      return state(Level.CRITICAL,
          app.getString(R.string.scan_coach_thermal_critical),
          "tracking", depth, thermal);
    }
    if (raw.contains("MT_INIT")) {
      return state(Level.WARNING,
          app.getString(R.string.scan_coach_tracking_init),
          "initializing", depth, thermal);
    }
    if (thermal >= 2) {
      return state(Level.WARNING,
          app.getString(R.string.scan_coach_thermal_warning),
          "tracking", depth, thermal);
    }
    // This is a motion-blur risk estimate, not an image sharpness measurement.
    if (angularSpeed > 1.20f || linearAccelerationMagnitude > 3.5f) {
      return state(Level.WARNING,
          app.getString(R.string.scan_coach_blur_risk),
          "tracking", depth, thermal);
    }
    if ((runtimeArMode == 0 || runtimeArMode == 1)
        && !arCore.automaticDepth && !arCore.rawDepth) {
      return state(Level.WARNING,
          app.getString(R.string.scan_coach_no_depth),
          "tracking", depth, thermal);
    }
    return state(Level.GOOD, "", "good", depth, thermal);
  }

  private CoachState state(Level level, String message, String tracking,
                           String depth, int thermal) {
    return new CoachState(level, message, tracking, depth,
        thermalName(thermal), angularSpeed);
  }

  private String depthLabel() {
    if (runtimeArMode == 1 || runtimeArMode == 4) return "hardware";
    if ((runtimeArMode == 0) && arCore.automaticDepth) return "ARCore";
    if ((runtimeArMode == 0) && arCore.rawDepth) return "ARCore raw";
    return "feature points";
  }

  public int currentThermalStatus() {
    if (Build.VERSION.SDK_INT >= 29 && power != null) {
      try {
        return power.getCurrentThermalStatus();
      } catch (Throwable ignored) {
        // Thermal coaching is informational and should remain non-fatal on a
        // vendor PowerManager implementation that cannot answer this query.
      }
    }
    return -1;
  }

  private static String thermalName(int status) {
    switch (status) {
      case 0: return "none";
      case 1: return "light";
      case 2: return "moderate";
      case 3: return "severe";
      case 4: return "critical";
      case 5: return "emergency";
      case 6: return "shutdown";
      default: return "unavailable";
    }
  }

  public File exportReport() throws Exception {
    File root = app.getExternalFilesDir(null);
    if (root == null) root = app.getExternalCacheDir();
    if (root == null) throw new IllegalStateException("External storage unavailable");
    File directory = new File(root, "diagnostics");
    if (!directory.exists() && !directory.mkdirs()) {
      throw new IllegalStateException("Cannot create diagnostics directory");
    }
    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    File output = new File(directory, "3DLiveScanner_" + stamp + ".json");
    try (FileOutputStream stream = new FileOutputStream(output)) {
      stream.write(buildReport().toString(2).getBytes(StandardCharsets.UTF_8));
    }
    return output;
  }

  public void shareReport(Activity activity, File report) {
    Uri uri = FileProvider.getUriForFile(activity,
        activity.getPackageName() + ".provider", report);
    Intent share = new Intent(Intent.ACTION_SEND);
    share.setType("application/json");
    share.putExtra(Intent.EXTRA_STREAM, uri);
    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    activity.startActivity(Intent.createChooser(
        share, activity.getString(R.string.diagnostics_share_chooser)));
  }

  public JSONObject buildReport() throws Exception {
    JSONObject report = new JSONObject();
    report.put("schema", 1);
    report.put("timestamp_ms", System.currentTimeMillis());
    JSONObject build = new JSONObject();
    build.put("manufacturer", Build.MANUFACTURER);
    build.put("brand", Build.BRAND);
    build.put("model", Build.MODEL);
    build.put("device", Build.DEVICE);
    build.put("product", Build.PRODUCT);
    build.put("hardware", Build.HARDWARE);
    build.put("fingerprint", Build.FINGERPRINT);
    build.put("sdk", Build.VERSION.SDK_INT);
    build.put("release", Build.VERSION.RELEASE);
    build.put("security_patch", Build.VERSION.SECURITY_PATCH);
    if (Build.VERSION.SDK_INT >= 31) {
      build.put("soc_manufacturer", Build.SOC_MANUFACTURER);
      build.put("soc_model", Build.SOC_MODEL);
    }
    report.put("build", build);
    report.put("arcore", arCore.toJson());
    report.put("google_play_store_installed", Compatibility.isPlayStoreSupported(app));
    report.put("huawei_ar_engine_installed",
        Compatibility.isHuaweiArEngineAvailable(app));
    report.put("runtime_ar_mode", runtimeArMode);
    report.put("camera2_back_depth_output", camera2DepthOutput);
    report.put("camera2_note",
        "DEPTH_OUTPUT alone does not prove a LiDAR/ToF sensor; laser autofocus is not a depth map.");
    report.put("cameras", cameraReport(app));
    report.put("sensors", sensorReport());
    report.put("memory", memoryReport());
    report.put("thermal_status", thermalName(currentThermalStatus()));
    report.put("thermal_status_code", currentThermalStatus());
    return report;
  }

  private JSONObject memoryReport() throws Exception {
    ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
    if (activityManager != null) activityManager.getMemoryInfo(info);
    Runtime runtime = Runtime.getRuntime();
    JSONObject json = new JSONObject();
    json.put("system_total_bytes", info.totalMem);
    json.put("system_available_bytes", info.availMem);
    json.put("system_low_memory", info.lowMemory);
    json.put("system_low_memory_threshold_bytes", info.threshold);
    json.put("java_max_bytes", runtime.maxMemory());
    json.put("java_total_bytes", runtime.totalMemory());
    json.put("java_free_bytes", runtime.freeMemory());
    return json;
  }

  private JSONArray sensorReport() throws Exception {
    JSONArray array = new JSONArray();
    if (sensors == null) return array;
    List<Sensor> list = sensors.getSensorList(Sensor.TYPE_ALL);
    for (Sensor sensor : list) {
      JSONObject json = new JSONObject();
      json.put("type", sensor.getType());
      json.put("string_type", sensor.getStringType());
      json.put("name", sensor.getName());
      json.put("vendor", sensor.getVendor());
      json.put("version", sensor.getVersion());
      json.put("maximum_range", sensor.getMaximumRange());
      json.put("resolution", sensor.getResolution());
      json.put("power_ma", sensor.getPower());
      json.put("minimum_delay_us", sensor.getMinDelay());
      if (Build.VERSION.SDK_INT >= 21) {
        json.put("reporting_mode", sensor.getReportingMode());
        json.put("wake_up", sensor.isWakeUpSensor());
      }
      array.put(json);
    }
    return array;
  }

  private static boolean probeBackCameraDepthOutput(Context context) {
    try {
      CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
      if (manager == null) return false;
      for (String id : manager.getCameraIdList()) {
        CameraCharacteristics c = manager.getCameraCharacteristics(id);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK
            && hasCapability(c,
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
          return true;
        }
      }
    } catch (Throwable ignored) {
    }
    return false;
  }

  private static JSONArray cameraReport(Context context) throws Exception {
    JSONArray cameras = new JSONArray();
    CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    if (manager == null) return cameras;
    for (String id : manager.getCameraIdList()) {
      JSONObject json = new JSONObject();
      try {
        CameraCharacteristics c = manager.getCameraCharacteristics(id);
        json.put("id", id);
        json.put("facing", facingName(c.get(CameraCharacteristics.LENS_FACING)));
        json.put("hardware_level", hardwareLevelName(
            c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));
        json.put("depth_output", hasCapability(c,
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT));
        json.put("raw", hasCapability(c,
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW));
        json.put("logical_multi_camera", Build.VERSION.SDK_INT >= 28 && hasCapability(c,
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA));
        JSONArray physical = new JSONArray();
        if (Build.VERSION.SDK_INT >= 28) {
          Set<String> ids = c.getPhysicalCameraIds();
          for (String physicalId : ids) physical.put(physicalId);
        }
        json.put("physical_camera_ids", physical);
        putValue(json, "sensor_pixel_array", c.get(
            CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE));
        putValue(json, "sensor_physical_size_mm", c.get(
            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE));
        putValue(json, "sensor_orientation", c.get(
            CameraCharacteristics.SENSOR_ORIENTATION));
        putValue(json, "focal_lengths_mm", c.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS));
        putValue(json, "minimum_focus_distance_diopters", c.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE));
        putValue(json, "intrinsic_calibration", c.get(
            CameraCharacteristics.LENS_INTRINSIC_CALIBRATION));
        StreamConfigurationMap map = c.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
          JSONArray formats = new JSONArray();
          for (int format : map.getOutputFormats()) {
            JSONObject f = new JSONObject();
            f.put("format", imageFormatName(format));
            Size largest = largest(map.getOutputSizes(format));
            if (largest != null) f.put("largest", largest.toString());
            formats.put(f);
          }
          json.put("output_formats", formats);
        }
      } catch (Throwable error) {
        json.put("id", id);
        json.put("error", error.getClass().getSimpleName() + ": "
            + String.valueOf(error.getMessage()));
      }
      cameras.put(json);
    }
    return cameras;
  }

  private static boolean hasCapability(CameraCharacteristics c, int wanted) {
    int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
    if (capabilities == null) return false;
    for (int capability : capabilities) if (capability == wanted) return true;
    return false;
  }

  private static Size largest(Size[] sizes) {
    if (sizes == null || sizes.length == 0) return null;
    Size best = sizes[0];
    for (Size size : sizes) {
      if ((long) size.getWidth() * size.getHeight()
          > (long) best.getWidth() * best.getHeight()) best = size;
    }
    return best;
  }

  private static void putValue(JSONObject target, String key, Object value) throws Exception {
    if (value == null) return;
    if (value instanceof float[]) {
      JSONArray array = new JSONArray();
      for (float f : (float[]) value) array.put(f);
      target.put(key, array);
    } else {
      target.put(key, String.valueOf(value));
    }
  }

  private static String facingName(Integer facing) {
    if (facing == null) return "unknown";
    if (facing == CameraCharacteristics.LENS_FACING_BACK) return "back";
    if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "front";
    if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "external";
    return String.valueOf(facing);
  }

  private static String hardwareLevelName(Integer level) {
    if (level == null) return "unknown";
    if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) return "legacy";
    if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) return "limited";
    if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) return "full";
    if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) return "level_3";
    if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) return "external";
    return String.valueOf(level);
  }

  private static String imageFormatName(int format) {
    if (format == ImageFormat.DEPTH16) return "DEPTH16";
    if (format == ImageFormat.DEPTH_POINT_CLOUD) return "DEPTH_POINT_CLOUD";
    if (format == ImageFormat.RAW_SENSOR) return "RAW_SENSOR";
    if (format == ImageFormat.RAW10) return "RAW10";
    if (format == ImageFormat.RAW12) return "RAW12";
    if (format == ImageFormat.YUV_420_888) return "YUV_420_888";
    if (format == ImageFormat.JPEG) return "JPEG";
    if (format == ImageFormat.PRIVATE) return "PRIVATE";
    return String.valueOf(format);
  }
}
