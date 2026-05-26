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
    private final Paint emptyP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerP = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF tmpRect = new RectF();
    private final float density;

    private String title = "";
    private List<UsageChartView.DeviceUsage> devices = new ArrayList<>();
    public DeviceBreakdownView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        nameP.setColor(TEXT_PRIMARY);
        nameP.setTextSize(16f * density);
        nameP.setFakeBoldText(true);

        timeP.setColor(TEXT_PRIMARY);
        timeP.setTextSize(16f * density);
        timeP.setTextAlign(Paint.Align.RIGHT);
        timeP.setFakeBoldText(true);

        metaP.setColor(TEXT_MUTED);
        metaP.setTextSize(13f * density);

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
        requestLayout();
        // 双保险：让父链也 requestLayout，避免 LinearLayout 不重新 measure 导致高度错
        if (getParent() instanceof View) ((View) getParent()).requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int rows = Math.max(devices.size(), 1);
        int rowH = Math.round(60f * density);
        int contentH = rowH * rows + Math.round(10f * density);
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
        float rowH = 60f * density;

        for (int i = 0; i < devices.size(); i++) {
            UsageChartView.DeviceUsage d = devices.get(i);
            float top = i * rowH + 6f * density;

            // 设备颜色
            int color = (d.colorIndex >= 0 && d.colorIndex < DEVICE_LINE_COLORS.length)
                    ? DEVICE_LINE_COLORS[d.colorIndex] : DEVICE_LINE_COLORS[i % DEVICE_LINE_COLORS.length];
            dotP.setColor(color);

            float dotR = 5f * density;
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

            String meta = d.uploadedAt.isEmpty() ? "尚未记录上传时间" : ("上传 " + d.uploadedAt);
            canvas.drawText(meta, nameX, top + 44f * density, metaP);

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
