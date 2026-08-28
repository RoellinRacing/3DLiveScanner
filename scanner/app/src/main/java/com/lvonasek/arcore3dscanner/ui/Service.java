package com.lvonasek.arcore3dscanner.ui;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.lvonasek.arcore3dscanner.R;
import com.lvonasek.arcore3dscanner.main.JNI;

public class Service extends android.app.Service
{
  public static final String SERVICE_LINK = "service_link";
  public static final String SERVICE_RUNNING = "service_running";

  public static final int SERVICE_NOT_RUNNING = 0;
  public static final int SERVICE_POSTPROCESS = 1;
  public static final int SERVICE_SAVE = 2;
  public static final int SERVICE_SKETCHFAB = 3;
  public static final int SERVICE_PHOTOGRAMMETRY = 4;

  private static final String NOTIFICATION_CHANNEL = "scanner_processing";
  private static final int NOTIFICATION_ID = 0x3D5CA;
  private static final int NOTIFICATION_PERMISSION_REQUEST = 0x3D5C;

  private static Runnable action;
  private static String message;
  private static String messageNotification;
  private static AbstractActivity parent;
  private static boolean running;
  private static Service service;

  @Override
  public synchronized void onCreate() {
    super.onCreate();
    service = this;
    message = "";
    startInForeground();
    if ((parent == null) || (action == null)) {
      Log.w(Service.class.getSimpleName(), "Discarding a stale service restart");
      PreferenceManager.getDefaultSharedPreferences(this).edit()
        .putInt(SERVICE_RUNNING, SERVICE_NOT_RUNNING)
        .putString(SERVICE_LINK, "")
        .apply();
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
      return;
    }
    if ((getRunning(parent) == SERVICE_POSTPROCESS) || (getRunning(parent) == SERVICE_SAVE)) {
      running = true;
      new Thread(() -> {
        while(running) {
          setMessage(JNI.getEvent(Service.this.getResources()));
          try
          {
            Thread.sleep(1000);
          } catch (Exception e)
          {
            e.printStackTrace();
          }
        }
        message = "";
      }).start();
    }
    Runnable pendingAction = action;
    new Thread(pendingAction, "ScannerProcessing").start();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId)
  {
    updateForegroundNotification();
    // Work is kept in memory by process(); after a process death it cannot be
    // reconstructed safely, so do not let Android create a zombie service.
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy()
  {
    running = false;
    if (service == this)
      service = null;
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  @TargetApi(35)
  public void onTimeout(int startId, int foregroundServiceType)
  {
    Log.e(Service.class.getSimpleName(), "Foreground dataSync time limit reached");
    running = false;
    PreferenceManager.getDefaultSharedPreferences(this).edit()
      .putInt(SERVICE_RUNNING, SERVICE_NOT_RUNNING)
      .putString(SERVICE_LINK, "")
      .apply();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public IBinder onBind(Intent intent)
  {
    return null;
  }

  public static synchronized void finish(String link)
  {
    running = false;
    stopCurrentService();
    SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(parent).edit();
    e.putInt(SERVICE_RUNNING, -Math.abs(getRunning(parent)));
    e.putString(SERVICE_LINK, link);
    e.commit();
    System.exit(0);
  }

  public static synchronized void forceState(AbstractActivity activity, String link, int state)
  {
    running = false;
    stopCurrentService();
    SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(activity).edit();
    e.putInt(SERVICE_RUNNING, -Math.abs(state));
    e.putString(SERVICE_LINK, link);
    e.commit();
    System.exit(0);
  }

  public static synchronized void interrupt() {
    messageNotification = null;
    message = null;
  }

  public static synchronized void process(String message, int serviceId, AbstractActivity activity, Runnable runnable)
  {
    action = runnable;
    parent = activity;
    messageNotification = message;

    SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(activity).edit();
    e.putInt(SERVICE_RUNNING, serviceId);
    e.putString(SERVICE_LINK, "");
    e.commit();
    Intent serviceIntent = new Intent(activity, Service.class);
    activity.runOnUiThread(() -> {
      if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        && (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED)) {
        activity.requestPermissions(
          new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        activity.startForegroundService(serviceIntent);
      else
        activity.startService(serviceIntent);
    });
  }

  public static synchronized String getLink(Context context)
  {
    SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
    return pref.getString(SERVICE_LINK, "");
  }

  public static synchronized String getMessage()
  {
    if (messageNotification == null)
      return null;
    if (message == null)
      return null;
    return messageNotification + "\n" + message;
  }

  public static synchronized int getRunning(Context context)
  {
    SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
    return pref.getInt(SERVICE_RUNNING, SERVICE_NOT_RUNNING);
  }

  private static synchronized void setMessage(String msg)
  {
    message = msg;
    if (service != null)
      service.updateForegroundNotification();
  }

  public static synchronized void setMessageNotification(String msg)
  {
    messageNotification = msg;
    if (service != null)
      service.updateForegroundNotification();
  }

  public static synchronized void reset(Context context)
  {
    stopCurrentService();
    SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(context).edit();
    e.putInt(SERVICE_RUNNING, SERVICE_NOT_RUNNING);
    e.putString(SERVICE_LINK, "");
    e.commit();
  }

  private Notification buildNotification()
  {
    NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
        NOTIFICATION_CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
      channel.setDescription(getString(R.string.app_name));
      manager.createNotificationChannel(channel);
    }

    Intent openIntent = new Intent(this, Initializator.class)
      .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String text = getMessage();
    if ((text == null) || text.trim().isEmpty())
      text = getString(R.string.app_name);

    Notification.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
      builder = new Notification.Builder(this, NOTIFICATION_CHANNEL);
    else
      builder = new Notification.Builder(this);
    return builder
      .setSmallIcon(R.drawable.ic_launcher)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(text)
      .setStyle(new Notification.BigTextStyle().bigText(text))
      .setContentIntent(contentIntent)
      .setCategory(Notification.CATEGORY_PROGRESS)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .build();
  }

  private void startInForeground()
  {
    Notification notification = buildNotification();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else
      startForeground(NOTIFICATION_ID, notification);
  }

  private void updateForegroundNotification()
  {
    if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
      && (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED))
      return;
    NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    manager.notify(NOTIFICATION_ID, buildNotification());
  }

  private static void stopCurrentService()
  {
    Service current = service;
    if (current == null)
      return;
    current.stopForeground(STOP_FOREGROUND_REMOVE);
    current.stopSelf();
  }
}
