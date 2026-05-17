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
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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

        emptyPaint.setColor(TEXT_SECONDARY);
        emptyPaint.setTextSize(12f * density);
        emptyPaint.setTextAlign(Paint.Align.CENTER);

        selectedBgPaint.setColor(PINK_HOVER_BG);

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
        float chartLeft = padding;
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
        float chartTop = padding + tooltipHeight + 8f * density;
        float chartBottom = height - labelHeight;
        float chartHeight = chartBottom - chartTop;

        canvas.drawLine(padding, chartBottom, width - padding, chartBottom, gridPaint);

        long max = 1L;
        for (DayBucket d : days) {
            max = Math.max(max, d.totalMs);
        }

        float cell = (width - padding * 2f) / days.size();
        float barWidth = Math.min(cell * 0.5f, 28f * density);

        for (int i = 0; i < days.size(); i++) {
            DayBucket day = days.get(i);
            float centerX = padding + cell * i + cell / 2f;
            boolean isSelected = i == selectedIndex;
            float left = centerX - barWidth / 2f;
            float right = centerX + barWidth / 2f;

            if (isSelected) {
                float cellLeft = padding + cell * i + 2f * density;
                float cellRight = padding + cell * (i + 1) - 2f * density;
                tmpRect.set(cellLeft, chartTop - tooltipHeight - 4f * density, cellRight, chartBottom);
                canvas.drawRoundRect(tmpRect, 10f * density, 10f * density, selectedBgPaint);
            }

            tmpRect.set(left, chartTop, right, chartBottom);
            canvas.drawRoundRect(tmpRect, barWidth / 2f, barWidth / 2f, barBgPaint);

            float ratio = (float) ((double) day.totalMs / (double) max);
            float barH = Math.max(6f * density, chartHeight * ratio);
            float top = chartBottom - barH;
            tmpRect.set(left, top, right, chartBottom);
            Paint barP = isSelected ? barSelectedPaint : barPaint;
            canvas.drawRoundRect(tmpRect, barWidth / 2f, barWidth / 2f, barP);

            if (isSelected) {
                canvas.drawRoundRect(tmpRect, barWidth / 2f, barWidth / 2f, barStrokePaint);
            }

            Paint lp = isSelected ? labelSelectedPaint : labelPaint;
            canvas.drawText(day.label, centerX, height - 6f * density, lp);

            if (isSelected) {
                String text = formatShortDuration(day.totalMs);
                float textWidth = tooltipTextPaint.measureText(text);
                float tooltipPaddingH = 10f * density;
                float tooltipWidth = textWidth + tooltipPaddingH * 2f;
                float tooltipLeft = centerX - tooltipWidth / 2f;
                float tooltipRight = centerX + tooltipWidth / 2f;
                if (tooltipLeft < padding) {
                    float shift = padding - tooltipLeft;
                    tooltipLeft += shift;
                    tooltipRight += shift;
                }
                if (tooltipRight > width - padding) {
                    float shift = tooltipRight - (width - padding);
                    tooltipLeft -= shift;
                    tooltipRight -= shift;
                }
                float tooltipTop = top - tooltipHeight - 6f * density;
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
