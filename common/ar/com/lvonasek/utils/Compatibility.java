package com.lvonasek.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.SystemClock;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.huawei.hiar.ARConfigBase;
import com.huawei.hiar.ARSession;
import com.huawei.hiar.ARWorldTrackingConfig;

public class Compatibility {

    private static final String GOOGLE_PLAY_PACKAGE = "com.android.vending";
    private static final String HUAWEI_AR_ENGINE_PACKAGE = "com.huawei.arengine.service";

    /**
     * Reports Camera2 DEPTH_OUTPUT. This can be computational depth and must not
     * be presented as proof of a physical ToF/LiDAR sensor.
     */
    public static boolean hasCamera2DepthOutput(Activity activity) {
        try {
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        int[] ch = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                        if (ch != null) {
                            for (int c : ch) {
                                if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return false;
    }

    /** Kept for Huawei backend compatibility; use the honest name in new UI. */
    @Deprecated
    public static boolean hasToFSensor(Activity activity) {
        return hasCamera2DepthOutput(activity);
    }

    public static boolean isARSupported(Context context) {
        if (context == null) return false;
        ArCoreApk.Availability availability = getArCoreAvailability(context);
        if (availability == ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            // Do not expose scanning merely from the catalogue result. A real,
            // closable Session proves that this runtime can actually start.
            return isARCoreSessionUsable(context);
        }
        if (availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD) {
            return true;
        }
        if (availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED) {
            return true;
        }
        // Some OEM/OS combinations can lag behind ARCore's asynchronous
        // availability catalogue. This is deliberately not a device override:
        // scanning is enabled only if the public Session constructor succeeds.
        if (isARCoreSessionUsable(context)) return true;
        return isHuaweiSessionUsable(context);
    }

    /** A scan entry point must use this stricter runtime check. */
    public static boolean isScanningSessionUsable(Context context) {
        if (context == null) return false;
        return isARCoreSessionUsable(context) || isHuaweiSessionUsable(context);
    }

    /** Whether ARCore can be installed/updated for this device catalogue entry. */
    public static boolean isARCoreInstallRequired(Context context) {
        if (context == null) return false;
        ArCoreApk.Availability availability = getArCoreAvailability(context);
        return availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
                || availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD;
    }

    /** Runtime probe for Huawei AR Engine without starting camera capture. */
    public static boolean isHuaweiSessionUsable(Context context) {
        if (context == null) return false;
        if (!isHuaweiArEngineAvailable(context)) return false;
        try {
            ARSession session = new ARSession(context);
            ARWorldTrackingConfig config = new ARWorldTrackingConfig(session);
            session.configure(config);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isARCoreSupportedAndUpToDate(Activity activity) {
        // Make sure ARCore is installed and supported on this device.
        ArCoreApk.Availability availability = getArCoreAvailability(activity);
        switch (availability) {
            case SUPPORTED_INSTALLED:
                return isARCoreSessionUsable(activity);
            case SUPPORTED_APK_TOO_OLD:
            case SUPPORTED_NOT_INSTALLED:
                try {
                    // Request ARCore installation or update if needed.
                    ArCoreApk.InstallStatus installStatus =
                            ArCoreApk.getInstance().requestInstall(activity, /*userRequestedInstall=*/ true);
                    switch (installStatus) {
                        case INSTALL_REQUESTED:
                            return false;
                        case INSTALLED:
                            return isARCoreSessionUsable(activity);
                    }
                } catch (Throwable e) {
                    return false;
                }
                return false;
            case UNKNOWN_ERROR:
            case UNKNOWN_CHECKING:
            case UNKNOWN_TIMED_OUT:
            case UNSUPPORTED_DEVICE_NOT_CAPABLE:
                // Runtime proof is stronger than a stale/unknown catalogue
                // response, while a failed Session remains a hard stop.
                return isARCoreSessionUsable(activity);
            default:
                return false;
        }
    }

    /**
     * Safe runtime probe using only the public ARCore API. The temporary
     * Session never resumes the camera and is closed on every path.
     */
    public static boolean isARCoreSessionUsable(Context context) {
        if (context == null) return false;
        Session session = null;
        try {
            session = new Session(context);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            closeSession(session);
        }
    }

    private static ArCoreApk.Availability getArCoreAvailability(Context context) {
        if (context == null) return ArCoreApk.Availability.UNKNOWN_ERROR;
        try {
            ArCoreApk.Availability availability =
                    ArCoreApk.getInstance().checkAvailability(context);
            for (int retry = 0;
                 availability == ArCoreApk.Availability.UNKNOWN_CHECKING && retry < 8;
                 retry++) {
                SystemClock.sleep(75);
                availability = ArCoreApk.getInstance().checkAvailability(context);
            }
            return availability;
        } catch (Throwable ignored) {
            return ArCoreApk.Availability.UNKNOWN_ERROR;
        }
    }

    public static boolean isDaydreamSupported(Context context)
    {
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities( mainIntent, 0))
            if (info.activityInfo.packageName.compareTo("com.google.android.vr.home") == 0)
                return true;
        return false;
    }

    public static boolean isGoogleDepthSupported(Activity activity) {
        Session session = null;
        try {
            session = new Session(activity);
            // GOOGLE_SFM configures AUTOMATIC depth in the native backend.
            return session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            closeSession(session);
        }
        return false;
    }

    /** Raw depth may be software-generated; it is not proof of ToF/LiDAR. */
    public static boolean isGoogleRawDepthSupported(Activity activity) {
        Session session = null;
        try {
            session = new Session(activity);
            return session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY);
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            closeSession(session);
        }
        return false;
    }

    public static boolean isGoogleHardwareDepthSupported(Activity activity) {
        Session session = null;
        try {
            session = new Session(activity);
            for (CameraConfig config : session.getSupportedCameraConfigs()) {
                if (config.getDepthSensorUsage() == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE) {
                    return true;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            closeSession(session);
        }
        return false;
    }

    /** Compatibility alias for existing callers. Hardware depth is not always ToF. */
    @Deprecated
    public static boolean isGoogleToFSupported(Activity activity) {
        return isGoogleHardwareDepthSupported(activity);
    }

    private static void closeSession(Session session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Throwable ignored) {
        }
    }

    public static boolean isHuaweiHardwareDepthSupported(Activity activity) {
        if (!isHuaweiArEngineAvailable(activity)) return false;
        //blacklist Huawei Mate 20, Huawei Mate 20 RS, Huawei Mate 20 X
        if (Build.DEVICE.startsWith("HWHMA")) return false;
        if (Build.DEVICE.startsWith("HWLYA")) return false;
        if (Build.DEVICE.startsWith("HWEVR")) return false;
        //blacklist Huawei P20 Pro
        if (Build.DEVICE.startsWith("HW-01K")) return false;
        if (Build.DEVICE.startsWith("HWCLT")) return false;
        //blacklist Huawei P30
        if (Build.DEVICE.startsWith("HWELE")) return false;

        // Huawei depth requires a Camera2 depth-output path. This still does not
        // identify whether the underlying hardware is ToF, stereo or structured light.
        if (!hasCamera2DepthOutput(activity)) return false;

        try {
            ARSession session = new ARSession(activity);
            ARWorldTrackingConfig config = new ARWorldTrackingConfig(session);
            config.setEnableItem(ARConfigBase.ENABLE_DEPTH | ARConfigBase.ENABLE_MESH);
            session.configure(config);
            return session.isSupported(config);
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Compatibility alias for existing callers. Hardware depth is not always ToF. */
    @Deprecated
    public static boolean isHuaweiToFSupported(Activity activity) {
        return isHuaweiHardwareDepthSupported(activity);
    }

    public static boolean isPlayStoreSupported(Context context)
    {
        return isPackageInstalled(context, GOOGLE_PLAY_PACKAGE);
    }

    public static boolean isHuaweiArEngineAvailable(Context context) {
        return isPackageInstalled(context, HUAWEI_AR_ENGINE_PACKAGE);
    }

    @SuppressWarnings("deprecation")
    private static boolean isPackageInstalled(Context context, String packageName) {
        if (context == null) return false;
        try {
            return context.getPackageManager()
                    .getApplicationInfo(packageName, 0).enabled;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean shouldUseHuawei(Activity activity) {
        if (!isHuaweiArEngineAvailable(activity)) return false;
        if (isHuaweiHardwareDepthSupported(activity)) return true;
        return !isPlayStoreSupported(activity) && isHuaweiSessionUsable(activity);
    }
}
