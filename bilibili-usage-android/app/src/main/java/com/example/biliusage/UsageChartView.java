package com.example.biliusage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class UsageChartView extends View {

    public interface OnDaySelectedListener {
        void onSelected(int index, DayBucket bucket);
    }

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint yAxisLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // 折线图专用
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    private final RectF tmpRect = new RectF();
    private final Path trianglePath = new Path();

    private List<DayBucket> days = new ArrayList<>();
    private int selectedIndex = -1;
    private OnDaySelectedListener listener;

    private static final int PINK = 0xFFFB7299;
    private static final int PINK_SOFT = 0xFFFFE3EC;
    private static final int PINK_HOVER_BG = 0x14FB7299;
    private static final int TEXT_SECONDARY = 0xFF94A3B8;
    private static final int TEXT_PRIMARY = 0xFF0F172A;
    private static final int BORDER = 0xFFEEF1F5;
    private static final int TOOLTIP_BG = 0xFF18181B;

    private final float density;

    public UsageChartView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        barPaint.setColor(PINK);
        barBgPaint.setColor(PINK_SOFT);
        barSelectedPaint.setColor(PINK);

        barStrokePaint.setStyle(Paint.Style.STROKE);
        barStrokePaint.setStrokeWidth(2f * density);
        barStrokePaint.setColor(0xFFE15D88);

        labelPaint.setColor(TEXT_SECONDARY);
        labelPaint.setTextSize(11f * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        labelSelectedPaint.setColor(TEXT_PRIMARY);
        labelSelectedPaint.setTextSize(11f * density);
        labelSelectedPaint.setTextAlign(Paint.Align.CENTER);
        labelSelectedPaint.setFakeBoldText(true);

        tooltipPaint.setColor(TOOLTIP_BG);
        tooltipTextPaint.setColor(Color.WHITE);
        tooltipTextPaint.setTextSize(11f * density);
        tooltipTextPaint.setTextAlign(Paint.Align.CENTER);
        tooltipTextPaint.setFakeBoldText(true);

        gridPaint.setColor(BORDER);
        gridPaint.setStrokeWidth(1f);

        yAxisLabelPaint.setColor(TEXT_SECONDARY);
        yAxisLabelPaint.setTextSize(9.5f * density);
        yAxisLabelPaint.setTextAlign(Paint.Align.RIGHT);

        emptyPaint.setColor(TEXT_SECONDARY);
        emptyPaint.setTextSize(12f * density);
        emptyPaint.setTextAlign(Paint.Align.CENTER);

        selectedBgPaint.setColor(PINK_HOVER_BG);

        // 折线图
        linePaint.setColor(PINK);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        lineFillPaint.setColor(0x33FB7299); // 粉色半透明填充
        lineFillPaint.setStyle(Paint.Style.FILL);

        dotPaint.setColor(PINK);
        dotPaint.setStyle(Paint.Style.FILL);

        dotOuterPaint.setColor(Color.WHITE);
        dotOuterPaint.setStyle(Paint.Style.FILL);

        dotSelectedPaint.setColor(PINK);
        dotSelectedPaint.setStyle(Paint.Style.STROKE);
        dotSelectedPaint.setStrokeWidth(2.5f * density);

        setMinimumHeight(Math.round(180f * density));
        setClickable(true);
        setFocusable(true);
    }

    public void setDays(List<DayBucket> values) {
        this.days = values == null ? new ArrayList<>() : new ArrayList<>(values);
        if (this.days.isEmpty()) {
            selectedIndex = -1;
        } else if (selectedIndex < 0 || selectedIndex >= this.days.size()) {
            selectedIndex = this.days.size() - 1;
        }
        invalidate();
        emitSelected();
    }

    public void setSelectedIndex(int index) {
        if (index < 0 || index >= days.size()) return;
        if (index == selectedIndex) return;
        selectedIndex = index;
        invalidate();
        emitSelected();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public DayBucket getSelected() {
        if (selectedIndex < 0 || selectedIndex >= days.size()) return null;
        return days.get(selectedIndex);
    }

    public void setOnDaySelectedListener(OnDaySelectedListener listener) {
        this.listener = listener;
    }

    private void emitSelected() {
        if (listener == null) return;
        if (selectedIndex < 0 || selectedIndex >= days.size()) return;
        listener.onSelected(selectedIndex, days.get(selectedIndex));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            selectByX(event.getX());
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
            selectByX(event.getX());
            return true;
        }
        return super.onHoverEvent(event);
    }

    private void selectByX(float x) {
        if (days.isEmpty()) return;
        float padding = 14f * density;
        float yAxisLabelWidth = 34f * density;
        float chartLeft = padding + yAxisLabelWidth;
        float chartRight = getWidth() - padding;
        float cell = (chartRight - chartLeft) / days.size();
        if (cell <= 0f) return;
        int idx = (int) ((x - chartLeft) / cell);
        if (idx < 0) idx = 0;
        if (idx >= days.size()) idx = days.size() - 1;
        if (idx != selectedIndex) {
            selectedIndex = idx;
            invalidate();
            emitSelected();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();

        if (days.isEmpty()) {
            canvas.drawText("暂无数据", width / 2f, height / 2f, emptyPaint);
            return;
        }

        float padding = 14f * density;
        float labelHeight = 22f * density;
        float tooltipHeight = 22f * density;
        // 左侧预留 Y 轴标签区
        float yAxisLabelWidth = 34f * density;
        float chartLeft = padding + yAxisLabelWidth;
        float chartRight = width - padding;
        float chartTop = padding + tooltipHeight + 8f * density;
        float chartBottom = height - labelHeight;
        float chartHeight = chartBottom - chartTop;
        float chartWidth = chartRight - chartLeft;

        long max = 1L;
        for (DayBucket d : days) {
            max = Math.max(max, d.totalMs);
        }
        // 把 max 向上取整到干净刻度
        long niceMax = niceCeil(max);

        // Y 轴网格 + 标签（4 档）
        int yTicks = 4;
        for (int t = 0; t <= yTicks; t++) {
            float ratio = (float) t / (float) yTicks;
            float y = chartBottom - chartHeight * ratio;
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint);
            long val = (long) (niceMax * ratio);
            String label = formatAxisLabel(val);
            // 基线微调到中间
            float baseline = y - (yAxisLabelPaint.descent() + yAxisLabelPaint.ascent()) / 2f;
            canvas.drawText(label, chartLeft - 4f * density, baseline, yAxisLabelPaint);
        }

        int n = days.size();
        float cell = chartWidth / Math.max(1, n);

        // 计算每个点的中心坐标
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            DayBucket day = days.get(i);
            float centerX = chartLeft + cell * i + cell / 2f;
            float ratio = (float) ((double) day.totalMs / (double) niceMax);
            float y = chartBottom - chartHeight * ratio;
            // 最小提起 2dp，避免零值点贴基线看不见
            if (day.totalMs <= 0L) y = chartBottom; // 零值贴底
            else y = Math.min(y, chartBottom - 2f * density);
            xs[i] = centerX;
            ys[i] = y;
        }

        // 选中反选背景豹
        if (selectedIndex >= 0 && selectedIndex < n) {
            float cellLeft = chartLeft + cell * selectedIndex + 2f * density;
            float cellRight = chartLeft + cell * (selectedIndex + 1) - 2f * density;
            tmpRect.set(cellLeft, chartTop - tooltipHeight - 4f * density, cellRight, chartBottom);
            canvas.drawRoundRect(tmpRect, 10f * density, 10f * density, selectedBgPaint);
        }

        // 填充面积（折线下方粉色渐变）
        if (n >= 2) {
            fillPath.reset();
            fillPath.moveTo(xs[0], chartBottom);
            for (int i = 0; i < n; i++) {
                if (i == 0) fillPath.lineTo(xs[i], ys[i]);
                else {
                    // 用三次贝塞尔曲线让折线更平滑
                    float midX = (xs[i - 1] + xs[i]) / 2f;
                    fillPath.cubicTo(midX, ys[i - 1], midX, ys[i], xs[i], ys[i]);
                }
            }
            fillPath.lineTo(xs[n - 1], chartBottom);
            fillPath.close();
            canvas.drawPath(fillPath, lineFillPaint);

            // 折线
            linePath.reset();
            linePath.moveTo(xs[0], ys[0]);
            for (int i = 1; i < n; i++) {
                float midX = (xs[i - 1] + xs[i]) / 2f;
                linePath.cubicTo(midX, ys[i - 1], midX, ys[i], xs[i], ys[i]);
            }
            canvas.drawPath(linePath, linePaint);
        }

        // X 轴标签稀疏化
        int labelEvery = Math.max(1, (int) Math.ceil(n / 8.0));

        // 画点 + 标签
        float dotR = 3.5f * density;
        float dotSelOuterR = 7f * density;
        float dotSelInnerR = 4.5f * density;
        for (int i = 0; i < n; i++) {
            DayBucket day = days.get(i);
            boolean isSelected = i == selectedIndex;
            float centerX = xs[i];
            float pointY = ys[i];

            // 点
            if (isSelected) {
                canvas.drawCircle(centerX, pointY, dotSelOuterR, dotOuterPaint);
                canvas.drawCircle(centerX, pointY, dotSelInnerR, dotPaint);
                canvas.drawCircle(centerX, pointY, dotSelOuterR, dotSelectedPaint);
            } else if (day.totalMs > 0L) {
                canvas.drawCircle(centerX, pointY, dotR + 1f * density, dotOuterPaint);
                canvas.drawCircle(centerX, pointY, dotR, dotPaint);
            } else if (n <= 10) {
                // 零值点但稀疏时还是表示一下位置（加个空心圆）
                canvas.drawCircle(centerX, pointY, dotR, dotOuterPaint);
            }

            // X 轴标签
            boolean drawLabel = isSelected || (i % labelEvery == 0) || i == n - 1;
            if (drawLabel) {
                Paint lp = isSelected ? labelSelectedPaint : labelPaint;
                canvas.drawText(day.label, centerX, height - 6f * density, lp);
            }

            // Tooltip
            if (isSelected) {
                String text = formatShortDuration(day.totalMs);
                float textWidth = tooltipTextPaint.measureText(text);
                float tooltipPaddingH = 10f * density;
                float tooltipWidth = textWidth + tooltipPaddingH * 2f;
                float tooltipLeft = centerX - tooltipWidth / 2f;
                float tooltipRight = centerX + tooltipWidth / 2f;
                if (tooltipLeft < chartLeft) {
                    float shift = chartLeft - tooltipLeft;
                    tooltipLeft += shift;
                    tooltipRight += shift;
                }
                if (tooltipRight > width - padding) {
                    float shift = tooltipRight - (width - padding);
                    tooltipLeft -= shift;
                    tooltipRight -= shift;
                }
                float tooltipTop = pointY - tooltipHeight - 10f * density;
                if (tooltipTop < 2f * density) tooltipTop = 2f * density;
                float tooltipBot = tooltipTop + tooltipHeight;
                tmpRect.set(tooltipLeft, tooltipTop, tooltipRight, tooltipBot);
                canvas.drawRoundRect(tmpRect, tooltipHeight / 2f, tooltipHeight / 2f, tooltipPaint);

                trianglePath.reset();
                float tipBaseY = tooltipBot - 0.5f * density;
                float tipY = tooltipBot + 4f * density;
                float tipX = Math.max(tooltipLeft + 8f * density, Math.min(tooltipRight - 8f * density, centerX));
                trianglePath.moveTo(tipX - 5f * density, tipBaseY);
                trianglePath.lineTo(tipX + 5f * density, tipBaseY);
                trianglePath.lineTo(tipX, tipY);
                trianglePath.close();
                canvas.drawPath(trianglePath, tooltipPaint);

                float baseline = tooltipTop + tooltipHeight / 2f - (tooltipTextPaint.descent() + tooltipTextPaint.ascent()) / 2f;
                canvas.drawText(text, (tooltipLeft + tooltipRight) / 2f, baseline, tooltipTextPaint);
            }
        }
    }

    static String formatShortDuration(long ms) {
        long s = Math.max(0L, ms / 1000L);
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        if (h > 0L) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        if (m > 0L) return String.format(Locale.US, "%d:%02d", m, sec);
        return String.format(Locale.US, "0:%02d", sec);
    }

    /** Y 轴刷标签，紧凑格式。 */
    static String formatAxisLabel(long ms) {
        if (ms <= 0L) return "0";
        long s = ms / 1000L;
        if (s < 60L) return s + "s";
        long m = s / 60L;
        if (m < 60L) return m + "m";
        long h = m / 60L;
        long rem = m % 60L;
        if (rem == 0L) return h + "h";
        return h + "h" + rem + "m";
    }

    /** 把原始最大值向上取整到干净刻度（1s/5s…1m/5m…1h/2h…），保证 Y 轴可读。 */
    static long niceCeil(long ms) {
        if (ms <= 0L) return 1_000L;
        long[] steps = {
            1_000L,              // 1s
            5_000L,              // 5s
            10_000L,             // 10s
            20_000L,             // 20s
            30_000L,             // 30s
            60_000L,             // 1m
            2L * 60_000L,        // 2m
            5L * 60_000L,        // 5m
            10L * 60_000L,       // 10m
            15L * 60_000L,       // 15m
            20L * 60_000L,       // 20m
            30L * 60_000L,       // 30m
            60L * 60_000L,       // 1h
            2L * 60L * 60_000L,  // 2h
            3L * 60L * 60_000L,
            4L * 60L * 60_000L,
            6L * 60L * 60_000L,
            8L * 60L * 60_000L,
            12L * 60L * 60_000L,
            24L * 60L * 60_000L
        };
        for (long step : steps) {
            if (ms <= step) return step;
        }
        return ((ms + 60L * 60_000L - 1L) / (60L * 60_000L)) * (60L * 60_000L);
    }

    public static final class DayBucket {
        public final String date;
        public final String label;
        public final long totalMs;
        public final List<DeviceUsage> devices;

        public DayBucket(String date, String label, long totalMs, List<DeviceUsage> devices) {
            this.date = date;
            this.label = label;
            this.totalMs = totalMs;
            this.devices = devices == null ? new ArrayList<>() : devices;
        }
    }

    public static final class DeviceUsage {
        public final String deviceId;
        public final String deviceAlias;
        public final long totalMs;
        public final String uploadedAt;

        public DeviceUsage(String deviceId, String deviceAlias, long totalMs, String uploadedAt) {
            this.deviceId = deviceId == null ? "" : deviceId;
            this.deviceAlias = deviceAlias == null ? "" : deviceAlias;
            this.totalMs = totalMs;
            this.uploadedAt = uploadedAt == null ? "" : uploadedAt;
        }

        public String displayName() {
            if (!deviceAlias.isEmpty()) return deviceAlias;
            if (!deviceId.isEmpty()) return deviceId;
            return "unknown";
        }
    }
}
