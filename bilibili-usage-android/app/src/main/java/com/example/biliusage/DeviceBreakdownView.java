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
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int rows = Math.max(devices.size(), 1);
        int rowH = Math.round(56f * density);
        int contentH = rowH * rows + Math.round(16f * density);
        if (devices.isEmpty()) {
            contentH = Math.round(80f * density);
        }
        setMeasuredDimension(width, contentH);
    }

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
        float rowH = 56f * density;

        for (int i = 0; i < devices.size(); i++) {
            UsageChartView.DeviceUsage d = devices.get(i);
            float top = i * rowH + 4f * density;

            float dotR = 4f * density;
            float dotCx = left + dotR;
            float dotCy = top + 14f * density;
            canvas.drawCircle(dotCx, dotCy, dotR, dotP);

            float nameX = dotCx + dotR + 8f * density;
            canvas.drawText(d.displayName(), nameX, top + 18f * density, nameP);

            String timeText = UsageChartView.formatShortDuration(d.totalMs);
            canvas.drawText(timeText, right, top + 18f * density, timeP);

            float barTop = top + 26f * density;
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
            canvas.drawText(meta, left, barBot + 14f * density, metaP);

            if (i < devices.size() - 1) {
                float dy = top + rowH - 1f;
                canvas.drawLine(left, dy, right, dy, dividerP);
            }
        }
    }
}
