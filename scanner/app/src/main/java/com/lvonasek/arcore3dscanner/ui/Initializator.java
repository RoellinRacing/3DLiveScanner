package com.lvonasek.arcore3dscanner.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.io.InputStream;

public class Initializator extends Activity
{
  private static boolean first = true;
  private static Intent lastIntent;

  public static InputStream getFile(Context context) {
    try {
      InputStream is = context.getContentResolver().openInputStream(lastIntent.getData());
      lastIntent = null;
      return is;
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  public static boolean hasFileIntent() {
    return (lastIntent != null) && (lastIntent.getData() != null);
  }

  public static void rememberFileIntent(Intent intent) {
    if (intent != null && intent.getData() != null) {
      lastIntent = new Intent(intent);
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);

    // This activity is only a launcher/file-import trampoline. Killing the VM
    // from a lifecycle callback made ordinary transitions look like an
    // immediate crash on newer Android versions. It deliberately has no UI,
    // sensors, orientation changes or AR runtime work of its own.
    lastIntent = new Intent(getIntent());
    Intent intent = new Intent(this, FileManager.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if (lastIntent.getData() != null) {
      // Keep the original URI on an Activity that remains in the task. This
      // preserves temporary document-provider read grants after the trampoline
      // itself is finished.
      intent.setDataAndType(lastIntent.getData(), lastIntent.getType());
      int grantFlags = lastIntent.getFlags() &
          (Intent.FLAG_GRANT_READ_URI_PERMISSION
              | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
              | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
              | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
      intent.addFlags(grantFlags);
    }
    startActivity(intent);
    finish();
  }

  public static boolean isFirst()
  {
    boolean output = first;
    first = false;
    return output;
  }
}
