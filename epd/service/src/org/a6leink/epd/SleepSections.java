/*
 * Copyright (C) 2026 The hk-hisense-a6l-eink authors
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

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Stage 2 of the sleeping screen (spec 03 section 2): the widget and
 * notification sections below the 360 px header. Handler thread of the
 * service.
 *
 * A draw has two passes. {@link #prepare} reads the settings and the
 * notifications and sizes the widgets for the space they may get, which is
 * what the widget host renders. {@link #draw} then lays the sections out again
 * with the widgets that were actually rendered, so a skipped widget leaves no
 * hole, and paints them.
 */
final class SleepSections {
    private static final String TAG = "A6lEpdStatus";

    private static final int W = EpdSettings.EINK_WIDTH;
    private static final int H = EpdSettings.EINK_HEIGHT;
    /** Height of the stage 1 header. */
    static final int HEADER = 360;
    private static final int MARGIN = 24;
    private static final int CONTENT_W = W - 2 * MARGIN;
    private static final int BOTTOM = H - 16;
    private static final int RULE = 2;
    private static final int RULE_GAP = 16;
    private static final int WIDGET_GAP = 16;
    private static final int MIN_WIDGET_H = 48;
    private static final int DEFAULT_WIDGET_H = 160;
    private static final int ITEM_GAP = 8;
    private static final int ICON = 32;
    private static final int TEXT_X = MARGIN + ICON + 12;
    private static final int TEXT_W = W - MARGIN - TEXT_X;
    private static final int MORE_H = 34;

    private static final int SECTION_WIDGETS = 0;
    private static final int SECTION_NOTIFICATIONS = 1;

    /** A widget that may be drawn: its options, its height range and, once rendered, its picture. */
    static final class WidgetCandidate {
        final SleepWidgetConfig config;
        final int desired;
        final int minimum;
        Bitmap bitmap;
        // Result of the last layout pass.
        int y = -1;
        int height;

        WidgetCandidate(SleepWidgetConfig config, int desired, int minimum) {
            this.config = config;
            this.desired = desired;
            this.minimum = minimum;
        }
    }

    /** A notification item, measured for drawing. */
    private static final class Note {
        final SleepNotifications.Item item;
        final String header;
        final String title;
        final StaticLayout text;
        final int height;

        Note(SleepNotifications.Item item, String header, String title, StaticLayout text,
                int height) {
            this.item = item;
            this.header = header;
            this.title = title;
            this.text = text;
            this.height = height;
        }
    }

    /** What one sleeping-screen draw shows below the header. */
    static final class Content {
        int order;
        int max;
        int[] configuredIds = new int[0];
        final List<WidgetCandidate> widgets = new ArrayList<>();
        List<SleepNotifications.Item> items = new ArrayList<>();
        final List<Note> notes = new ArrayList<>();
        String signature = "";

        // Result of the last layout pass.
        final List<Integer> rules = new ArrayList<>();
        int notesY = -1;
        int notesShown;
        int more;
        int moreY = -1;

        /** The widgets placed by the first pass, in order. */
        List<WidgetCandidate> placedWidgets() {
            List<WidgetCandidate> out = new ArrayList<>();
            for (WidgetCandidate w : widgets) if (w.y >= 0) out.add(w);
            return out;
        }

    }

    private final Context mContext;
    private final SleepNotifications mNotifications;
    private final TextPaint mHeaderPaint = textPaint(22, false);
    private final TextPaint mTitlePaint = textPaint(27, true);
    private final TextPaint mTextPaint = textPaint(23, false);
    private final TextPaint mMorePaint = textPaint(24, false);
    private final Paint mRulePaint = new Paint();

    SleepSections(Context context) {
        mContext = context;
        mNotifications = new SleepNotifications(context);
        mRulePaint.setColor(Color.BLACK);
    }

    /** The current notification list, as compared to decide on a redraw. */
    String notificationSignature() {
        return SleepNotifications.signature(mNotifications.collect());
    }

    /** First pass. Never throws: on failure the header is drawn alone. */
    Content prepare(long now) {
        Content c = new Content();
        final ContentResolver cr = mContext.getContentResolver();
        try {
            c.order = Settings.System.getIntForUser(cr, EpdSettings.SLEEP_SECTIONS_ORDER,
                    EpdSettings.ORDER_WIDGETS_FIRST, UserHandle.USER_CURRENT);
            c.max = Settings.System.getIntForUser(cr, EpdSettings.SLEEP_NOTIFICATIONS_MAX,
                    EpdSettings.NOTIFICATIONS_MAX_FIT, UserHandle.USER_CURRENT);
            if (c.max > EpdSettings.NOTIFICATIONS_MAX_LIMIT) {
                c.max = EpdSettings.NOTIFICATIONS_MAX_LIMIT;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read the sleep screen settings", e);
        }

        c.items = mNotifications.collect();
        c.signature = SleepNotifications.signature(c.items);
        try {
            addWidgetCandidates(c, cr);
        } catch (RuntimeException e) {
            // e.g. AppWidgetService refuses a locked user.
            Log.w(TAG, "No widgets on this draw: " + e.getMessage());
            c.widgets.clear();
        }
        try {
            if (!c.items.isEmpty()) measureNotes(c, now);
        } catch (RuntimeException e) {
            Log.w(TAG, "No notifications on this draw", e);
            c.items = new ArrayList<>();
            c.notes.clear();
        }
        layout(c);
        return c;
    }

    private void addWidgetCandidates(Content c, ContentResolver cr) {
        List<SleepWidgetConfig> configs = SleepWidgetConfig.load(cr, UserHandle.USER_CURRENT);
        c.configuredIds = new int[configs.size()];
        for (int i = 0; i < configs.size(); i++) c.configuredIds[i] = configs.get(i).id;
        if (configs.isEmpty()) return;

        AppWidgetManager manager = AppWidgetManager.getInstance(mContext);
        float scale = SleepWidgetHost.deviceToPanelScale(mContext);
        int maxH = BOTTOM - HEADER - 2 * RULE_GAP - RULE;
        for (SleepWidgetConfig cfg : configs) {
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(cfg.id);
            if (info == null) continue; // deleted or provider uninstalled
            int providerMin = Math.round(info.minHeight * scale);
            int desired = cfg.size > SleepWidgetConfig.SIZE_DEFAULT
                    ? SleepWidgetConfig.SIZE_HEIGHTS[cfg.size]
                    : Math.max(providerMin > 0 ? providerMin : DEFAULT_WIDGET_H, MIN_WIDGET_H);
            desired = Math.min(desired, maxH);
            int resizeMin = info.minResizeHeight > 0
                    ? Math.round(info.minResizeHeight * scale) : providerMin;
            int minimum = Math.max(MIN_WIDGET_H, Math.min(desired,
                    resizeMin > 0 ? resizeMin : desired));
            c.widgets.add(new WidgetCandidate(cfg, desired, minimum));
        }
    }

    /** The render requests for the widgets placed by the first pass. */
    static SleepWidgetHost.Request[] requests(Content c) {
        List<WidgetCandidate> placed = c.placedWidgets();
        SleepWidgetHost.Request[] out = new SleepWidgetHost.Request[placed.size()];
        for (int i = 0; i < out.length; i++) {
            WidgetCandidate w = placed.get(i);
            out[i] = new SleepWidgetHost.Request(w.config.id, CONTENT_W, w.height,
                    w.config.invert ? Color.BLACK : Color.WHITE);
        }
        return out;
    }

    /**
     * Second pass: keeps the widgets that were rendered (in request order) and
     * lays the sections out again. {@code bitmaps} may be null (no widgets drawn).
     */
    void setRendered(Content c, Bitmap[] bitmaps) {
        List<WidgetCandidate> placed = c.placedWidgets();
        c.widgets.clear();
        for (int i = 0; i < placed.size(); i++) {
            Bitmap b = bitmaps != null && i < bitmaps.length ? bitmaps[i] : null;
            if (b == null) continue;
            WidgetCandidate w = new WidgetCandidate(placed.get(i).config, b.getHeight(),
                    b.getHeight());
            w.bitmap = b;
            c.widgets.add(w);
        }
        layout(c);
    }

    // ---------------------------------------------------------------------------------------
    // Layout (spec 03 section 2)

    private void layout(Content c) {
        c.rules.clear();
        c.notesY = -1;
        c.notesShown = 0;
        c.more = 0;
        c.moreY = -1;
        for (WidgetCandidate w : c.widgets) w.y = -1;

        int y = HEADER;
        int[] order = c.order == EpdSettings.ORDER_NOTIFICATIONS_FIRST
                ? new int[] { SECTION_NOTIFICATIONS, SECTION_WIDGETS }
                : new int[] { SECTION_WIDGETS, SECTION_NOTIFICATIONS };
        for (int section : order) {
            // A rule with a gap on each side separates the sections that are present.
            int top = y + RULE_GAP + RULE + RULE_GAP;
            int end = section == SECTION_WIDGETS ? layoutWidgets(c, top) : layoutNotes(c, top);
            if (end > top) {
                c.rules.add(y + RULE_GAP);
                y = end;
            }
        }
    }

    /** Places widgets from {@code top}; returns the end of the section (top if empty). */
    private static int layoutWidgets(Content c, int top) {
        int y = top;
        boolean any = false;
        for (WidgetCandidate w : c.widgets) {
            int start = any ? y + WIDGET_GAP : y;
            int avail = BOTTOM - start;
            int h;
            if (w.desired <= avail) {
                h = w.desired;
            } else if (w.minimum <= avail) {
                h = avail; // capped by the space left
            } else {
                continue; // does not fit: skipped, not cut
            }
            w.y = start;
            w.height = h;
            y = start + h;
            any = true;
        }
        return any ? y : top;
    }

    private int layoutNotes(Content c, int top) {
        int total = c.items.size();
        if (total == 0 || c.max == 0) return top;
        int avail = BOTTOM - top;
        int best = 0;
        int used = 0;
        for (int k = 1; k <= c.notes.size(); k++) {
            used += (k > 1 ? ITEM_GAP : 0) + c.notes.get(k - 1).height;
            if (used > avail) break;
            int need = used + (k < total ? ITEM_GAP + MORE_H : 0);
            if (need <= avail) best = k;
        }
        int more = total - best;
        if (best == 0 && (more == 0 || MORE_H > avail)) return top;
        c.notesY = top;
        c.notesShown = best;
        c.more = more;
        int y = top;
        for (int i = 0; i < best; i++) y += (i > 0 ? ITEM_GAP : 0) + c.notes.get(i).height;
        if (more > 0) {
            c.moreY = best > 0 ? y + ITEM_GAP : y;
            y = c.moreY + MORE_H;
        }
        return y;
    }

    /** Measures the items that could be shown at all (at most the maximum setting). */
    private void measureNotes(Content c, long now) {
        int limit = c.max < 0 ? c.items.size() : Math.min(c.max, c.items.size());
        // No more than fit on the panel even at the smallest item height.
        limit = Math.min(limit, (BOTTOM - HEADER) / (ICON + ITEM_GAP) + 1);
        for (int i = 0; i < limit; i++) {
            SleepNotifications.Item item = c.items.get(i);
            String app = mNotifications.appName(item);
            String header;
            String title = null;
            CharSequence body;
            if (item.hidden) {
                header = app;
                body = mContext.getString(R.string.sleep_notification_hidden);
            } else {
                header = app + " · " + formatTime(item.when, now);
                if (item.title != null) {
                    title = TextUtils.ellipsize(item.title.replace('\n', ' '), mTitlePaint,
                            TEXT_W, TextUtils.TruncateAt.END).toString();
                }
                body = item.text;
            }
            header = TextUtils.ellipsize(header, mHeaderPaint, TEXT_W,
                    TextUtils.TruncateAt.END).toString();
            StaticLayout text = null;
            if (!TextUtils.isEmpty(body)) {
                text = StaticLayout.Builder.obtain(body, 0, body.length(), mTextPaint, TEXT_W)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(item.hidden ? 1 : 2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setIncludePad(false)
                        .build();
            }
            int h = ICON + 4;
            if (title != null) h += lineHeight(mTitlePaint);
            if (text != null) h += text.getHeight();
            h += 4;
            c.notes.add(new Note(item, header, title, text, h));
        }
    }

    private String formatTime(long when, long now) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(when);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(now);
        boolean sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        if (sameDay) return DateFormat.getTimeFormat(mContext).format(when);
        String pattern = DateFormat.getBestDateTimePattern(
                mContext.getResources().getConfiguration().getLocales().get(0), "MMMd");
        return DateFormat.format(pattern, a).toString();
    }

    // ---------------------------------------------------------------------------------------
    // Drawing

    /** Paints the sections laid out by the last pass. Widget bitmaps are converted in place. */
    void draw(Canvas canvas, Content c) {
        for (int y : c.rules) {
            canvas.drawRect(MARGIN, y, W - MARGIN, y + RULE, mRulePaint);
        }
        for (WidgetCandidate w : c.widgets) {
            if (w.y < 0 || w.bitmap == null) continue;
            Mono.convert(w.bitmap, w.config.invert,
                    w.config.contrast == SleepWidgetConfig.CONTRAST_HIGH);
            canvas.drawBitmap(w.bitmap, MARGIN, w.y, null);
        }
        int y = c.notesY;
        for (int i = 0; i < c.notesShown; i++) {
            Note n = c.notes.get(i);
            drawNote(canvas, n, y);
            y += n.height + ITEM_GAP;
        }
        if (c.moreY >= 0) {
            String more = mContext.getResources().getQuantityString(R.plurals.sleep_more,
                    c.more, c.more);
            Paint.FontMetrics fm = mMorePaint.getFontMetrics();
            float baseline = c.moreY + (MORE_H - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            canvas.drawText(more, MARGIN, baseline, mMorePaint);
        }
    }

    private void drawNote(Canvas canvas, Note n, int top) {
        Bitmap icon = icon(n.item);
        if (icon != null) {
            canvas.drawBitmap(icon, MARGIN, top, null);
            icon.recycle();
        }
        Paint.FontMetrics fm = mHeaderPaint.getFontMetrics();
        float baseline = top + (ICON - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        canvas.drawText(n.header, TEXT_X, baseline, mHeaderPaint);
        float y = top + ICON + 4;
        if (n.title != null) {
            canvas.drawText(n.title, TEXT_X, y - mTitlePaint.getFontMetrics().ascent,
                    mTitlePaint);
            y += lineHeight(mTitlePaint);
        }
        if (n.text != null) {
            canvas.save();
            canvas.translate(TEXT_X, y);
            n.text.draw(canvas);
            canvas.restore();
        }
    }

    /** The item's icon, 32 px, black on white. */
    private Bitmap icon(SleepNotifications.Item item) {
        try {
            Drawable small = mNotifications.smallIcon(item);
            if (small != null) return Mono.icon(small, ICON, true);
            Drawable app = mNotifications.appIcon(item);
            if (app != null) return Mono.icon(app, ICON, false);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot draw the icon of " + item.packageName, e);
        }
        return null;
    }

    private static int lineHeight(Paint p) {
        Paint.FontMetricsInt fm = p.getFontMetricsInt();
        return fm.descent - fm.ascent;
    }

    private static TextPaint textPaint(float size, boolean bold) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(size);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p;
    }

    String lastNotificationError() {
        return mNotifications.lastError();
    }

    /** Black-and-white conversion for the E-ink (spec 03 section 3). */
    static final class Mono {
        private Mono() {}

        /** Low and high end of the grey ramp of the "high" contrast (around 50 %). */
        private static final int HIGH_LOW = 100;
        private static final int HIGH_HIGH = 156;

        /** Converts to grey (luminance), then applies invert and contrast. In place. */
        static void convert(Bitmap b, boolean invert, boolean high) {
            if (!b.isMutable()) return;
            int w = b.getWidth();
            int h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int y = (((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587
                        + (c & 0xff) * 114) / 1000;
                if (invert) y = 255 - y;
                if (high) y = stretch(y);
                px[i] = 0xff000000 | (y << 16) | (y << 8) | y;
            }
            b.setPixels(px, 0, w, 0, 0, w, h);
        }

        private static int stretch(int y) {
            if (y <= HIGH_LOW) return 0;
            if (y >= HIGH_HIGH) return 255;
            return (y - HIGH_LOW) * 255 / (HIGH_HIGH - HIGH_LOW);
        }

        /**
         * A square black-on-white icon. A silhouette (notification small icon) is
         * black where it is opaque; other icons are thresholded at 50 % luminance.
         */
        static Bitmap icon(Drawable d, int size, boolean silhouette) {
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            if (!silhouette) c.drawColor(Color.WHITE);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            int[] px = new int[size * size];
            b.getPixels(px, 0, size, 0, 0, size, size);
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                boolean black;
                if (silhouette) {
                    black = (p >>> 24) >= 128;
                } else {
                    int y = (((p >> 16) & 0xff) * 299 + ((p >> 8) & 0xff) * 587
                            + (p & 0xff) * 114) / 1000;
                    black = y < 128;
                }
                px[i] = black ? Color.BLACK : Color.WHITE;
            }
            b.setPixels(px, 0, size, 0, 0, size, size);
            return b;
        }
    }
}
