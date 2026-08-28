package com.lvonasek.arcore3dscanner.main;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lvonasek.arcore3dscanner.ui.AbstractActivity;

public class Indicators implements Runnable {

  private final ActivityManager activityManager;
  private final ActivityManager.MemoryInfo memoryInfo;
  private final AbstractActivity main;
  private final DeviceCapabilities capabilities;
  private final LinearLayout layoutInfo;
  private final TextView infoLeft;
  private final TextView infoRight;
  private final TextView infoLog;
  private final View battery;
  private final View statusDot;
  private final Thread worker;
  private final Application.ActivityLifecycleCallbacks lifecycleCallbacks;

  private volatile String overrideMessage;
  private volatile boolean running = true;

  public Indicators(AbstractActivity main) {
    this(main, new DeviceCapabilities(main, DeviceCapabilities.probeArCore(main)));
  }

  public Indicators(AbstractActivity main, DeviceCapabilities capabilities) {
    this.main = main;
    this.capabilities = capabilities;
    layoutInfo = main.findViewById(com.lvonasek.arcore3dscanner.R.id.layout_info);
    infoLeft = main.findViewById(com.lvonasek.arcore3dscanner.R.id.info_left);
    infoRight = main.findViewById(com.lvonasek.arcore3dscanner.R.id.info_right);
    infoLog = main.findViewById(com.lvonasek.arcore3dscanner.R.id.infolog);
    battery = main.findViewById(com.lvonasek.arcore3dscanner.R.id.info_battery);
    statusDot = main.findViewById(com.lvonasek.arcore3dscanner.R.id.scan_status_dot);
    activityManager = (ActivityManager) main.getSystemService(Activity.ACTIVITY_SERVICE);
    memoryInfo = new ActivityManager.MemoryInfo();
    lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
      @Override public void onActivityCreated(Activity activity, Bundle state) { }
      @Override public void onActivityStarted(Activity activity) { }
      @Override public void onActivityResumed(Activity activity) { }
      @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
      @Override public void onActivityStopped(Activity activity) { }
      @Override public void onActivityDestroyed(Activity activity) {
        if (activity == main) disable();
      }
      @Override public void onActivityPaused(Activity activity) {
        if (activity == main) disable();
      }
    };
    main.getApplication().registerActivityLifecycleCallbacks(lifecycleCallbacks);
    capabilities.start();
    layoutInfo.setVisibility(View.VISIBLE);
    worker = new Thread(this, "scan-indicators");
    worker.start();
  }

  public void disable() {
    if (!running) return;
    running = false;
    worker.interrupt();
    capabilities.stop();
    main.getApplication().unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
    layoutInfo.setVisibility(View.GONE);
    infoLog.setVisibility(View.GONE);
  }

  public void setOverrideMessage(String message) {
    overrideMessage = message;
    main.runOnUiThread(() -> updateText("", null));
  }

  public static int getBatteryPercentage(Context context) {
    Intent status = context.registerReceiver(null,
        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    if (status == null) return -1;
    int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
    if (level < 0 || scale <= 0) return -1;
    return Math.round(100f * level / scale);
  }

  @Override
  public void run() {
    while (running) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ignored) {
      }
      if (!running || main.isFinishing() || main.isDestroyed()) break;
      final String rawEvent = JNI.getRawEvent();
      final String nativeMessage = JNI.localizeEvent(rawEvent, main.getResources());
      final DeviceCapabilities.CoachState coach =
          capabilities == null ? null : capabilities.evaluate(rawEvent);
      main.runOnUiThread(() -> update(nativeMessage, coach));
    }
  }

  private void update(String nativeMessage, DeviceCapabilities.CoachState coach) {
    if (!running) return;
    activityManager.getMemoryInfo(memoryInfo);
    long freeMb = memoryInfo.availMem / 1048576L;
    infoLeft.setText(freeMb + " MB");
    infoLeft.setTextColor(freeMb < 400 ? Color.RED : Color.WHITE);

    int percent = getBatteryPercentage(main);
    infoRight.setText(percent < 0 ? "—" : percent + "%");
    int icon = com.lvonasek.arcore3dscanner.R.drawable.ic_battery_0;
    if (percent > 10) icon = com.lvonasek.arcore3dscanner.R.drawable.ic_battery_20;
    if (percent > 30) icon = com.lvonasek.arcore3dscanner.R.drawable.ic_battery_40;
    if (percent > 50) icon = com.lvonasek.arcore3dscanner.R.drawable.ic_battery_60;
    if (percent > 70) icon = com.lvonasek.arcore3dscanner.R.drawable.ic_battery_80;
    if (percent > 90) icon = com.lvonasek.arcore3dscanner.R.drawable.ic_battery_100;
    battery.setBackgroundResource(icon);
    infoRight.setTextColor(percent >= 0 && percent < 15 ? Color.RED : Color.WHITE);

    if (coach == null || coach.level == DeviceCapabilities.Level.GOOD) {
      statusDot.setBackgroundResource(
          com.lvonasek.arcore3dscanner.R.drawable.scanner_status_good);
    } else if (coach.level == DeviceCapabilities.Level.WARNING) {
      statusDot.setBackgroundResource(
          com.lvonasek.arcore3dscanner.R.drawable.scanner_status_warning);
    } else {
      statusDot.setBackgroundResource(
          com.lvonasek.arcore3dscanner.R.drawable.scanner_status_error);
    }
    updateText(nativeMessage, coach);
  }

  private void updateText(String nativeMessage, DeviceCapabilities.CoachState coach) {
    String text = overrideMessage;
    if (text == null || text.length() == 0) {
      text = nativeMessage == null ? "" : nativeMessage;
      if (coach != null && coach.message.length() > 0) {
        boolean independentCritical = coach.level == DeviceCapabilities.Level.CRITICAL
            && "tracking".equals(coach.tracking);
        if (text.length() == 0) {
          text = coach.message;
        } else if (independentCritical) {
          text = coach.message + "\n" + text;
        }
      }
    }
    infoLog.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
    infoLog.setText(text);
  }
}
