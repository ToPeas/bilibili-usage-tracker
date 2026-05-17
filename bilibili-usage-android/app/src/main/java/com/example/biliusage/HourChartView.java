package com.example.biliusage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 24 小时分布折线图，支持多设备分色 + 触摸 Tooltip。
 * 颜色分配规则（与 DeviceBreakdownView 保持一致）：
 *   web/browser → 粉(0)  android/app → 蓝(1)  其余按传入顺序 2/3/4
 */
public final class HourChartView extends View {

    /** 多设备调色板，索引与 DeviceBreakdownView.colorForIndex 完全同步 */
    public static final int[] LINE_COLORS = {
            0xFFFB7299, // 粉 - web/浏览器
            0xFF23ADE5, // 蓝 - Android
            0xFF52C41A, // 绿
            0xFFFA8C16, // 橙
            0xFF722ED1, // 紫
    };
    static final int[] FILL_COLORS = {
            0x2FFB7299,
            0x2523ADE5,
            0x2552C41A,
            0x25FA8C16,
            0x25722ED1,
    };

    private static final int TEXT_SECONDARY = 0xFF94A3B8;
    private static final int BORDER = 0xFFEEF1F5;
    private static final int TOOLTIP_BG = 0xF0FFFFFF;
    private static final int TOOLTIP_BORDER = 0xFFE0E0E0;

    // ── 数据模型 ────────────────────────────────────────
    public static class DeviceSeries {
        public final String label;   // 设备别名
        public final String source;  // "android" / "web" / ""
        public final long[] hours;   // 长度 24
        public final int colorIndex; // 0-4，对应 LINE_COLORS
        public DeviceSeries(String label, String source, long[] hours, int colorIndex) {
            this.label = label != null ? label : "";
            this.source = source != null ? source : "";
            this.hours = hours != null ? hours : new long[24];
            this.colorIndex = colorIndex;
        }
    }

    /**
     * @deprecated 颜色现在按设备序号分配，不再区分 source，保留仅作兼容占位。
     */
    @Deprecated
    public static int colorIndexForSource(String source, int sameSourceRank) {
        return sameSourceRank % LINE_COLORS.length;
    }

    // ── Paint ────────────────────────────────────────────
    private final Paint gridPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xLabelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint yLabelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipBgP    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipBorderP= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipTextP  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipSubP   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final RectF tmp = new RectF();
    private final float density;

    // ── 状态 ─────────────────────────────────────────────
    private List<DeviceSeries> seriesList = new ArrayList<>();
    private int hoveredHour = -1; // -1 表示无 hover

    // 缓存绘制参数（onDraw 里更新，onTouch 里读取）
    private float cChartLeft, cChartRight, cChartTop, cChartBot, cCell;
    private long cNiceMax;

    public HourChartView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);

        gridPaint.setColor(BORDER);
        gridPaint.setStrokeWidth(1f);

        xLabelPaint.setColor(TEXT_SECONDARY);
        xLabelPaint.setTextSize(10f * density);
        xLabelPaint.setTextAlign(Paint.Align.CENTER);

        yLabelPaint.setColor(TEXT_SECONDARY);
        yLabelPaint.setTextSize(9.5f * density);
        yLabelPaint.setTextAlign(Paint.Align.RIGHT);

        legendPaint.setTextSize(9f * density);
        legendPaint.setTextAlign(Paint.Align.LEFT);

        emptyPaint.setColor(TEXT_SECONDARY);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setTextSize(12f * density);

        dotOuterPaint.setColor(Color.WHITE);
        dotOuterPaint.setStyle(Paint.Style.FILL);

        tooltipBgP.setColor(TOOLTIP_BG);
        tooltipBgP.setStyle(Paint.Style.FILL);

        tooltipBorderP.setColor(TOOLTIP_BORDER);
        tooltipBorderP.setStyle(Paint.Style.STROKE);
        tooltipBorderP.setStrokeWidth(1f);

        tooltipTextP.setColor(0xFF1F1F23);
        tooltipTextP.setTextSize(11f * density);
        tooltipTextP.setFakeBoldText(true);

        tooltipSubP.setColor(0xFF6B6470);
        tooltipSubP.setTextSize(10f * density);

        crossPaint.setColor(0x40000000);
        crossPaint.setStrokeWidth(1f);
        crossPaint.setStyle(Paint.Style.STROKE);

        setMinimumHeight(Math.round(160f * density));
    }

    // ── 数据接口 ─────────────────────────────────────────
    public void setDeviceHours(List<DeviceSeries> series, String ignored) {
        seriesList = (series != null) ? series : new ArrayList<>();
        hoveredHour = -1;
        invalidate();
    }

    /** 向后兼容：单系列粉色 */
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
        if (seriesList.isEmpty() || cCell <= 0) return super.onTouchEvent(event);
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float tx = event.getX();
            if (tx < cChartLeft) tx = cChartLeft;
            if (tx > cChartRight) tx = cChartRight;
            int hour = Math.round((tx - cChartLeft) / cCell);
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

        float padTop    = 18f * density;
        float padBot    = 22f * density;
        float padRight  = 8f  * density;
        float yAxisW    = 36f * density;
        float chartLeft  = yAxisW + 4f * density;
        float chartRight = w - padRight;
        float chartTop   = padTop;
        float chartBot   = h - padBot;
        float chartH     = chartBot - chartTop;
        float chartW     = chartRight - chartLeft;

        // 缓存给 onTouchEvent
        cChartLeft = chartLeft; cChartRight = chartRight;
        cChartTop  = chartTop;  cChartBot   = chartBot;

        long rawMax = 0L;
        for (DeviceSeries s : seriesList) for (long v : s.hours) rawMax = Math.max(rawMax, v);

        if (rawMax <= 0L || seriesList.isEmpty()) {
            canvas.drawText("尚无时段数据", w / 2f, h / 2f, emptyPaint);
            return;
        }

        long niceMax = UsageChartView.niceCeil(rawMax);
        cNiceMax = niceMax;

        // 网格 + Y 轴标签
        for (int t = 0; t <= 4; t++) {
            float ratio = t / 4f;
            float y = chartBot - chartH * ratio;
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint);
            long val = (long) (niceMax * ratio);
            float bl = y - (yLabelPaint.descent() + yLabelPaint.ascent()) / 2f;
            canvas.drawText(UsageChartView.formatAxisLabel(val), chartLeft - 4f * density, bl, yLabelPaint);
        }

        int n = 24;
        float cell = chartW / (n - 1);
        cCell = cell;
        float[] txs = new float[n];
        for (int i = 0; i < n; i++) txs[i] = chartLeft + cell * i;

        // 预计算各系列 Y 坐标
        float[][] ysAll = new float[seriesList.size()][n];
        for (int si = 0; si < seriesList.size(); si++) {
            DeviceSeries s = seriesList.get(si);
            for (int i = 0; i < n; i++) {
                float ratio = (float) ((double) s.hours[i] / (double) niceMax);
                float y = chartBot - chartH * ratio;
                ysAll[si][i] = s.hours[i] <= 0L ? chartBot : Math.min(y, chartBot - 2f * density);
            }
        }

        // Hover 竖线（在图层最底）
        if (hoveredHour >= 0) {
            float hx = txs[hoveredHour];
            canvas.drawLine(hx, chartTop, hx, chartBot, crossPaint);
        }

        // 图例（右上角，从右向左）
        float lx = chartRight;
        float legendY = padTop / 2f;
        for (int si = seriesList.size() - 1; si >= 0; si--) {
            DeviceSeries s = seriesList.get(si);
            int cIdx = s.colorIndex % LINE_COLORS.length;
            String lbl = sourceLabel(s.source) + (s.label.isEmpty() ? "" : "·" + s.label);
            legendPaint.setColor(LINE_COLORS[cIdx]);
            float tw = legendPaint.measureText(lbl);
            lx -= tw + 2f * density;
            canvas.drawText(lbl, lx, legendY + legendPaint.getTextSize() * 0.7f, legendPaint);
            lx -= 12f * density;
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setColor(LINE_COLORS[cIdx]); bp.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(lx, legendY + 1.5f * density,
                    lx + 10f * density, legendY + 4.5f * density), 2, 2, bp);
            lx -= 4f * density;
        }

        // 填充（从后往前）
        for (int si = seriesList.size() - 1; si >= 0; si--) {
            DeviceSeries s = seriesList.get(si);
            int cIdx = s.colorIndex % LINE_COLORS.length;
            float[] ys = ysAll[si];
            Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
            fp.setStyle(Paint.Style.FILL);
            fp.setShader(new LinearGradient(0, chartTop, 0, chartBot,
                    FILL_COLORS[cIdx], Color.TRANSPARENT, Shader.TileMode.CLAMP));
            fillPath.reset();
            fillPath.moveTo(txs[0], chartBot);
            fillPath.lineTo(txs[0], ys[0]);
            for (int i = 1; i < n; i++) {
                float mx = (txs[i - 1] + txs[i]) / 2f;
                fillPath.cubicTo(mx, ys[i - 1], mx, ys[i], txs[i], ys[i]);
            }
            fillPath.lineTo(txs[n - 1], chartBot);
            fillPath.close();
            canvas.drawPath(fillPath, fp);
        }

        // 折线 + 圆点
        for (int si = 0; si < seriesList.size(); si++) {
            DeviceSeries s = seriesList.get(si);
            int cIdx = s.colorIndex % LINE_COLORS.length;
            float[] ys = ysAll[si];
            Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
            lp.setColor(LINE_COLORS[cIdx]);
            lp.setStyle(Paint.Style.STROKE);
            lp.setStrokeWidth(2.2f * density);
            lp.setStrokeCap(Paint.Cap.ROUND);
            lp.setStrokeJoin(Paint.Join.ROUND);
            linePath.reset();
            linePath.moveTo(txs[0], ys[0]);
            for (int i = 1; i < n; i++) {
                float mx = (txs[i - 1] + txs[i]) / 2f;
                linePath.cubicTo(mx, ys[i - 1], mx, ys[i], txs[i], ys[i]);
            }
            canvas.drawPath(linePath, lp);

            Paint dotInner = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotInner.setColor(LINE_COLORS[cIdx]); dotInner.setStyle(Paint.Style.FILL);
            float dotR = 2.6f * density;
            for (int i = 0; i < n; i++) {
                if (s.hours[i] <= 0L) continue;
                canvas.drawCircle(txs[i], ys[i], dotR + 1.2f * density, dotOuterPaint);
                canvas.drawCircle(txs[i], ys[i], dotR, dotInner);
            }
        }

        // X 轴标签
        int[] lbIdx = {0, 6, 12, 18, 23};
        for (int i : lbIdx) canvas.drawText(i + ":00", txs[i], h - 4f * density, xLabelPaint);

        // ── Tooltip ───────────────────────────────────────
        if (hoveredHour >= 0 && hoveredHour < n) {
            drawTooltip(canvas, w, h, txs[hoveredHour], chartTop, chartBot, niceMax, ysAll);
        }
    }

    private void drawTooltip(Canvas canvas, float vw, float vh,
                              float hx, float chartTop, float chartBot,
                              long niceMax, float[][] ysAll) {
        int hr = hoveredHour;
        float d = density;

        // 文本内容
        String header = hr + ":00 – " + (hr + 1) + ":00";
        // 每台设备一行
        List<String> lines = new ArrayList<>();
        List<Integer> lineColors = new ArrayList<>();
        for (int si = 0; si < seriesList.size(); si++) {
            DeviceSeries s = seriesList.get(si);
            long ms = s.hours[hr];
            if (ms <= 0L) continue;
            String nm = s.label.isEmpty() ? sourceLabel(s.source) : (sourceLabel(s.source) + " " + s.label);
            lines.add(nm + "  " + UsageChartView.formatShortDuration(ms));
            lineColors.add(s.colorIndex % LINE_COLORS.length);
        }
        if (lines.isEmpty()) lines.add("无使用记录");

        // 尺寸计算
        float pad = 7f * d;
        float lineH = tooltipSubP.getTextSize() + 3f * d;
        float headerH = tooltipTextP.getTextSize() + 4f * d;
        float boxH = pad + headerH + lines.size() * lineH + pad;
        float maxTw = tooltipTextP.measureText(header);
        for (String ln : lines) maxTw = Math.max(maxTw, tooltipSubP.measureText(ln) + 14f * d);
        float boxW = maxTw + pad * 2f;

        // 定位（不超出 View 边界）
        float bx = hx + 10f * d;
        if (bx + boxW > vw - 4f * d) bx = hx - boxW - 10f * d;
        bx = Math.max(4f * d, Math.min(bx, vw - boxW - 4f * d));
        float by = chartTop + 4f * d;
        if (by + boxH > chartBot) by = chartBot - boxH;

        // 阴影
        tooltipBgP.setShadowLayer(4f * d, 0, 2f * d, 0x20000000);
        tmp.set(bx, by, bx + boxW, by + boxH);
        canvas.drawRoundRect(tmp, 8f * d, 8f * d, tooltipBgP);
        tooltipBgP.clearShadowLayer();
        canvas.drawRoundRect(tmp, 8f * d, 8f * d, tooltipBorderP);

        // Header
        float ty = by + pad + tooltipTextP.getTextSize();
        canvas.drawText(header, bx + pad, ty, tooltipTextP);
        ty += headerH;

        // 各设备行
        for (int i = 0; i < lines.size(); i++) {
            int cIdx = i < lineColors.size() ? lineColors.get(i) : 0;
            // 小圆点
            Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
            dp.setColor(LINE_COLORS[cIdx]); dp.setStyle(Paint.Style.FILL);
            canvas.drawCircle(bx + pad + 3f * d, ty - tooltipSubP.getTextSize() / 2f, 3.5f * d, dp);
            tooltipSubP.setColor(0xFF1F1F23);
            canvas.drawText(lines.get(i), bx + pad + 12f * d, ty, tooltipSubP);
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
