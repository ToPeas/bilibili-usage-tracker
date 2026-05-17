package com.example.biliusage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DeviceBreakdownView extends View {

    private static final int PINK = 0xFFFB7299;
    private static final int PINK_SOFT = 0xFFFFE3EC;
    private static final int TEXT_PRIMARY = 0xFF0F172A;
    private static final int TEXT_SECONDARY = 0xFF64748B;
    private static final int TEXT_MUTED = 0xFF94A3B8;
    private static final int CARD_DIVIDER = 0xFFF1F3F7;

    private final Paint nameP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint metaP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBgP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerP = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF tmpRect = new RectF();
    private final float density;

    private String title = "";
    private List<UsageChartView.DeviceUsage> devices = new ArrayList<>();
    private long maxTotal = 0L;

    public DeviceBreakdownView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        nameP.setColor(TEXT_PRIMARY);
        nameP.setTextSize(13f * density);
        nameP.setFakeBoldText(true);

        timeP.setColor(TEXT_PRIMARY);
        timeP.setTextSize(13f * density);
        timeP.setTextAlign(Paint.Align.RIGHT);
        timeP.setFakeBoldText(true);

        metaP.setColor(TEXT_MUTED);
        metaP.setTextSize(11f * density);

        barBgP.setColor(PINK_SOFT);
        barP.setColor(PINK);

        emptyP.setColor(TEXT_SECONDARY);
        emptyP.setTextSize(13f * density);
        emptyP.setTextAlign(Paint.Align.CENTER);

        dotP.setColor(PINK);

        dividerP.setColor(CARD_DIVIDER);
        dividerP.setStrokeWidth(1f);
    }

    public void setData(String title, List<UsageChartView.DeviceUsage> devices) {
        this.title = title == null ? "" : title;
        this.devices = devices == null ? new ArrayList<>() : new ArrayList<>(devices);
        long max = 0L;
        for (UsageChartView.DeviceUsage d : this.devices) max = Math.max(max, d.totalMs);
        this.maxTotal = max;
        requestLayout();
        // 双保险：让父链也 requestLayout，避免 LinearLayout 不重新 measure 导致高度错
        if (getParent() instanceof View) ((View) getParent()).requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int rows = Math.max(devices.size(), 1);
        int rowH = Math.round(72f * density); // 每行需容纳：名称(18) + 进度条(8) + meta(14) + 间隔，以前 56 太压
        int contentH = rowH * rows + Math.round(16f * density);
        if (devices.isEmpty()) {
            contentH = Math.round(80f * density);
        }
        setMeasuredDimension(width, contentH);
    }

    private static final int[] DEVICE_LINE_COLORS = HourChartView.LINE_COLORS;

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();

        if (devices.isEmpty()) {
            canvas.drawText("这一天还没有任何设备上传 ✨", width / 2f, height / 2f + 5f * density, emptyP);
            return;
        }

        float left = 4f * density;
        float right = width - 4f * density;
        float rowH = 72f * density;

        for (int i = 0; i < devices.size(); i++) {
            UsageChartView.DeviceUsage d = devices.get(i);
            float top = i * rowH + 4f * density;

            // 设备颜色
            int color = (d.colorIndex >= 0 && d.colorIndex < DEVICE_LINE_COLORS.length)
                    ? DEVICE_LINE_COLORS[d.colorIndex] : DEVICE_LINE_COLORS[i % DEVICE_LINE_COLORS.length];
            dotP.setColor(color);
            barP.setColor(color);
            int fillAlpha = 0x33;
            int fillColor = (color & 0x00FFFFFF) | (fillAlpha << 24);
            barBgP.setColor(fillColor);

            float dotR = 4f * density;
            float dotCx = left + dotR;
            float dotCy = top + 14f * density;
            canvas.drawCircle(dotCx, dotCy, dotR, dotP);

            float nameX = dotCx + dotR + 8f * density;
            String timeText = UsageChartView.formatShortDuration(d.totalMs);
            float timeWidth = timeP.measureText(timeText);
            float nameMaxWidth = right - nameX - timeWidth - 12f * density;
            String nameText = ellipsize(d.displayName(), nameMaxWidth, nameP);
            canvas.drawText(nameText, nameX, top + 18f * density, nameP);
            canvas.drawText(timeText, right, top + 18f * density, timeP);

            float barTop = top + 30f * density;
            float barBot = barTop + 8f * density;
            tmpRect.set(left, barTop, right, barBot);
            canvas.drawRoundRect(tmpRect, 4f * density, 4f * density, barBgP);

            float ratio = maxTotal > 0L ? (float) ((double) d.totalMs / (double) maxTotal) : 0f;
            float fillRight = left + (right - left) * Math.max(ratio, d.totalMs > 0 ? 0.03f : 0f);
            if (fillRight > left) {
                tmpRect.set(left, barTop, fillRight, barBot);
                canvas.drawRoundRect(tmpRect, 4f * density, 4f * density, barP);
            }

            String meta = d.uploadedAt.isEmpty() ? "尚未记录上传时间" : ("上传 " + d.uploadedAt);
            canvas.drawText(meta, left, barBot + 18f * density, metaP);

            if (i < devices.size() - 1) {
                float dy = top + rowH - 1f;
                canvas.drawLine(left, dy, right, dy, dividerP);
            }
        }
    }

    /** 如果文本宽度超过 maxWidth，则裁切并追加 … */
    private static String ellipsize(String text, float maxWidth, Paint paint) {
        if (text == null || text.isEmpty() || maxWidth <= 0f) return "";
        if (paint.measureText(text) <= maxWidth) return text;
        String suffix = "…";
        float suffixW = paint.measureText(suffix);
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            float w = paint.measureText(text, 0, mid) + suffixW;
            if (w <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        if (lo <= 0) return suffix;
        return text.substring(0, lo) + suffix;
    }
}
