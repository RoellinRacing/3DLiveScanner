package com.lvonasek.arcore3dscanner.main;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.lvonasek.arcore3dscanner.R;

import java.util.ArrayList;
import java.util.Locale;

/** Persistent tap-to-measure overlay for the model viewer. */
public class DistanceMeasuring extends View {
  private static class Point3 {
    float x, y, z, quality;
    Point3(float x, float y, float z, float quality) {
      this.x = x; this.y = y; this.z = z; this.quality = quality;
    }
  }

  private static class Measurement {
    Point3 a, b;
    Measurement(Point3 a, Point3 b) { this.a = a; this.b = b; }
  }

  private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final ArrayList<Measurement> mMeasurements = new ArrayList<>();
  private final float mDensity;
  private Point3 mPending;
  private boolean mEnabled = false;
  private boolean mMoved = false;
  private boolean mPicking = false;
  private boolean mScaling = false;
  private int mPickGeneration = 0;
  private long mProjectionAnimationUntil = 0;
  private float mDownX, mDownY;

  public DistanceMeasuring(Context context, AttributeSet attrs) {
    super(context, attrs);
    mDensity = getResources().getDisplayMetrics().density;
    setWillNotDraw(false);
  }

  @Override
  public boolean isEnabled() { return mEnabled; }

  @Override
  public void setEnabled(boolean enabled) {
    super.setEnabled(enabled);
    mEnabled = enabled;
    mPickGeneration++;
    mPicking = false;
    mPending = null;
    invalidate();
  }

  public boolean hasMeasurements() { return mPending != null || !mMeasurements.isEmpty(); }

  public void refreshProjection() {
    if (hasMeasurements()) {
      // Native camera motion is eased after the gesture ends. Follow it for a
      // short settling window instead of projecting continuously while idle.
      mProjectionAnimationUntil = SystemClock.uptimeMillis() + 900;
      postInvalidateOnAnimation();
    }
  }

  /** Main may also pass this event to CameraControl so drag and pinch keep working. */
  public boolean onViewerTouch(MotionEvent event) {
    if (!mEnabled) return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        mDownX = event.getX(); mDownY = event.getY(); mMoved = false; return true;
      case MotionEvent.ACTION_POINTER_DOWN:
        mMoved = true; return true;
      case MotionEvent.ACTION_MOVE:
        float dx = event.getX() - mDownX;
        float dy = event.getY() - mDownY;
        if (dx * dx + dy * dy > mDensity * mDensity * 100) mMoved = true;
        return true;
      case MotionEvent.ACTION_CANCEL:
        mMoved = true; return true;
      case MotionEvent.ACTION_UP:
        if (!mMoved) pick(event.getX(), event.getY());
        return true;
      default:
        return true;
    }
  }

  private void pick(final float screenX, final float screenY) {
    if (mPicking || mScaling) return;
    mPicking = true;
    final int generation = ++mPickGeneration;
    new Thread(() -> {
      final float[] hit = JNI.pickMeasurementPoint(screenX, screenY);
      post(() -> {
        if (generation != mPickGeneration) return;
        mPicking = false;
        if (!mEnabled) return;
        if (hit == null || hit.length < 4) {
          Toast.makeText(getContext(), R.string.measure_miss, Toast.LENGTH_SHORT).show();
          return;
        }
        Point3 point = new Point3(hit[0], hit[1], hit[2], hit[3]);
        if (mPending == null) mPending = point;
        else { mMeasurements.add(new Measurement(mPending, point)); mPending = null; }
        invalidate();
      });
    }).start();
  }

  public void deleteLast() {
    mPickGeneration++;
    mPicking = false;
    if (mPending != null) mPending = null;
    else if (!mMeasurements.isEmpty()) mMeasurements.remove(mMeasurements.size() - 1);
    invalidate();
  }

  public void clearAll() {
    mPickGeneration++;
    mPicking = false;
    mPending = null;
    mMeasurements.clear();
    invalidate();
  }

  /** Kept for old call sites. Camera movement should use refreshProjection(). */
  public void reset() { clearAll(); }

  public void calibrateLast() {
    if (mPicking || mScaling) return;
    if (mMeasurements.isEmpty()) {
      Toast.makeText(getContext(), R.string.measure_need_pair, Toast.LENGTH_SHORT).show();
      return;
    }
    final Measurement reference = mMeasurements.get(mMeasurements.size() - 1);
    final float measured = distance(reference.a, reference.b);
    final EditText input = new EditText(getContext());
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    input.setText(String.format(Locale.US, "%.1f", measured * 1000.0f));
    input.selectAll();
    int padding = (int) (20 * mDensity);
    input.setPadding(padding, padding, padding, padding);

    new AlertDialog.Builder(getContext())
        .setTitle(R.string.measure_scale_title)
        .setMessage(R.string.measure_scale_message)
        .setView(input)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.measure_apply, (dialog, which) -> {
          try {
            float knownMm = Float.parseFloat(input.getText().toString().trim().replace(',', '.'));
            final float factor = (knownMm / 1000.0f) / measured;
            if (!Float.isFinite(factor) || factor < 0.01f || factor > 100.0f)
              throw new NumberFormatException();
            mScaling = true;
            new Thread(() -> {
              final boolean ok = JNI.applyUniformScale(
                  factor, reference.a.x, reference.a.y, reference.a.z);
              post(() -> {
                mScaling = false;
                if (ok) {
                  scalePoints(reference.a, factor);
                  Toast.makeText(getContext(), R.string.measure_scale_done, Toast.LENGTH_SHORT).show();
                  invalidate();
                } else {
                  Toast.makeText(getContext(), R.string.measure_scale_failed, Toast.LENGTH_SHORT).show();
                }
              });
            }).start();
          } catch (Exception exception) {
            Toast.makeText(getContext(), R.string.measure_invalid, Toast.LENGTH_SHORT).show();
          }
        }).show();
  }

  private void scalePoints(Point3 anchor, float factor) {
    for (Measurement measurement : mMeasurements) {
      scale(measurement.a, anchor, factor); scale(measurement.b, anchor, factor);
    }
    if (mPending != null) scale(mPending, anchor, factor);
  }

  private static void scale(Point3 point, Point3 anchor, float factor) {
    point.x = anchor.x + (point.x - anchor.x) * factor;
    point.y = anchor.y + (point.y - anchor.y) * factor;
    point.z = anchor.z + (point.z - anchor.z) * factor;
  }

  private static float distance(Point3 a, Point3 b) {
    float x = b.x - a.x, y = b.y - a.y, z = b.z - a.z;
    return (float) Math.sqrt(x * x + y * y + z * z);
  }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (!hasMeasurements()) return;

    ArrayList<Point3> points = new ArrayList<>();
    for (Measurement measurement : mMeasurements) {
      points.add(measurement.a); points.add(measurement.b);
    }
    if (mPending != null) points.add(mPending);

    float[] world = new float[points.size() * 3];
    for (int i = 0; i < points.size(); i++) {
      Point3 point = points.get(i);
      world[i * 3] = point.x; world[i * 3 + 1] = point.y; world[i * 3 + 2] = point.z;
    }
    float[] screen = JNI.projectMeasurementPoints(world);
    if (screen == null || screen.length != points.size() * 3) return;

    for (int i = 0; i < mMeasurements.size(); i++) {
      int ai = i * 6, bi = ai + 3;
      if (screen[ai + 2] < 0.5f || screen[bi + 2] < 0.5f) continue;
      Measurement measurement = mMeasurements.get(i);
      float quality = Math.min(measurement.a.quality, measurement.b.quality);
      int color = qualityColor(quality);
      mPaint.setColor(color); mPaint.setStrokeWidth(2.5f * mDensity);
      mPaint.setStyle(Paint.Style.STROKE);
      canvas.drawLine(screen[ai], screen[ai + 1], screen[bi], screen[bi + 1], mPaint);
      drawPoint(canvas, screen[ai], screen[ai + 1], color);
      drawPoint(canvas, screen[bi], screen[bi + 1], color);

      float mx = (screen[ai] + screen[bi]) * 0.5f;
      float my = (screen[ai + 1] + screen[bi + 1]) * 0.5f;
      String line1 = formatDistance(distance(measurement.a, measurement.b));
      String line2 = String.format(Locale.getDefault(),
          "Delta X %+.1f   Y %+.1f   Z %+.1f mm",
          (measurement.b.x - measurement.a.x) * 1000,
          (measurement.b.y - measurement.a.y) * 1000,
          (measurement.b.z - measurement.a.z) * 1000);
      drawCard(canvas, mx, my, line1, line2, color);
    }

    int last = points.size() - 1, si = last * 3;
    if (last >= 0 && screen[si + 2] > 0.5f)
      drawLoupe(canvas, screen[si], screen[si + 1], points.get(last).quality);
    if (SystemClock.uptimeMillis() < mProjectionAnimationUntil)
      postInvalidateOnAnimation();
  }

  private void drawPoint(Canvas canvas, float x, float y, int color) {
    mPaint.setStyle(Paint.Style.FILL); mPaint.setColor(0xE6101720);
    canvas.drawCircle(x, y, 9 * mDensity, mPaint);
    mPaint.setStyle(Paint.Style.STROKE); mPaint.setStrokeWidth(3 * mDensity); mPaint.setColor(color);
    canvas.drawCircle(x, y, 7 * mDensity, mPaint);
    mPaint.setStyle(Paint.Style.FILL); canvas.drawCircle(x, y, 2 * mDensity, mPaint);
  }

  private void drawCard(Canvas canvas, float x, float y, String first, String second, int color) {
    mPaint.setTextAlign(Paint.Align.CENTER); mPaint.setTextSize(16 * mDensity);
    mPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    float width = Math.max(mPaint.measureText(first), mPaint.measureText(second)) + 24 * mDensity;
    float height = 48 * mDensity;
    RectF rect = new RectF(x - width / 2, y - height / 2, x + width / 2, y + height / 2);
    mPaint.setStyle(Paint.Style.FILL); mPaint.setColor(0xE6101720);
    canvas.drawRoundRect(rect, 12 * mDensity, 12 * mDensity, mPaint);
    mPaint.setStyle(Paint.Style.STROKE); mPaint.setStrokeWidth(mDensity); mPaint.setColor(color);
    canvas.drawRoundRect(rect, 12 * mDensity, 12 * mDensity, mPaint);
    mPaint.setStyle(Paint.Style.FILL); mPaint.setColor(Color.WHITE);
    canvas.drawText(first, x, y - 3 * mDensity, mPaint);
    mPaint.setTypeface(android.graphics.Typeface.DEFAULT); mPaint.setTextSize(11 * mDensity);
    mPaint.setColor(0xFFCBD5E1); canvas.drawText(second, x, y + 15 * mDensity, mPaint);
  }

  private void drawLoupe(Canvas canvas, float x, float y, float quality) {
    float lx = Math.min(getWidth() - 38 * mDensity,
        Math.max(38 * mDensity, x + (x < getWidth() / 2.0f ? 58 : -58) * mDensity));
    float ly = Math.min(getHeight() - 80 * mDensity,
        Math.max(42 * mDensity, y - 62 * mDensity));
    int color = qualityColor(quality);
    mPaint.setStyle(Paint.Style.STROKE); mPaint.setStrokeWidth(mDensity); mPaint.setColor(0x8894A3B8);
    canvas.drawLine(x, y, lx, ly, mPaint);
    mPaint.setStyle(Paint.Style.FILL); mPaint.setColor(0xEE101720);
    canvas.drawCircle(lx, ly, 31 * mDensity, mPaint);
    mPaint.setStyle(Paint.Style.STROKE); mPaint.setStrokeWidth(3 * mDensity); mPaint.setColor(color);
    canvas.drawCircle(lx, ly, 29 * mDensity, mPaint);
    canvas.drawLine(lx - 13 * mDensity, ly, lx + 13 * mDensity, ly, mPaint);
    canvas.drawLine(lx, ly - 13 * mDensity, lx, ly + 13 * mDensity, mPaint);
    mPaint.setStyle(Paint.Style.FILL); mPaint.setTextAlign(Paint.Align.CENTER);
    mPaint.setTextSize(10 * mDensity); mPaint.setColor(Color.WHITE);
    canvas.drawText(qualityText(quality) + " " + Math.round(quality * 100) + "%",
        lx, ly + 47 * mDensity, mPaint);
  }

  private int qualityColor(float quality) {
    return quality >= 0.75f ? 0xFF22C55E : quality >= 0.45f ? 0xFFF59E0B : 0xFFEF4444;
  }

  private String qualityText(float quality) {
    int id = quality >= 0.75f ? R.string.measure_quality_good :
        quality >= 0.45f ? R.string.measure_quality_medium : R.string.measure_quality_low;
    return getResources().getString(id);
  }

  private String formatDistance(float metres) {
    return metres >= 1 ? String.format(Locale.getDefault(), "%.3f m", metres) :
        String.format(Locale.getDefault(), "%.1f mm", metres * 1000);
  }
}
