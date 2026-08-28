package com.lvonasek.arcore3dscanner;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stores the last uncaught Java exception in app-private storage.
 * The report is never transmitted unless the user explicitly shares it.
 */
public final class ScannerApplication extends Application {
  private static final String CRASH_DIRECTORY = "crash-reports";
  private static final String CRASH_FILE = "last-crash.txt";

  @Override
  public void onCreate() {
    super.onCreate();
    installCrashHandler();
  }

  public static File getPendingCrashReport(Context context) {
    File report = new File(new File(context.getFilesDir(), CRASH_DIRECTORY), CRASH_FILE);
    return report.isFile() && report.length() > 0 ? report : null;
  }

  public static boolean deletePendingCrashReport(Context context) {
    File report = getPendingCrashReport(context);
    return report == null || report.delete();
  }

  private void installCrashHandler() {
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    AtomicBoolean handlingCrash = new AtomicBoolean(false);
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
      if (handlingCrash.compareAndSet(false, true)) {
        try {
          writeCrashReport(thread, error);
        } catch (Throwable ignored) {
          // A crash reporter must never hide or replace the original exception.
        }
      }

      try {
        if (previous != null) {
          previous.uncaughtException(thread, error);
        }
      } catch (Throwable ignored) {
        // Fall through to the process shutdown if the platform handler fails.
      }
      Process.killProcess(Process.myPid());
      System.exit(10);
    });
  }

  private void writeCrashReport(Thread thread, Throwable error) throws Exception {
    File directory = new File(getFilesDir(), CRASH_DIRECTORY);
    if (!directory.isDirectory() && !directory.mkdirs()) {
      return;
    }

    File report = new File(directory, CRASH_FILE);
    try (FileOutputStream stream = new FileOutputStream(report, false);
         PrintWriter writer = new PrintWriter(
             new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
      SimpleDateFormat format =
          new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
      writer.println("3D Live Scanner MAX local crash report");
      writer.println("Time: " + format.format(new Date()));
      writer.println("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
      writer.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
      writer.println("Thread: " + thread.getName());
      writer.println();
      error.printStackTrace(writer);
      writer.flush();
      stream.getFD().sync();
    }
  }
}
