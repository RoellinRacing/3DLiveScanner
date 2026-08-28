package com.lvonasek.arcore3dscanner.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.lvonasek.arcore3dscanner.R;
import com.lvonasek.arcore3dscanner.main.DeviceCapabilities;
import com.lvonasek.utils.Compatibility;

public class Settings extends PreferenceActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setTheme(android.R.style.Theme_Material_NoActionBar_Fullscreen);
    overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.fade_out);
    addPreferencesFromResource(R.xml.settings);
  }

  @Override
  protected void onPause() {
    super.onPause();
    overridePendingTransition(android.R.anim.fade_in, R.anim.slide_out_left);
  }

  @Override
  protected void onResume() {
    super.onResume();
    setStyle(getWindow());

    updateDepthPreference();

    //update items
    keepUpdated((ListPreference) findPreference(getString(R.string.pref_resolution)));

    //visual updates
    findPreference("src_hardware").setOnPreferenceClickListener(fixBackground);
    findPreference("src_realtime").setOnPreferenceClickListener(fixBackground);
    findPreference("src_parameters").setOnPreferenceClickListener(fixBackground);
    findPreference("src_postprocess").setOnPreferenceClickListener(fixBackground);
    findPreference("src_visualisations").setOnPreferenceClickListener(fixBackground);
  }

  private void updateDepthPreference() {
    String key = getString(R.string.pref_depth);
    CheckBoxPreference depth = (CheckBoxPreference) findPreference(key);
    if (depth == null) return;

    boolean huaweiAvailable = Compatibility.isHuaweiArEngineAvailable(this);
    boolean huaweiHardwareDepth = huaweiAvailable
        && Compatibility.isHuaweiHardwareDepthSupported(this);
    boolean useHuawei = Compatibility.shouldUseHuawei(this);
    boolean hardwareDepth;
    boolean computedDepth = false;
    if (useHuawei) {
      hardwareDepth = huaweiHardwareDepth;
    } else {
      DeviceCapabilities.ArCoreInfo arCore = DeviceCapabilities.probeArCore(this);
      hardwareDepth = arCore.hardwareDepthCameraConfig;
      computedDepth = arCore.automaticDepth || arCore.rawDepth;
    }

    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
    if (!preferences.contains(key)) depth.setChecked(hardwareDepth);
    if (!hardwareDepth) depth.setChecked(false);
    depth.setEnabled(hardwareDepth);
    if (hardwareDepth) {
      depth.setTitle(R.string.depth_hardware_title);
      depth.setSummary(R.string.depth_hardware_summary);
    } else {
      depth.setTitle(R.string.depth_hardware_unavailable_title);
      depth.setSummary(computedDepth
          ? R.string.depth_computed_summary
          : R.string.depth_no_depth_summary);
    }
  }

  private void keepUpdated(ListPreference pref) {
    if (pref.isEnabled()) {

      CharSequence[] texts = pref.getEntries();
      CharSequence[] values = pref.getEntryValues();
      pref.setOnPreferenceChangeListener((preference, newValue) -> {
        for (int i = 0; i < values.length; i++) {
          if (values[i].toString().compareTo((String)newValue) == 0) {
            pref.setSummary(texts[i]);
            return true;
          }
        }
        pref.setSummary((String)newValue);
        return true;
      });

      String newValue = pref.getValue();
      for (int i = 0; i < values.length; i++) {
        if (values[i].toString().compareTo(newValue) == 0) {
          pref.setSummary(texts[i]);
          return;
        }
      }
      pref.setSummary(newValue);
    } else {
      pref.setSummary("");
    }
  }

  private int getNavigationBarColor() {
    return Color.argb(255, 32, 32, 32);
  }

  private int getStatusBarColor() {
    return Color.argb(255, 48, 48, 48);
  }

  protected void setStyle(Window window) {
    int lFlags = window.getDecorView().getSystemUiVisibility();
    window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    window.setBackgroundDrawable(getDrawable(com.lvonasek.arcore3dscanner.R.drawable.background_settings));
    window.setStatusBarColor(getStatusBarColor());
    window.setNavigationBarColor(getNavigationBarColor());
    if (Color.red(getStatusBarColor()) > 128)
      window.getDecorView().setSystemUiVisibility(lFlags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    else
      window.getDecorView().setSystemUiVisibility(lFlags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    AbstractActivity.setOrientation(true, this);
  }

  protected Preference.OnPreferenceClickListener fixBackground = preference -> {
    PreferenceScreen a = (PreferenceScreen) preference;
    setStyle(a.getDialog().getWindow());
    return false;
  };
}
