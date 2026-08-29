package com.lvonasek.arcore3dscanner.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.lvonasek.arcore3dscanner.R;
import com.lvonasek.arcore3dscanner.ScannerApplication;
import com.lvonasek.arcore3dscanner.diagnostics.ScannerLog;
import com.lvonasek.arcore3dscanner.main.DeviceCapabilities;
import com.lvonasek.arcore3dscanner.main.Exporter;
import com.lvonasek.arcore3dscanner.main.Main;
import com.lvonasek.utils.Compatibility;
import com.lvonasek.utils.IO;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class FileManager extends AbstractActivity implements View.OnClickListener {
  private FileAdapter mAdapter;
  private GridView mList;
  private Button mAdd;
  private Button mCancel;
  private CheckBox mCheckbox;
  private ProgressBar mProgress;
  private TextView mText;
  private RelativeLayout mHeader;
  private LinearLayout mOptions;
  private TextView mName;
  private View mPosition;
  private View mRename;
  private View mShare;
  private static boolean allowedToAskForPermissions = true;

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    Initializator.rememberFileIntent(getIntent());
    setContentView(R.layout.activity_files);

    boolean showPro = Compatibility.isPlayStoreSupported(this) && !isProVersion(this);
    findViewById(R.id.settings).setOnClickListener(this);
    findViewById(R.id.logs).setOnClickListener(this);
    ScannerLog.i(TAG, "project_browser_create");

    mName = findViewById(R.id.name);
    mRename = findViewById(R.id.rename);
    mPosition = findViewById(R.id.position);
    mShare = findViewById(R.id.share);
    mHeader = findViewById(R.id.header);
    mOptions = findViewById(R.id.options);
    mPosition.setOnClickListener(this);
    mRename.setOnClickListener(this);
    mShare.setOnClickListener(this);
    findViewById(R.id.delete).setOnClickListener(this);

    mAdd = findViewById(R.id.add_button);
    mCancel = findViewById(R.id.service_cancel);
    mCheckbox = findViewById(R.id.checkbox);
    mList = findViewById(R.id.list);
    mText = findViewById(R.id.info_text);
    mProgress = findViewById(R.id.progressBar);
    mAdd.setOnClickListener(this);
    mCancel.setOnClickListener(this);

    int columns = 3;
    SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
    columns = pref.getInt(getString(R.string.pref_layout), columns);

    mAdapter = new FileAdapter(this, columns);
    mList.setOnTouchListener((view, event) -> {
      mAdapter.forwardTouch(event);
      return false;
    });
    showPendingCrashReport();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    Initializator.rememberFileIntent(intent);
  }

  @Override
  public void onBackPressed()
  {
    if (mProgress.getVisibility() == View.VISIBLE) {
      System.exit(0);
    } else if (mAdapter.hasParent()) {
      mAdapter.toParent();
    } else if (mAdapter.getSelected() != null) {
      mAdapter.update();
    } else {
      moveTaskToBack(true);
    }
  }

  @Override
  public int getNavigationBarColor() {
        return Color.BLACK;
    }

  @Override
  public int getStatusBarColor() {
    return getColor(R.color.scanner_background);
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    mAdd.setVisibility(View.VISIBLE);
    mCancel.setVisibility(View.GONE);
    mProgress.setVisibility(View.GONE);

    int service = Service.getRunning(this);
    if (service > Service.SERVICE_NOT_RUNNING) {
      mAdd.setVisibility(View.GONE);
      mCancel.setVisibility(View.VISIBLE);
      mList.setVisibility(View.GONE);
      mText.setVisibility(View.VISIBLE);
      mText.setText("");
      new Thread(() -> {
        while(true) {
          try
          {
            Thread.sleep(1000);
          } catch (Exception e)
          {
            e.printStackTrace();
          }
          FileManager.this.runOnUiThread(() -> {
            if (Service.getMessage() == null)
              mText.setText(getString(R.string.failed));
            else
              mText.setText(getString(R.string.working) + "\n\n" + Service.getMessage());
          });
        }
      }).start();
    } else if (Service.getRunning(this) < Service.SERVICE_NOT_RUNNING)
    {
      service = Math.abs(Service.getRunning(this));
      mAdd.setVisibility(View.GONE);
      if (service != Service.SERVICE_SAVE) {
        mCancel.setVisibility(View.VISIBLE);
        mList.setVisibility(View.GONE);
        mText.setVisibility(View.VISIBLE);
      }
      boolean paused = service == Service.SERVICE_SAVE;
      int text = paused ? R.string.paused : R.string.finished;
      mText.setText(getString(text) + "\n" + getString(R.string.turn_off));
      if (service == Service.SERVICE_SAVE) {
        showProgress();
        startActivity(new Intent(this, Main.class));
      } else if ((service == Service.SERVICE_POSTPROCESS) || (service == Service.SERVICE_PHOTOGRAMMETRY)) {
        finishScanning();
      } else if (service == Service.SERVICE_SKETCHFAB) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(Service.getLink(this)));
        startActivity(intent);
        Service.forceState(this, null, Service.SERVICE_NOT_RUNNING);
      }
    } else
      setupPermissions();
  }

  public void refreshUI()
  {
    String link = "https://lvonasek.github.io/policy-3dls.html";
    String info = getString(R.string.info).replaceAll("\n", "<br>");
    info = info.replaceAll("#BEGIN#", "<a href=" + link + ">").replaceAll("#END#", "</a>");
    info = info.replaceAll("\"Models\"", "\"Documents\\\\3D Live Scanner\"");

    AlertDialog d;
    String policyKey = "KEY_POLICY_ACCEPTED";
    SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
    if (!pref.getBoolean(policyKey, false) && !Compatibility.isPlayStoreSupported(this)) {
      mAdd.setVisibility(View.GONE);
      mCancel.setVisibility(View.VISIBLE);
      mCheckbox.setVisibility(View.VISIBLE);
      mCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> mCancel.setBackgroundResource(isChecked ? R.drawable.background_button : R.drawable.background_button_selected));
      mList.setVisibility(View.GONE);
      mCancel.setBackgroundResource(R.drawable.background_button_selected);
      mCancel.setText(android.R.string.ok);
      mCancel.setOnClickListener(view -> {
        if (mCheckbox.isChecked()) {
          SharedPreferences.Editor e = pref.edit();
          e.putBoolean(policyKey, true);
          e.apply();
          refreshUI();
        }
      });
      mText.setText(Html.fromHtml(info, Html.FROM_HTML_MODE_LEGACY));
      mText.setOnClickListener(v -> openURL(FileManager.this, link));
      return;
    } else if (Initializator.isFirst() && Compatibility.isPlayStoreSupported(this)) {
      LayoutInflater inflater = (LayoutInflater)getSystemService(Context.LAYOUT_INFLATER_SERVICE);
      View view = inflater.inflate(R.layout.dialog_start, null);

      TextView text = view.findViewById(R.id.info);
      text.setText(Html.fromHtml(info, Html.FROM_HTML_MODE_LEGACY));
      text.setOnClickListener(v -> openURL(FileManager.this, link));

      AlertDialog.Builder dialog = new AlertDialog.Builder(this);
      dialog.setView(view);

      d = dialog.create();
      d.show();
      if (d.getWindow() != null) {
        d.getWindow().setBackgroundDrawable(getDrawable(R.drawable.background_dialog));
      }

      // Depth support is probed only when Scan is requested. Some uncertified
      // OEM ARCore runtimes are not safe to construct during launcher startup.
    }

    long time = System.currentTimeMillis();
    boolean migrate = hasFilesToMigrate(this);
    if (migrate) {
      Log.d(TAG, "Some files has to be migrated");
    }
    mCancel.setVisibility(View.GONE);
    mCheckbox.setVisibility(View.GONE);
    mList.setVisibility(View.VISIBLE);
    mText.setOnClickListener(null);
    mText.setText(migrate ? R.string.migrating_data : R.string.wait);
    mText.setVisibility(mAdapter.isEmpty() ? View.VISIBLE : View.GONE);
    new Thread(() -> {

      //update file structure
      Exporter.makeStructure(getPath(migrate));

      //get list of files
      runOnUiThread(() -> {
        mAdapter.update();
        Log.d(TAG, "Listing files took " + (System.currentTimeMillis() - time) + "ms");

        mText.setText(R.string.no_data);
        mText.setVisibility(mAdapter.getCount() == 0 ? View.VISIBLE : View.GONE);
        mList.setAdapter(mAdapter);
        mAdd.setVisibility(View.VISIBLE);
        mProgress.setVisibility(View.GONE);

        mAdapter.notifyDataSetChanged();
        if (mAdapter.getCount() > 0) {
          mList.setSelection(0);
        }
      });
    }).start();
  }

  protected void setupPermissions() {
    String[] permissions = {
            Manifest.permission.CAMERA
    };

    boolean ok = true;
    for (String s : permissions)
      if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED)
        ok = false;

    if (!allowedToAskForPermissions && !ok) {
      mAdd.setVisibility(View.GONE);
      mCancel.setVisibility(View.VISIBLE);
      mList.setVisibility(View.GONE);
      mCancel.setText(android.R.string.ok);
      mCancel.setOnClickListener(view -> {
        allowedToAskForPermissions = true;
        setupPermissions();
      });
      mText.setText(R.string.permissions_required);
      return;
    } else {
      mAdd.setVisibility(View.VISIBLE);
      mList.setVisibility(View.VISIBLE);
      mCancel.setText(android.R.string.cancel);
      mCancel.setOnClickListener(this);
      mCancel.setVisibility(View.GONE);
      allowedToAskForPermissions = false;
    }

    long timestamp = System.currentTimeMillis();
    onPermissionFail = () -> {
      if (System.currentTimeMillis() - timestamp < 100) {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getPackageName(), null);
        intent.setData(uri);
        startActivity(intent);
      }
    };
    onPermissionSuccess = () -> {
      if (Initializator.hasFileIntent()) {
        showProgress();

        new Thread(() -> {

          int index = 0;
          File path;
          do {
            index++;
            path = new File(getPath(false), "Import_" + index + ".obj");
          } while (path.exists());
          path.mkdirs();

          boolean success = IO.unzip(path.getAbsolutePath() + "/", Initializator.getFile(FileManager.this));
          File finalPath = path;
          runOnUiThread(() -> {
            if (success) {
              Intent intent = new Intent(FileManager.this, Main.class);
              intent.putExtra(AbstractActivity.FILE_KEY, finalPath.getAbsolutePath());
              startActivity(intent);
            } else {
              refreshUI();
            }
          });
        }).start();
      } else {
        refreshUI();
      }
    };
    askForPermissions(permissions);
  }

  public void showProgress()
  {
    try {
      mAdd.setVisibility(View.GONE);
      mProgress.setVisibility(View.VISIBLE);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void onClick(View v) {
    int id = v.getId();

    if (id == R.id.delete) {
      mAdapter.deleteModel();
    } else if (id == R.id.position) {
      mAdapter.showPosition();
    } else if (id == R.id.rename) {
      mAdapter.rename();
    } else if (id == R.id.share) {
      mAdapter.shareModel();
    } else if (id == R.id.add_button) {
      startScanning();
    } else if (id == R.id.service_cancel) {
      Service.reset(this);
      System.exit(0);
    } else if (id == R.id.settings) {
      startActivity(new Intent(this, Settings.class));
    } else if (id == R.id.logs) {
      showLogConsole();
    }
  }


  private void startScanning()
  {
    ScannerLog.i(TAG, "scan_mode_dialog_opened");
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setView(R.layout.dialog_scan);
    Dialog dialog = builder.create();
    dialog.show();
    if (dialog.getWindow() != null) {
      dialog.getWindow().setBackgroundDrawable(getDrawable(R.drawable.background_dialog));
    }

    ArrayList<Drawable> icons = new ArrayList<>();
    ArrayList<String> values = new ArrayList<>();
    ArrayList<String> descriptions = new ArrayList<>();
    ArrayList<String> modes = new ArrayList<>();
    icons.add(getDrawable(R.drawable.ic_type_scan));
    values.add(getString(R.string.mode_realtime));
    descriptions.add(getString(R.string.mode_realtime_description));
    modes.add("realtime");
    icons.add(getDrawable(R.drawable.ic_type_dataset));
    values.add(getString(R.string.mode_dataset));
    descriptions.add(getString(R.string.mode_dataset_description));
    modes.add("dataset");
    icons.add(getDrawable(R.drawable.ic_type_photogrammetry));
    values.add(getString(R.string.mode_photogrammetry));
    descriptions.add(getString(R.string.mode_photogrammetry_description));
    modes.add("photogrammetry");

    ArrayAdapterWithIcons adapter = new ArrayAdapterWithIcons(
        this, values, icons, descriptions);
    GridView list = dialog.findViewById(R.id.list);
    list.setAdapter(adapter);
    list.setOnTouchListener((v, event) -> event.getAction() == MotionEvent.ACTION_MOVE);
    list.setOnItemClickListener((adapterView, view, index, l) -> {
      dialog.dismiss();
      String mode = modes.get(index);
      ScannerLog.i(TAG, "scan_mode_selected mode=" + mode);
      if ("photogrammetry".equals(mode)) {
        startActivity(new Intent(FileManager.this, PhotoDatasetActivity.class));
      } else {
        requestNativeScan("dataset".equals(mode));
      }
    });
  }

  private void requestNativeScan(boolean datasetCapture) {
    SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
    Runnable start = () -> startNativeScanning(datasetCapture);
    if (pref.getBoolean(getString(R.string.pref_gps), false)) {
      String[] permissions = {
          Manifest.permission.ACCESS_COARSE_LOCATION,
          Manifest.permission.ACCESS_FINE_LOCATION
      };
      onPermissionSuccess = start;
      askForPermissions(permissions);
    } else {
      start.run();
    }
  }

  private void startNativeScanning(boolean datasetCapture) {
    ScannerLog.i(TAG, "native_scan_probe_started dataset_capture=" + datasetCapture);
    boolean runtimeUsable = false;
    try {
      runtimeUsable = Compatibility.isScanningSessionUsable(this);
    } catch (Throwable error) {
      ScannerLog.e(TAG, "ar_runtime_probe_failed", error);
    }
    if (!runtimeUsable) {
      // Installation/update UI is allowed only after the user explicitly asks
      // to scan. Creating ARCore/Huawei sessions while the launcher is coming
      // up caused fragile OEM runtimes to terminate the process before the
      // project screen could even be shown.
      try {
        if (!Compatibility.isHuaweiArEngineAvailable(this)
            && Compatibility.isARCoreInstallRequired(this)) {
          if (ArCoreApk.getInstance().requestInstall(this, true)
              != ArCoreApk.InstallStatus.INSTALLED) {
            return;
          }
          runtimeUsable = Compatibility.isARCoreReady(this);
        }
      } catch (Throwable error) {
        ScannerLog.e(TAG, "ar_runtime_install_failed", error);
      }
    }
    if (!runtimeUsable) {
      ScannerLog.w(TAG, "native_scan_unavailable dataset_capture=" + datasetCapture);
      showScanningUnavailable();
      return;
    }
    SharedPreferences.Editor editor = PreferenceManager
        .getDefaultSharedPreferences(this).edit();
    editor.putBoolean(getString(R.string.pref_later), datasetCapture);
    editor.putString(getString(R.string.pref_mode), "realtime");
    if (datasetCapture) editor.putBoolean(getString(R.string.pref_fullhd), true);
    editor.apply();
    ScannerLog.i(TAG, "native_scan_launch dataset_capture=" + datasetCapture);
    showProgress();
    startActivity(new Intent(FileManager.this, Main.class));
  }

  private void showLogConsole() {
    String logs = ScannerLog.readAll(this);
    String preview = logs.length() > 12000
        ? "…\n" + logs.substring(logs.length() - 12000) : logs;
    TextView view = new TextView(this);
    int padding = Math.round(18 * getResources().getDisplayMetrics().density);
    view.setPadding(padding, padding, padding, padding);
    view.setText(preview);
    view.setTextColor(getColor(R.color.scanner_text_secondary));
    view.setTextSize(12);
    view.setTextIsSelectable(true);
    view.setTypeface(android.graphics.Typeface.MONOSPACE);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(view);
    new AlertDialog.Builder(this)
        .setTitle(R.string.logs_title)
        .setMessage(R.string.logs_message)
        .setView(scroll)
        .setPositiveButton(R.string.logs_copy, (dialog, which) -> copyLogs())
        .setNeutralButton(R.string.logs_share, (dialog, which) -> shareLogs())
        .setNegativeButton(R.string.action_close, null)
        .show();
  }

  private void copyLogs() {
    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    if (clipboard == null) return;
    clipboard.setPrimaryClip(ClipData.newPlainText(
        "3D Live Scanner MAX diagnostics", ScannerLog.readAll(this)));
    ScannerLog.i(TAG, "logs_copied_to_clipboard");
    Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show();
  }

  private void shareLogs() {
    try {
      File report = ScannerLog.createExport(this);
      Uri uri = FileProvider.getUriForFile(
          this, getPackageName() + ".provider", report);
      Intent intent = new Intent(Intent.ACTION_SEND);
      intent.setType("text/plain");
      intent.putExtra(Intent.EXTRA_STREAM, uri);
      intent.setClipData(ClipData.newRawUri("scanner diagnostics", uri));
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(intent, getString(R.string.logs_share)));
    } catch (Throwable error) {
      ScannerLog.e(TAG, "log_share_failed", error);
      Toast.makeText(this, R.string.diagnostics_failed, Toast.LENGTH_LONG).show();
    }
  }

  private void showScanningUnavailable() {
    new AlertDialog.Builder(this)
        .setTitle(R.string.photo_scan_fallback_title)
        .setMessage(R.string.photo_scan_fallback_message)
        .setPositiveButton(R.string.photo_scan_fallback_start,
            (dialog, which) -> startActivity(
                new Intent(FileManager.this, PhotoDatasetActivity.class)))
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(R.string.diagnostics_create_share,
            (dialog, which) -> createAndShareDiagnostics())
        .show();
  }

  private void createAndShareDiagnostics() {
    ScannerLog.i(TAG, "device_diagnostics_export_requested");
    new Thread(() -> {
      try {
        DeviceCapabilities.ArCoreInfo arCore =
            DeviceCapabilities.probeArCore(FileManager.this);
        DeviceCapabilities capabilities =
            new DeviceCapabilities(FileManager.this, arCore);
        File report = capabilities.exportReport();
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          try {
            capabilities.shareReport(FileManager.this, report);
          } catch (Throwable error) {
            showDiagnosticsError(error);
          }
        });
      } catch (Throwable error) {
        runOnUiThread(() -> {
          if (!isFinishing() && !isDestroyed()) showDiagnosticsError(error);
        });
      }
    }, "device-diagnostics").start();
  }

  private void showDiagnosticsError(Throwable error) {
    Log.e(TAG, "Unable to create or share device diagnostics", error);
    Toast.makeText(this, R.string.diagnostics_failed, Toast.LENGTH_LONG).show();
  }

  private void showPendingCrashReport() {
    File report = ScannerApplication.getPendingCrashReport(this);
    if (report == null) return;

    new AlertDialog.Builder(this)
        .setTitle(R.string.crash_report_title)
        .setMessage(R.string.crash_report_message)
        .setPositiveButton(R.string.crash_report_share,
            (dialog, which) -> shareCrashReport(report))
        .setNegativeButton(R.string.crash_report_delete, (dialog, which) -> {
          if (!ScannerApplication.deletePendingCrashReport(FileManager.this)) {
            Toast.makeText(FileManager.this, R.string.crash_report_delete_failed,
                Toast.LENGTH_LONG).show();
          }
        })
        .setNeutralButton(android.R.string.cancel, null)
        .show();
  }

  private void shareCrashReport(File report) {
    try {
      Uri uri = FileProvider.getUriForFile(
          this, getPackageName() + ".provider", report);
      Intent intent = new Intent(Intent.ACTION_SEND);
      intent.setType("text/plain");
      intent.putExtra(Intent.EXTRA_STREAM, uri);
      intent.setClipData(ClipData.newRawUri("crash report", uri));
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(intent,
          getString(R.string.crash_report_share_chooser)));
    } catch (Throwable error) {
      Log.e(TAG, "Unable to share crash report", error);
      Toast.makeText(this, R.string.crash_report_share_failed, Toast.LENGTH_LONG).show();
    }
  }

  private void finishScanning()
  {
    mCancel.setVisibility(View.GONE);
    showProgress();
    Date date = new Date() ;
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    final String filename = dateFormat.format(date);
    String text = getString(R.string.data_saved) + " " + filename;
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();

    new Thread(() -> {
      File file = new File(Service.getLink(FileManager.this));
      File file2save = Exporter.export(file, filename);

      //remove temp dir
      if (!isPostProcessLaterOn(FileManager.this))
        deleteRecursive(new File(file.getParent()));

      //finish
      Service.reset(FileManager.this);
      Intent intent = new Intent(FileManager.this, Main.class);
      intent.putExtra(FILE_KEY, file2save.getAbsolutePath());
      showProgress();
      startActivity(intent);
    }).start();
  }

  public void setColumns(int count) {
    mList.setNumColumns(count);

    SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(this).edit();
    e.putInt(getString(R.string.pref_layout), count);
    e.commit();
  }

  public void setOptions(int size) {
    boolean on = size > 0;
    mHeader.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
    mOptions.setVisibility(on ? View.VISIBLE : View.GONE);

    if (on) {
      mName.setText(mAdapter.getSelected());
    }

    boolean more = size > 1;
    boolean ext = mAdapter.hasExtension();
    mPosition.setVisibility(!more && mAdapter.hasPosition() ? View.VISIBLE : View.GONE);
    mRename.setVisibility(!more ? View.VISIBLE : View.GONE);
    mShare.setVisibility(ext && !more ? View.VISIBLE : View.GONE);

    int background = Color.argb(128, 0, 153, 204);
    setWindow(on ? background : getStatusBarColor(), getNavigationBarColor());
  }
}
