/*
 * Copyright (C) 2026 jetbee
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.a6leink.epd;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.text.format.DateFormat;

import java.util.Calendar;
import java.util.Locale;

/**
 * Draws the status screens of spec 02 section 6.2 on a 720 x 1440 canvas and
 * converts them to the raw RGB565 little-endian format of section 6.4.
 * Everything is drawn in the top quarter (360 px); the rest stays white.
 */
final class StatusRenderer {
    static final int SCREEN_SLEEP = 1;
    static final int SCREEN_LCD = 2;

    private static final int W = EpdSettings.EINK_WIDTH;
    private static final int H = EpdSettings.EINK_HEIGHT;
    private static final int BAND = 360;
    private static final int MARGIN = 24;
    private static final int RIGHT_MARGIN = 12;
    private static final int COLUMN_GAP = 24;

    private final Context mContext;

    StatusRenderer(Context context) {
        mContext = context;
    }

    /** Renders a screen. {@code since} is the time of the switch to the LCD (LCD screen only). */
    Bitmap render(int screen, long now, long since, boolean clock, int clockStyle) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        if (screen == SCREEN_LCD) {
            drawLcdInUse(canvas, since);
        } else if (clock) {
            drawSleepingWithClock(canvas, now, clockStyle);
        } else {
            Paint label = textPaint(64, true);
            drawFitted(canvas, mContext.getString(R.string.status_sleeping), MARGIN, 200,
                    W - MARGIN - RIGHT_MARGIN, label);
        }
        return bmp;
    }

    private void drawLcdInUse(Canvas canvas, long since) {
        float width = W - MARGIN - RIGHT_MARGIN;
        drawFitted(canvas, mContext.getString(R.string.status_lcd_in_use), MARGIN, 150, width,
                textPaint(56, true));
        String time = DateFormat.getTimeFormat(mContext).format(since);
        drawFitted(canvas, mContext.getString(R.string.status_lcd_since, time), MARGIN, 240,
                width, textPaint(40, false));
    }

    private void drawSleepingWithClock(Canvas canvas, long now, int style) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        if (style == EpdSettings.CLOCK_STYLE_DIGITAL) {
            drawDigitalClock(canvas, cal);
        } else {
            drawAnalogClock(canvas, cal);
        }

        float x = BAND + COLUMN_GAP;
        float width = W - RIGHT_MARGIN - x;
        drawFitted(canvas, mContext.getString(R.string.status_sleeping), x, BAND * 0.33f, width,
                textPaint(48, true));

        Locale locale = currentLocale();
        String skeleton = mContext.getString(R.string.status_date_skeleton);
        String date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, skeleton), cal)
                .toString();
        drawFitted(canvas, date, x, BAND * 0.58f, width, textPaint(34, false));

        String battery = batteryLine();
        if (battery != null) {
            drawFitted(canvas, battery, x, BAND * 0.83f, width, textPaint(34, false));
        }
    }

    private void drawAnalogClock(Canvas canvas, Calendar cal) {
        final float cx = BAND / 2f;
        final float cy = BAND / 2f;
        final float r = BAND / 2f - 30;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(8);
        canvas.drawCircle(cx, cy, r, p);

        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 12; i++) {
            boolean major = i % 3 == 0;
            p.setStrokeWidth(major ? 12 : 6);
            float len = major ? 34 : 20;
            double a = Math.toRadians(i * 30);
            float sin = (float) Math.sin(a);
            float cos = (float) Math.cos(a);
            float outer = r - 14;
            canvas.drawLine(cx + sin * outer, cy - cos * outer,
                    cx + sin * (outer - len), cy - cos * (outer - len), p);
        }

        int minute = cal.get(Calendar.MINUTE);
        int hour = cal.get(Calendar.HOUR);
        drawHand(canvas, p, cx, cy, (hour + minute / 60f) * 30f, r * 0.52f, 14);
        drawHand(canvas, p, cx, cy, minute * 6f, r * 0.80f, 9);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.BLACK);
        canvas.drawCircle(cx, cy, 13, dot);
    }

    private static void drawHand(Canvas c, Paint p, float cx, float cy, float degrees,
            float length, float width) {
        double a = Math.toRadians(degrees);
        p.setStrokeWidth(width);
        c.drawLine(cx, cy, cx + (float) Math.sin(a) * length, cy - (float) Math.cos(a) * length,
                p);
    }

    private void drawDigitalClock(Canvas canvas, Calendar cal) {
        boolean is24 = DateFormat.is24HourFormat(mContext, ActivityManager.getCurrentUser());
        String pattern = DateFormat.getBestDateTimePattern(currentLocale(), is24 ? "Hm" : "hm");
        String time = DateFormat.format(pattern, cal).toString();
        Paint p = textPaint(140, true);
        fitText(p, time, BAND - 2 * MARGIN);
        Paint.FontMetrics fm = p.getFontMetrics();
        float y = BAND / 2f - (fm.ascent + fm.descent) / 2f;
        p.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(time, BAND / 2f, y, p);
    }

    private String batteryLine() {
        Intent battery = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return null;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return null;
        int percent = Math.round(level * 100f / scale);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        return mContext.getString(charging ? R.string.status_battery_charging
                : R.string.status_battery, percent);
    }

    private Locale currentLocale() {
        return mContext.getResources().getConfiguration().getLocales().get(0);
    }

    private static Paint textPaint(float size, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(size);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p;
    }

    /** Shrinks the paint's text size until {@code text} fits {@code width}. */
    private static void fitText(Paint p, String text, float width) {
        float measured = p.measureText(text);
        if (measured > width && measured > 0) {
            p.setTextSize(p.getTextSize() * width / measured);
        }
    }

    private static void drawFitted(Canvas c, String text, float x, float baseline, float width,
            Paint p) {
        fitText(p, text, width);
        c.drawText(text, x, baseline, p);
    }

    /** Raw RGB565 little-endian, row-major from the top-left, no header. */
    static byte[] toRgb565(Bitmap bmp) {
        final int n = W * H;
        int[] px = new int[n];
        bmp.getPixels(px, 0, W, 0, 0, W, H);
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            int v = ((c >> 8) & 0xf800) | ((c >> 5) & 0x07e0) | ((c >> 3) & 0x001f);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }
}
