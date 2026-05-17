package com.example.biliusage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Arrays;

/**
 * 24 小时分布柱状图（0..23 共 24 根）。
 * 用于展示「某一天在哪个时段看了 B 站多久」。
 * 选中态 / 交互不需要（点击日期切换由 {@link UsageChartView} 负责）。
 */
public final class HourChartView extends View {
    private static final int PINK = 0xFFFB7299;
    private static final int PINK_SOFT = 0xFFFFE3EC;
    private static final int TEXT_SECONDARY = 0xFF94A3B8;
    private static final int BORDER = 0xFFEEF1F5;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final long[] hours = new long[24];
    private final float density;

    public HourChartView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        barPaint.setColor(PINK);
        bgPaint.setColor(PINK_SOFT);
        axisPaint.setColor(BORDER);
        axisPaint.setStrokeWidth(1f);
        labelPaint.setColor(TEXT_SECONDARY);
        labelPaint.setTextSize(10f * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setColor(TEXT_SECONDARY);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setTextSize(12f * density);
        setMinimumHeight(Math.round(140f * density));
    }

    public void setHours(long[] values) {
        if (values == null) Arrays.fill(hours, 0L);
        else {
            for (int i = 0; i < 24; i++) hours[i] = i < values.length ? values[i] : 0L;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        long max = 1L;
        for (long v : hours) max = Math.max(max, v);

        if (max <= 1L) {
            canvas.drawText("尚无时段数据", w / 2f, h / 2f, emptyPaint);
            return;
        }

        float padTop = 12f * density;
        float padBot = 22f * density;
        float padX = 8f * density;
        float chartTop = padTop;
        float chartBot = h - padBot;
        float chartH = chartBot - chartTop;
        float cell = (w - padX * 2f) / 24f;
        float barW = cell * 0.65f;

        canvas.drawLine(padX, chartBot, w - padX, chartBot, axisPaint);

        for (int i = 0; i < 24; i++) {
            float centerX = padX + cell * i + cell / 2f;
            float left = centerX - barW / 2f;
            float right = centerX + barW / 2f;

            tmp.set(left, chartTop, right, chartBot);
            canvas.drawRoundRect(tmp, barW / 2f, barW / 2f, bgPaint);

            long ms = hours[i];
            float ratio = (float) ((double) ms / (double) max);
            float barH = Math.max(2f * density, chartH * ratio);
            tmp.set(left, chartBot - barH, right, chartBot);
            canvas.drawRoundRect(tmp, barW / 2f, barW / 2f, barPaint);
        }

        // x 轴标签（0/6/12/18/23）
        int[] labels = { 0, 6, 12, 18, 23 };
        for (int i : labels) {
            float centerX = padX + cell * i + cell / 2f;
            canvas.drawText(i + ":00", centerX, h - 4f * density, labelPaint);
        }
    }
}
