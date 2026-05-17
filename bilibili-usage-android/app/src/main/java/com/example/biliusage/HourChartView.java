package com.example.biliusage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.Arrays;

/**
 * 24 小时分布折线图（0..23 共 24 个点）。
 * 用于展示「某一天在哪个时段看了 B 站多久」。
 * 选中态 / 交互不需要（点击日期切换由 {@link UsageChartView} 负责）。
 */
public final class HourChartView extends View {
    private static final int PINK = 0xFFFB7299;
    private static final int TEXT_SECONDARY = 0xFF94A3B8;
    private static final int BORDER = 0xFFEEF1F5;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint yLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final RectF tmp = new RectF();
    private final long[] hours = new long[24];
    private final float density;

    public HourChartView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        linePaint.setColor(PINK);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.2f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setColor(0x33FB7299);
        fillPaint.setStyle(Paint.Style.FILL);

        dotInnerPaint.setColor(PINK);
        dotInnerPaint.setStyle(Paint.Style.FILL);

        dotOuterPaint.setColor(Color.WHITE);
        dotOuterPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(BORDER);
        gridPaint.setStrokeWidth(1f);

        xLabelPaint.setColor(TEXT_SECONDARY);
        xLabelPaint.setTextSize(10f * density);
        xLabelPaint.setTextAlign(Paint.Align.CENTER);

        yLabelPaint.setColor(TEXT_SECONDARY);
        yLabelPaint.setTextSize(9.5f * density);
        yLabelPaint.setTextAlign(Paint.Align.RIGHT);

        emptyPaint.setColor(TEXT_SECONDARY);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setTextSize(12f * density);

        setMinimumHeight(Math.round(150f * density));
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
        long rawMax = 0L;
        for (long v : hours) rawMax = Math.max(rawMax, v);

        float padTop = 12f * density;
        float padBot = 22f * density;
        float padRight = 8f * density;
        float padLeft = 8f * density;
        // Y 轴标签区
        float yAxisLabelWidth = 34f * density;
        float chartLeft = padLeft + yAxisLabelWidth;
        float chartRight = w - padRight;
        float chartTop = padTop;
        float chartBot = h - padBot;
        float chartH = chartBot - chartTop;
        float chartW = chartRight - chartLeft;

        if (rawMax <= 0L) {
            canvas.drawText("尚无时段数据", w / 2f, h / 2f, emptyPaint);
            return;
        }

        long niceMax = UsageChartView.niceCeil(rawMax);

        // Y 轴 4 段网格 + 标签
        int yTicks = 4;
        for (int t = 0; t <= yTicks; t++) {
            float ratio = (float) t / (float) yTicks;
            float y = chartBot - chartH * ratio;
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint);
            long val = (long) (niceMax * ratio);
            String label = UsageChartView.formatAxisLabel(val);
            float baseline = y - (yLabelPaint.descent() + yLabelPaint.ascent()) / 2f;
            canvas.drawText(label, chartLeft - 4f * density, baseline, yLabelPaint);
        }

        // 计算 24 个点坐标（沿 X 等距 24 段）
        int n = 24;
        float[] xs = new float[n];
        float[] ys = new float[n];
        float cell = chartW / (n - 1);
        for (int i = 0; i < n; i++) {
            xs[i] = chartLeft + cell * i;
            float ratio = (float) ((double) hours[i] / (double) niceMax);
            float y = chartBot - chartH * ratio;
            if (hours[i] <= 0L) y = chartBot;
            else y = Math.min(y, chartBot - 2f * density);
            ys[i] = y;
        }

        // 填充（平滑）
        fillPath.reset();
        fillPath.moveTo(xs[0], chartBot);
        fillPath.lineTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            float midX = (xs[i - 1] + xs[i]) / 2f;
            fillPath.cubicTo(midX, ys[i - 1], midX, ys[i], xs[i], ys[i]);
        }
        fillPath.lineTo(xs[n - 1], chartBot);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);

        // 折线
        linePath.reset();
        linePath.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            float midX = (xs[i - 1] + xs[i]) / 2f;
            linePath.cubicTo(midX, ys[i - 1], midX, ys[i], xs[i], ys[i]);
        }
        canvas.drawPath(linePath, linePaint);

        // 仅给非零小时画 dot（密度太大不好看）
        float dotR = 2.6f * density;
        for (int i = 0; i < n; i++) {
            if (hours[i] <= 0L) continue;
            canvas.drawCircle(xs[i], ys[i], dotR + 1.2f * density, dotOuterPaint);
            canvas.drawCircle(xs[i], ys[i], dotR, dotInnerPaint);
        }

        // X 轴标签（0/6/12/18/23）
        int[] labels = { 0, 6, 12, 18, 23 };
        for (int i : labels) {
            canvas.drawText(i + ":00", xs[i], h - 4f * density, xLabelPaint);
        }
    }
}
