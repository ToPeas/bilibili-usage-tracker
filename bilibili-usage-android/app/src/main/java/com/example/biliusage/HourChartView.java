package com.example.biliusage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 24 小时分布柱状图，支持多设备堆叠色块 + 触摸 Tooltip。
 * Y 轴最大值固定为 1 小时，超过 1 小时时撑开到实际最大值。
 */
public final class HourChartView extends View {

    /** 多设备调色板，索引与 DeviceBreakdownView.colorForIndex 完全同步 */
    public static final int[] LINE_COLORS = {
            0xFFFB7299, // 粉
            0xFF23ADE5, // 蓝
            0xFF52C41A, // 绿
            0xFFFA8C16, // 橙
            0xFF722ED1, // 紫
    };

    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final int  TEXT_SECONDARY  = 0xFF94A3B8;
    private static final int  GRID_COLOR      = 0xFFEEF1F5;
    private static final int  TOOLTIP_BG      = 0xF0FFFFFF;
    private static final int  TOOLTIP_BORDER  = 0xFFE0E0E0;

    // ── 数据模型 ────────────────────────────────────────
    public static class DeviceSeries {
        public final String label;
        public final String source;
        public final long[] hours;   // 长度 24
        public final int colorIndex;
        public DeviceSeries(String label, String source, long[] hours, int colorIndex) {
            this.label = label != null ? label : "";
            this.source = source != null ? source : "";
            this.hours = hours != null ? hours : new long[24];
            this.colorIndex = colorIndex;
        }
    }

    /** @deprecated 颜色现在按设备序号分配，保留兼容 */
    @Deprecated
    public static int colorIndexForSource(String source, int sameSourceRank) {
        return sameSourceRank % LINE_COLORS.length;
    }

    // ── Paint ────────────────────────────────────────────
    private final Paint gridPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xLabelPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint yLabelPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipBgP     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipBorderP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipTextP   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipSubP    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect        = new RectF();

    private final float density;

    // ── 状态 ─────────────────────────────────────────────
    private List<DeviceSeries> seriesList = new ArrayList<>();
    private int hoveredHour = -1;

    // 缓存绘制参数供 onTouchEvent 使用
    private float cChartLeft, cChartRight, cChartTop, cChartBot;
    private float cColStep; // 每列中心之间的距离

    public HourChartView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);

        gridPaint.setColor(GRID_COLOR);
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

        tooltipBgP.setColor(TOOLTIP_BG);
        tooltipBgP.setStyle(Paint.Style.FILL);

        tooltipBorderP.setColor(TOOLTIP_BORDER);
        tooltipBorderP.setStyle(Paint.Style.STROKE);
        tooltipBorderP.setStrokeWidth(1f);

        tooltipTextP.setColor(0xFF1F1F23);
        tooltipTextP.setTextSize(11f * density);
        tooltipTextP.setFakeBoldText(true);

        tooltipSubP.setColor(0xFF1F1F23);
        tooltipSubP.setTextSize(10f * density);

        barPaint.setStyle(Paint.Style.FILL);

        setMinimumHeight(Math.round(160f * density));
    }

    // ── 数据接口 ─────────────────────────────────────────
    public void setDeviceHours(List<DeviceSeries> series, String ignored) {
        seriesList = (series != null) ? series : new ArrayList<>();
        hoveredHour = -1;
        invalidate();
    }

    public void setHours(long[] values) {
        long[] h = new long[24];
        if (values != null) for (int i = 0; i < 24; i++) h[i] = i < values.length ? values[i] : 0L;
        List<DeviceSeries> list = new ArrayList<>();
        list.add(new DeviceSeries("", "web", h, 0));
        seriesList = list;
        hoveredHour = -1;
        invalidate();
    }

    // ── 触摸处理 ─────────────────────────────────────────
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (seriesList.isEmpty() || cColStep <= 0) return super.onTouchEvent(event);
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float tx = event.getX();
            // 每列中心在 cChartLeft + colStep * h + colStep/2
            int hour = (int) ((tx - cChartLeft) / cColStep);
            hour = Math.max(0, Math.min(23, hour));
            if (hour != hoveredHour) { hoveredHour = hour; invalidate(); }
            return true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            hoveredHour = -1;
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }

    // ── 绘制 ─────────────────────────────────────────────
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();

        float padTop    = 8f * density;
        float padBot    = 22f * density;  // X 轴标签
        float padRight  = 0f;
        float yAxisW    = 18f * density;
        float chartLeft  = yAxisW;
        float chartRight = w - padRight;
        float chartTop   = padTop;
        float chartBot   = h - padBot;
        float chartH     = chartBot - chartTop;
        float chartW     = chartRight - chartLeft;

        // 缓存给 onTouchEvent
        cChartLeft  = chartLeft;
        cChartRight = chartRight;
        cChartTop   = chartTop;
        cChartBot   = chartBot;

        long rawMax = 0L;
        for (DeviceSeries s : seriesList) for (long v : s.hours) rawMax = Math.max(rawMax, v);

        if (rawMax <= 0L || seriesList.isEmpty()) {
            canvas.drawText("尚无时段数据", w / 2f, h / 2f, emptyPaint);
            return;
        }

        // Y 轴最大值按每小时堆叠总量计算，避免多设备同小时叠加后冲出图表。
        long maxStack = 0L;
        for (int hr = 0; hr < 24; hr++) {
            long sum = 0L;
            for (DeviceSeries s : seriesList) sum += s.hours[hr];
            maxStack = Math.max(maxStack, sum);
        }
        long yMax = Math.max(ONE_HOUR_MS, maxStack);

        // 列宽 & 间隙
        int n = 24;
        float colStep = chartW / n;       // 每列宽度（含间隙）
        float barW    = Math.max(4f * density, colStep * 0.72f);  // 实际柱宽
        float gap     = (colStep - barW) / 2f;
        cColStep = colStep;

        // ── 网格 + Y 轴标签（4 条网格线 = 0/15/30/45/60 分钟）──
        gridPaint.setColor(GRID_COLOR);
        gridPaint.setStrokeWidth(1f);
        for (int t = 0; t <= 4; t++) {
            float ratio = t / 4f;
            float y = chartBot - chartH * ratio;
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint);
            long val = (long) (yMax * ratio);
            float bl = y - (yLabelPaint.descent() + yLabelPaint.ascent()) / 2f;
            canvas.drawText(UsageChartView.formatAxisLabel(val),
                    chartLeft - 4f * density, bl, yLabelPaint);
        }

        // ── 柱子（堆叠，每小时从底往上叠各设备色块）──
        for (int hr = 0; hr < n; hr++) {
            float barLeft  = chartLeft + colStep * hr + gap;
            float barRight = barLeft + barW;
            float stackBot = chartBot;

            // hover 高亮背景
            if (hr == hoveredHour) {
                Paint hlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                hlPaint.setColor(0x0A000000);
                hlPaint.setStyle(Paint.Style.FILL);
                tmpRect.set(barLeft - gap * 0.5f, chartTop, barRight + gap * 0.5f, chartBot);
                canvas.drawRoundRect(tmpRect, 3f * density, 3f * density, hlPaint);
            }

            for (DeviceSeries s : seriesList) {
                long ms = s.hours[hr];
                if (ms <= 0L) continue;
                float ratio = (float) ((double) ms / (double) yMax);
                float segH  = Math.max(2f * density, chartH * ratio);
                float segTop = stackBot - segH;

                int cIdx = s.colorIndex % LINE_COLORS.length;
                barPaint.setColor(LINE_COLORS[cIdx]);
                barPaint.setAlpha(hr == hoveredHour ? 245 : 220);

                tmpRect.set(barLeft, segTop, barRight, stackBot);
                canvas.drawRect(tmpRect, barPaint);
                stackBot = segTop;
            }
        }

        // ── X 轴标签 ──
        int[] lbIdx = {0, 6, 12, 18, 23};
        for (int i : lbIdx) {
            float cx = chartLeft + colStep * i + colStep / 2f;
            canvas.drawText(i + "", cx, h - 4f * density, xLabelPaint);
        }

        // ── Tooltip ──
        if (hoveredHour >= 0 && hoveredHour < n) {
            float cx = chartLeft + colStep * hoveredHour + colStep / 2f;
            drawTooltip(canvas, w, h, cx, chartTop, chartBot, yMax);
        }
    }

    private void drawTooltip(Canvas canvas, float vw, float vh,
                              float hx, float chartTop, float chartBot, long yMax) {
        int hr = hoveredHour;
        float d = density;

        String header = hr + ":00 – " + (hr + 1) + ":00";
        List<String> lines = new ArrayList<>();
        List<Integer> lineColors = new ArrayList<>();
        for (DeviceSeries s : seriesList) {
            long ms = s.hours[hr];
            if (ms <= 0L) continue;
            String nm = s.label.isEmpty() ? sourceLabel(s.source) : (sourceLabel(s.source) + "·" + s.label);
            lines.add(nm + "  " + UsageChartView.formatShortDuration(ms));
            lineColors.add(s.colorIndex % LINE_COLORS.length);
        }
        if (lines.isEmpty()) lines.add("无使用记录");

        float pad   = 7f * d;
        float lineH = tooltipSubP.getTextSize() + 4f * d;
        float hdrH  = tooltipTextP.getTextSize() + 5f * d;
        float boxH  = pad + hdrH + lines.size() * lineH + pad;
        float maxTw = tooltipTextP.measureText(header);
        for (String ln : lines) maxTw = Math.max(maxTw, tooltipSubP.measureText(ln) + 14f * d);
        float boxW = maxTw + pad * 2f;

        float bx = hx + 8f * d;
        if (bx + boxW > vw - 4f * d) bx = hx - boxW - 8f * d;
        bx = Math.max(4f * d, Math.min(bx, vw - boxW - 4f * d));
        float by = chartTop + 4f * d;
        if (by + boxH > chartBot) by = chartBot - boxH;

        tooltipBgP.setShadowLayer(4f * d, 0, 2f * d, 0x20000000);
        tmpRect.set(bx, by, bx + boxW, by + boxH);
        canvas.drawRoundRect(tmpRect, 8f * d, 8f * d, tooltipBgP);
        tooltipBgP.clearShadowLayer();
        canvas.drawRoundRect(tmpRect, 8f * d, 8f * d, tooltipBorderP);

        float ty = by + pad + tooltipTextP.getTextSize();
        canvas.drawText(header, bx + pad, ty, tooltipTextP);
        ty += hdrH;

        for (int i = 0; i < lines.size(); i++) {
            int cIdx = i < lineColors.size() ? lineColors.get(i) : 0;
            Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
            dp.setColor(LINE_COLORS[cIdx]);
            dp.setStyle(Paint.Style.FILL);
            canvas.drawCircle(bx + pad + 3.5f * d, ty - tooltipSubP.getTextSize() / 2f, 3.5f * d, dp);
            tooltipSubP.setColor(0xFF1F1F23);
            canvas.drawText(lines.get(i), bx + pad + 13f * d, ty, tooltipSubP);
            ty += lineH;
        }
    }

    static String sourceLabel(String source) {
        if (source == null) return "";
        switch (source.toLowerCase()) {
            case "android": case "app": return "Android";
            case "web": case "browser": case "chrome": return "浏览器";
            default: return source.isEmpty() ? "" : source;
        }
    }
}
