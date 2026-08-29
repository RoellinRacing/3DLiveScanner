package com.lvonasek.arcore3dscanner.diagnostics;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.lvonasek.arcore3dscanner.BuildConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * App-private, user-exportable rolling log. Android logcat is still populated,
 * but this file makes diagnostics available without adb or READ_LOGS.
 */
public final class ScannerLog {
  private static final Object LOCK = new Object();
  private static final long MAX_BYTES = 1024L * 1024L;
  private static final String DIRECTORY = "diagnostics";
  private static final String CURRENT = "scanner-session.log";
  private static final String PREVIOUS = "scanner-session.previous.log";
  private static volatile Context appContext;

  private ScannerLog() {
  }

  public static void initialize(Context context) {
    if (context == null) return;
    appContext = context.getApplicationContext();
    i("APP", "session_start version=" + BuildConfig.VERSION_NAME
        + " sdk=" + Build.VERSION.SDK_INT
        + " device=" + Build.MANUFACTURER + "/" + Build.MODEL
        + " product=" + Build.PRODUCT
        + " fingerprint=" + Build.FINGERPRINT);
  }

  public static void i(String tag, String message) {
    Log.i(tag, message == null ? "" : message);
    append("I", tag, message, null);
  }

  public static void w(String tag, String message) {
    Log.w(tag, message == null ? "" : message);
    append("W", tag, message, null);
  }

  public static void e(String tag, String message, Throwable error) {
    Log.e(tag, message == null ? "" : message, error);
    append("E", tag, message, error);
  }

  public static String readAll(Context context) {
    synchronized (LOCK) {
      File directory = directory(context);
      StringBuilder output = new StringBuilder();
      appendFile(output, new File(directory, PREVIOUS));
      appendFile(output, new File(directory, CURRENT));
      return output.length() == 0 ? "No scanner log entries yet." : output.toString();
    }
  }

  public static File createExport(Context context) throws Exception {
    String content = readAll(context);
    File directory = new File(context.getCacheDir(), "exports");
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new IllegalStateException("Unable to create log export directory");
    }
    File output = new File(directory, "3d-live-scanner-diagnostics.txt");
    try (FileOutputStream stream = new FileOutputStream(output, false);
         OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
      writer.write("3D Live Scanner MAX diagnostics\n");
      writer.write("Version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n");
      writer.write("Android: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n");
      writer.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " / " + Build.PRODUCT + "\n");
      writer.write("Fingerprint: " + Build.FINGERPRINT + "\n\n");
      writer.write(content);
      writer.flush();
      stream.getFD().sync();
    }
    return output;
  }

  public static void clear(Context context) {
    synchronized (LOCK) {
      File directory = directory(context);
      new File(directory, CURRENT).delete();
      new File(directory, PREVIOUS).delete();
    }
    i("APP", "log_cleared_by_user");
  }

  private static void append(String level, String tag, String message, Throwable error) {
    Context context = appContext;
    if (context == null) return;
    synchronized (LOCK) {
      try {
        File directory = directory(context);
        File current = new File(directory, CURRENT);
        if (current.length() >= MAX_BYTES) {
          File previous = new File(directory, PREVIOUS);
          previous.delete();
          current.renameTo(previous);
        }
        try (FileOutputStream stream = new FileOutputStream(current, true);
             PrintWriter writer = new PrintWriter(
                 new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
          String timestamp = new SimpleDateFormat(
              "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
          writer.print(timestamp);
          writer.print(" ");
          writer.print(level);
          writer.print("/");
          writer.print(tag == null ? "SCANNER" : tag);
          writer.print(" [");
          writer.print(Thread.currentThread().getName());
          writer.print("] ");
          writer.println(message == null ? "" : message.replace('\n', ' '));
          if (error != null) error.printStackTrace(writer);
        }
      } catch (Throwable ignored) {
        // Diagnostics must never destabilize scanning.
      }
    }
  }

  private static File directory(Context context) {
    Context actual = context == null ? appContext : context.getApplicationContext();
    if (actual == null) throw new IllegalStateException("ScannerLog is not initialized");
    File directory = new File(actual.getFilesDir(), DIRECTORY);
    if (!directory.isDirectory()) directory.mkdirs();
    return directory;
  }

  private static void appendFile(StringBuilder output, File file) {
    if (!file.isFile()) return;
    if (output.length() > 0) output.append("\n--- log rotation ---\n");
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        new FileInputStream(file), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) output.append(line).append('\n');
    } catch (Throwable error) {
      output.append("Unable to read ").append(file.getName()).append(": ")
          .append(error.getMessage()).append('\n');
    }
  }
}
