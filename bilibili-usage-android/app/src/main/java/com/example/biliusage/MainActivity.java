package com.example.biliusage;

import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends android.app.Activity {

    private static final int COLOR_BG = 0xFFF7F8FB;
    private static final int COLOR_CARD_BORDER = 0xFFEEF1F5;
    private static final int COLOR_TEXT_PRIMARY = 0xFF0F172A;
    private static final int COLOR_TEXT_SECONDARY = 0xFF64748B;
    private static final int COLOR_TEXT_MUTED = 0xFF94A3B8;
    private static final int COLOR_PINK = 0xFFFB7299;
    private static final int COLOR_SUCCESS = 0xFF16A34A;
    private static final int COLOR_WARNING = 0xFFB45309;
    private static final int COLOR_DANGER = 0xFFB91C1C;

    private EditText accountId;
    private EditText databaseId;
    private EditText apiToken;
    private EditText deviceId;
    private EditText deviceAlias;

    private TextView heroTodayValue;
    private TextView heroTodaySubtitle;
    private TextView heroAllDevicesValue;
    private TextView heroAllDevicesSubtitle;
    private TextView heroPermissionChip;
    private TextView heroDeviceChip;
    private TextView statusBar;
    private TextView selectedDayTitle;
    private TextView selectedDayMeta;
    private TextView databaseLabel;
    private TextView chartHint;
    private TextView rangeLabel;
    private TextView hourCardSubtitle;
    private UsageChartView chart;
    private DeviceBreakdownView deviceBreakdown;
    private HourChartView hourChart;
    private LinearLayout rangeTabsRow;

    private int currentRangeDays = 7;
    private List<UsageChartView.DayBucket> currentDays = new ArrayList<>();
    /** 用户当前选中的日期（yyyy-MM-dd），用于刷新后恢复选中，避免跳动 */
    private String selectedDay = null;
    private int chartLoadToken = 0;
    private int hourLoadToken = 0;
    /**
     * 上传互斥锁：防止「进入 app 自动上传」和「手动点按钮上传」同时执行，
     * 导致并发访问 D1 API 时出现冲突或错误。
     */
    private final AtomicBoolean uploadingNow = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DailyUploadReceiver.scheduleNext(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildHeader());
        root.addView(spacer(14));
        root.addView(buildHeroCard());
        root.addView(spacer(14));
        root.addView(buildChartCard());
        root.addView(spacer(14));
        root.addView(buildDeviceCard());
        root.addView(spacer(14));
        root.addView(buildActionCard());
        root.addView(spacer(14));
        root.addView(buildSettingsCard());

        setContentView(scroll);
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTodayCard();
        // 进入 app 后：
        //   1) 立刻拉一次 D1，以立刻展示今天可能已由 Chrome 插件/其他设备上传的数据。
        //   2) 并行后台上传本机今日，完成后静默再拉一次 D1 推表。
        // 这样「有没有今日数据」不会被 Android 本机上传是否成功决定。
        runAsync(() -> {
            loadRecentChart();
            return null; // 不强这条状态
        });
        scheduleBackgroundTodayUpload();
    }

    /** 后台上传今天数据，上传后静默重拉 D1，不跳「执行中」状态。 */
    private void scheduleBackgroundTodayUpload() {
        SettingsStore settings = SettingsStore.load(this);
        if (!settings.isCloudConfigured()) return;
        if (!UsageCollector.hasUsageAccess(this)) return;
        // 如果已有上传任务在跑（例如用户刚点了按钮），跳过自动上传，避免并发冲突
        if (!uploadingNow.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                DailyUploadReceiver.upload(this, true, 1);
            } catch (Exception ignore) {
                // 失败不报错，避免干扰用户
            } finally {
                uploadingNow.set(false);
            }
            // 上传完重拉 D1（不用 runAsync，避免覆盖其它状态提示）
            try {
                loadRecentChart();
            } catch (Exception ignore) {
                // 拉取失败时保持原图
            }
            // 上传后重新刷一下 hero 卡的「全设备总计」让数字看到本机上传金额
            runOnUiThread(this::refreshTodayCard);
        }).start();
    }

    // ---------- 顶栏 ----------

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dot.setLayoutParams(dotLp);
        dot.setBackgroundColor(COLOR_PINK);

        TextView t = new TextView(this);
        t.setText("Bilibili Usage");
        t.setTextColor(COLOR_TEXT_PRIMARY);
        t.setTextSize(20f);
        t.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.leftMargin = dp(8);
        t.setLayoutParams(tLp);

        TextView sub = new TextView(this);
        sub.setText(" · 三端使用时长");
        sub.setTextColor(COLOR_TEXT_MUTED);
        sub.setTextSize(13f);

        row.addView(dot);
        row.addView(t);
        row.addView(sub);
        return row;
    }

    // ---------- Hero 卡 ----------

    private View buildHeroCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(getResources().getIdentifier("hero_bg", "drawable", getPackageName()));
        card.setPadding(dp(20), dp(20), dp(20), dp(20));

        // 上半：两列 «本机今日» 与 «全设备今日总计»
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(split, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 左列：全平台今日（D1 全设备合计，与 24h 图一致）
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        split.addView(leftCol, leftLp);

        TextView labelLeft = new TextView(this);
        labelLeft.setText("全平台 B 站 · 今日");
        labelLeft.setTextColor(0xFFFFFFFF);
        labelLeft.setAlpha(0.92f);
        labelLeft.setTextSize(12f);
        leftCol.addView(labelLeft);

        heroTodayValue = new TextView(this);
        heroTodayValue.setText("0:00");
        heroTodayValue.setTextColor(0xFFFFFFFF);
        heroTodayValue.setTextSize(34f);
        heroTodayValue.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = dp(2);
        leftCol.addView(heroTodayValue, vLp);

        heroTodaySubtitle = new TextView(this);
        heroTodaySubtitle.setText("等待读取...");
        heroTodaySubtitle.setTextColor(0xFFFFFFFF);
        heroTodaySubtitle.setAlpha(0.92f);
        heroTodaySubtitle.setTextSize(11f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(4);
        leftCol.addView(heroTodaySubtitle, sLp);

        // 分隔线
        View divider = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT);
        divLp.leftMargin = dp(12);
        divLp.rightMargin = dp(12);
        divider.setBackgroundColor(0x33FFFFFF);
        split.addView(divider, divLp);

        // 右列：全设备今日总计
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        split.addView(rightCol, rightLp);

        TextView labelRight = new TextView(this);
        labelRight.setText("本机实时 · 今日");
        labelRight.setTextColor(0xFFFFFFFF);
        labelRight.setAlpha(0.92f);
        labelRight.setTextSize(12f);
        rightCol.addView(labelRight);

        heroAllDevicesValue = new TextView(this);
        heroAllDevicesValue.setText("0:00");
        heroAllDevicesValue.setTextColor(0xFFFFFFFF);
        heroAllDevicesValue.setTextSize(34f);
        heroAllDevicesValue.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams vRLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vRLp.topMargin = dp(2);
        rightCol.addView(heroAllDevicesValue, vRLp);

        heroAllDevicesSubtitle = new TextView(this);
        heroAllDevicesSubtitle.setText("等待读取 D1...");
        heroAllDevicesSubtitle.setTextColor(0xFFFFFFFF);
        heroAllDevicesSubtitle.setAlpha(0.92f);
        heroAllDevicesSubtitle.setTextSize(11f);
        LinearLayout.LayoutParams sRLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sRLp.topMargin = dp(4);
        rightCol.addView(heroAllDevicesSubtitle, sRLp);

        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = dp(14);
        card.addView(chipRow, chipLp);

        heroPermissionChip = buildChipOnHero("权限：未授权");
        heroDeviceChip = buildChipOnHero("设备：未命名");
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLp.rightMargin = dp(8);
        chipRow.addView(heroPermissionChip, pLp);
        chipRow.addView(heroDeviceChip);

        return card;
    }

    private TextView buildChipOnHero(String text) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextColor(0xFFFFFFFF);
        c.setTextSize(11f);
        c.setPadding(dp(10), dp(5), dp(10), dp(5));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(999));
        bg.setColor(0x33FFFFFF);
        c.setBackground(bg);
        return c;
    }

    // ---------- 趋势图表卡 ----------

    private View buildChartCard() {
        LinearLayout card = card();

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(titleRow);

        TextView title = sectionTitle("使用时长趋势");
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(title, titleLp);

        databaseLabel = new TextView(this);
        databaseLabel.setText("未配置 D1");
        databaseLabel.setTextColor(COLOR_TEXT_MUTED);
        databaseLabel.setTextSize(11f);
        databaseLabel.setSingleLine(true);
        databaseLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        databaseLabel.setMaxWidth(dp(180));
        titleRow.addView(databaseLabel);

        rangeLabel = new TextView(this);
        rangeLabel.setText("范围：最近 7 天");
        rangeLabel.setTextColor(COLOR_TEXT_SECONDARY);
        rangeLabel.setTextSize(11f);
        LinearLayout.LayoutParams rangeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rangeLp.topMargin = dp(4);
        card.addView(rangeLabel, rangeLp);

        rangeTabsRow = new LinearLayout(this);
        rangeTabsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rtLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rtLp.topMargin = dp(8);
        rtLp.bottomMargin = dp(6);
        card.addView(rangeTabsRow, rtLp);
        appendRangeTab(rangeTabsRow, 7, "7 天");
        appendRangeTab(rangeTabsRow, 30, "30 天");
        appendRangeTab(rangeTabsRow, 90, "3 个月");
        appendRangeTab(rangeTabsRow, 180, "半年");

        chartHint = new TextView(this);
        chartHint.setText("点击/拖动柱条查看某一天的设备 + 24 小时分布");
        chartHint.setTextColor(COLOR_TEXT_MUTED);
        chartHint.setTextSize(11f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(4);
        hintLp.bottomMargin = dp(6);
        card.addView(chartHint, hintLp);

        chart = new UsageChartView(this);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180));
        card.addView(chart, chartLp);

        chart.setOnDaySelectedListener((index, bucket) -> {
            selectedDay = bucket.date; // 记住用户选中，刷新时不跳动
            refreshDeviceCard(bucket);
            loadHoursForSelected(bucket);
        });

        return card;
    }

    private void appendRangeTab(LinearLayout row, int days, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12f);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> {
            if (currentRangeDays == days) return;
            currentRangeDays = days;
            updateRangeTabsUi();
            loadRecentChartQuietly();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        t.setTag(days);
        row.addView(t);
        updateRangeTabStyle(t, days == currentRangeDays);
    }

    private void updateRangeTabsUi() {
        if (rangeTabsRow == null) return;
        for (int i = 0; i < rangeTabsRow.getChildCount(); i++) {
            View child = rangeTabsRow.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            Integer days = (Integer) child.getTag();
            if (days == null) continue;
            updateRangeTabStyle((TextView) child, days == currentRangeDays);
        }
        if (rangeLabel != null) rangeLabel.setText("范围：" + labelOfRange(currentRangeDays));
    }

    private void updateRangeTabStyle(TextView tv, boolean active) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(999));
        if (active) {
            bg.setColor(COLOR_PINK);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTypeface(null, Typeface.BOLD);
        } else {
            bg.setColor(0xFFFFE3EC);
            tv.setTextColor(0xFFE15D88);
            tv.setTypeface(null, Typeface.NORMAL);
        }
        tv.setBackground(bg);
    }

    private String labelOfRange(int days) {
        if (days >= 180) return "最近半年";
        if (days >= 90) return "最近 3 个月";
        if (days >= 30) return "最近 30 天";
        return "最近 " + days + " 天";
    }

    // ---------- 选中日期详情卡 ----------

    private View buildDeviceCard() {
        LinearLayout card = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(left, leftLp);

        selectedDayTitle = new TextView(this);
        selectedDayTitle.setText("选中日期");
        selectedDayTitle.setTextColor(COLOR_TEXT_PRIMARY);
        selectedDayTitle.setTextSize(20f);
        selectedDayTitle.setTypeface(null, Typeface.BOLD);
        left.addView(selectedDayTitle);

        selectedDayMeta = new TextView(this);
        selectedDayMeta.setText("请先从上方图表选择一天");
        selectedDayMeta.setTextColor(COLOR_TEXT_SECONDARY);
        selectedDayMeta.setTextSize(12f);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(2);
        left.addView(selectedDayMeta, metaLp);

        hourChart = new HourChartView(this);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(188));
        hLp.topMargin = dp(12);
        card.addView(hourChart, hLp);

        hourCardSubtitle = new TextView(this);
        hourCardSubtitle.setText("");
        hourCardSubtitle.setTextColor(COLOR_TEXT_SECONDARY);
        hourCardSubtitle.setTextSize(12f);

        deviceBreakdown = new DeviceBreakdownView(this);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(4);
        card.addView(deviceBreakdown, bLp);

        return card;
    }

    // ---------- 操作卡 ----------

    private View buildActionCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("操作"));

        statusBar = new TextView(this);
        statusBar.setText("");
        statusBar.setTextColor(COLOR_TEXT_SECONDARY);
        statusBar.setTextSize(12f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(4);
        sLp.bottomMargin = dp(10);
        card.addView(statusBar, sLp);

        Button refreshBtn = primaryButton("刷新当前范围的趋势图");
        refreshBtn.setOnClickListener(v -> runAsync(() -> {
            saveSettings();
            loadRecentChart();
            return "图表已刷新";
        }));
        card.addView(refreshBtn);

        card.addView(spacer(8));

        Button uploadBtn = primaryButton("上传当前范围（含今天）");
        uploadBtn.setOnClickListener(v -> runAsyncUpload(() -> DailyUploadReceiver.upload(this, true, currentRangeDays)));
        card.addView(uploadBtn);

        card.addView(spacer(8));

        Button sync7dBtn = primaryButton("同步最近 7 天");
        sync7dBtn.setOnClickListener(v -> runAsyncUpload(() -> {
            String result = DailyUploadReceiver.upload(this, true, 7);
            loadRecentChart();
            return result;
        }));
        card.addView(sync7dBtn);

        card.addView(spacer(8));

        Button backfillBtn = secondaryButton("补传最近半年（后台）");
        backfillBtn.setOnClickListener(v -> runAsyncUpload(() -> DailyUploadReceiver.upload(this, true, DailyUploadReceiver.BACKFILL_DAYS)));
        card.addView(backfillBtn);

        card.addView(spacer(8));

        Button permissionBtn = secondaryButton("打开使用情况权限");
        permissionBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        card.addView(permissionBtn);

        card.addView(spacer(8));

        Button testBtn = secondaryButton("测试 D1 读写");
        testBtn.setOnClickListener(v -> runAsync(() -> {
            saveSettings();
            SettingsStore settings = SettingsStore.load(this);
            new D1Client(settings).test();
            return "D1 可读可写";
        }));
        card.addView(testBtn);

        return card;
    }

    // ---------- 设置卡 ----------

    private View buildSettingsCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("D1 连接设置"));

        TextView desc = new TextView(this);
        desc.setText("填入 Cloudflare 账户信息后，APP 才能上传与读取设备使用时长。");
        desc.setTextColor(COLOR_TEXT_SECONDARY);
        desc.setTextSize(12f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.bottomMargin = dp(8);
        card.addView(desc, descLp);

        accountId = inputField("Cloudflare Account ID", false);
        databaseId = inputField("D1 Database ID", false);
        apiToken = inputField("Cloudflare API Token", true);
        deviceId = inputField("Device ID", false);
        deviceAlias = inputField("Device Alias（如 MacBook / Orion）", false);

        card.addView(labeled("Account ID", accountId));
        card.addView(labeled("Database ID", databaseId));
        card.addView(labeled("API Token", apiToken));
        card.addView(labeled("Device ID", deviceId));
        card.addView(labeled("Device Alias", deviceAlias));

        Button save = primaryButton("保存设置");
        save.setOnClickListener(v -> {
            saveSettings();
            showStatus("已保存设置", false);
        });
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(4);
        card.addView(save, saveLp);

        return card;
    }

    // ---------- 数据流 ----------

    private void loadSettings() {
        SettingsStore settings = SettingsStore.load(this);
        accountId.setText(settings.accountId);
        databaseId.setText(settings.databaseId);
        apiToken.setText(settings.apiToken);
        deviceId.setText(settings.deviceId);
        deviceAlias.setText(settings.deviceAlias);
        refreshTodayCard();
    }

    private void saveSettings() {
        SettingsStore.save(
                this,
                accountId.getText().toString().trim(),
                databaseId.getText().toString().trim(),
                apiToken.getText().toString().trim(),
                deviceId.getText().toString().trim(),
                deviceAlias.getText().toString().trim()
        );
        refreshTodayCard();
    }

    private void refreshTodayCard() {
        boolean access = UsageCollector.hasUsageAccess(this);
        SettingsStore settings = SettingsStore.load(this);

        heroPermissionChip.setText(access ? "权限：已授权" : "权限：未授权");
        heroPermissionChip.setAlpha(1f);
        heroDeviceChip.setText("设备：" + (settings.deviceAlias.isEmpty() ? "未命名" : settings.deviceAlias));

        databaseLabel.setText(settings.isCloudConfigured()
                ? mask(settings.accountId) + " · " + mask(settings.databaseId)
                : "未配置 D1");

        long localTotal = 0L;
        try {
            JSONObject payload = UsageCollector.collectDay(this, settings, Calendar.getInstance());
            localTotal = payload.getLong("totalMs");
            // 副标题展示本机各 App 详情
            heroTodaySubtitle.setText(buildTodayBundleSummary(payload));
        } catch (Exception e) {
            heroTodaySubtitle.setText("读取系统使用情况失败：" + (e.getMessage() == null ? "" : e.getMessage()));
        }
        // 左上角大数字先用本机值占位，等 D1 回来后 refreshAllDevicesTodayChip 会更新为全设备总计
        heroTodayValue.setText(formatBig(localTotal));

        // 右侧「全设备今日总计」需要从 D1 拉取，后台线程执行
        refreshAllDevicesTodayChip(settings, localTotal);
    }

    /**
     * 从 D1 拉取今日所有设备总计（含本机、含 Chrome 插件上传）。
     * D1 拿到后：
     *   - 左上角大字（heroTodayValue）= 全平台总计（与 24h 图一致）
     *   - 右侧（heroAllDevicesValue）= 本机实时数据
     */
    private void refreshAllDevicesTodayChip(SettingsStore settings, long localTotal) {
        if (heroAllDevicesValue == null) return;
        // 右侧始终展示本机实时
        heroAllDevicesValue.setText(formatBig(localTotal));
        heroAllDevicesSubtitle.setText("本机实时");

        if (!settings.isCloudConfigured()) {
            // 没有 D1，左上角也用本机
            heroTodayValue.setText(formatBig(localTotal));
            return;
        }
        String today = formatDate(Calendar.getInstance());
        String selfId = settings.deviceId;
        new Thread(() -> {
            try {
                JSONArray rows = new D1Client(settings).queryRecentDays(today, today);
                long sumOthers = 0L;
                long selfRow = 0L;
                int deviceCount = 0;
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        String date = row.optString("date", "");
                        if (!today.equals(date)) continue;
                        long total = row.optLong("totalMs", 0L);
                        String id = row.optString("deviceId", "");
                        if (selfId.equals(id)) selfRow += total;
                        else sumOthers += total;
                        deviceCount++;
                    }
                }
                // 本机取 D1 与本地实时的较大者（未上传时用本地兑底）
                long localShown = Math.max(selfRow, localTotal);
                long grandTotal = sumOthers + localShown;
                int fDeviceCount = deviceCount;
                long fGrand = grandTotal;
                long fLocalShown = localShown;
                long fSumOthers = sumOthers;
                long fSelfRow = selfRow;
                runOnUiThread(() -> {
                    // 左上角 = 全平台总计（与 24h 图一致）
                    heroTodayValue.setText(formatBig(fGrand));
                    // 右侧 = 本机实时，注明贡献
                    StringBuilder sb = new StringBuilder();
                    if (fDeviceCount > 0) sb.append(fDeviceCount).append(" 台");
                    else sb.append("仅本机");
                    if (fSumOthers > 0L) sb.append(" · 其他 ").append(formatDuration(fSumOthers));
                    if (fLocalShown > fSelfRow) sb.append(" · 含未上传");
                    heroAllDevicesSubtitle.setText(sb.toString());
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "拉取失败" : e.getMessage();
                runOnUiThread(() -> heroAllDevicesSubtitle.setText("D1 读取失败：" + msg));
            }
        }).start();
    }

    private String buildTodayBundleSummary(JSONObject payload) throws Exception {
        JSONArray items = payload.optJSONArray("items");
        if (items == null || items.length() == 0) return "今天 B 站零点击，加油 🎉";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (sb.length() > 0) sb.append(" · ");
            sb.append(simpleBundle(item.getString("bundle"))).append(" ")
                    .append(formatDuration(item.getLong("durationMs")));
        }
        return sb.toString();
    }

    private String simpleBundle(String bundle) {
        if (bundle == null) return "";
        switch (bundle) {
            case "tv.danmaku.bili": return "B站";
            case "tv.danmaku.bilibilihd": return "B站HD";
            case "com.bilibili.app.in": return "海外版";
            default: return bundle;
        }
    }

    private void loadRecentChart() throws Exception {
        SettingsStore settings = SettingsStore.load(this);
        final int token;
        synchronized (this) {
            chartLoadToken += 1;
            token = chartLoadToken;
        }
        if (!settings.isCloudConfigured()) {
            runOnUiThread(() -> {
                if (token != chartLoadToken) return;
                currentDays = new ArrayList<>();
                chart.setDays(currentDays);
                deviceBreakdown.setData("按设备拆分", Collections.emptyList());
                selectedDayTitle.setText("请先配置 D1");
                selectedDayMeta.setText("填写 Cloudflare 信息后才能展示历史数据");
                if (hourChart != null) hourChart.setDeviceHours(null, null);
                if (hourCardSubtitle != null) hourCardSubtitle.setText("请先配置 D1");
            });
            return;
        }
        int days = Math.max(1, currentRangeDays);
        Calendar toCal = Calendar.getInstance();
        Calendar fromCal = Calendar.getInstance();
        fromCal.add(Calendar.DAY_OF_MONTH, -(days - 1));
        String from = formatDate(fromCal);
        String to = formatDate(toCal);

        JSONArray cachedRows = UsageCache.getRecentRows(this, settings, from, to);
        if (cachedRows != null) {
            List<UsageChartView.DayBucket> cachedBuckets = buildBucketsFromRows(settings, cachedRows, days, fromCal, toCal);
            applyBuckets(cachedBuckets, days, token);
        }

        JSONArray rows = new D1Client(settings).queryRecentDays(from, to);
        UsageCache.putRecentRows(this, settings, from, to, rows);
        List<UsageChartView.DayBucket> buckets = buildBucketsFromRows(settings, rows, days, fromCal, toCal);
        applyBuckets(buckets, days, token);
    }

    private List<UsageChartView.DayBucket> buildBucketsFromRows(SettingsStore settings, JSONArray rows, int days,
                                                                 Calendar fromCal, Calendar toCal) throws Exception {
        Map<String, List<UsageChartView.DeviceUsage>> devicesByDate = new LinkedHashMap<>();
        Map<String, Long> totalsByDate = new LinkedHashMap<>();

        Calendar cursor = (Calendar) fromCal.clone();
        for (int i = 0; i < days; i++) {
            String date = formatDate(cursor);
            devicesByDate.put(date, new ArrayList<>());
            totalsByDate.put(date, 0L);
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }

        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String date = row.optString("date");
                if (!devicesByDate.containsKey(date)) continue;
                long totalMs = row.optLong("totalMs", 0L);
                String alias = row.optString("deviceAlias", "");
                String id = row.optString("deviceId", "");
                String uploadedAt = formatUploadTime(row.optString("uploadedAt", ""));
                devicesByDate.get(date).add(new UsageChartView.DeviceUsage(id, alias, totalMs, uploadedAt));
                totalsByDate.put(date, totalsByDate.get(date) + totalMs);
            }
        }

        for (Map.Entry<String, List<UsageChartView.DeviceUsage>> entry : devicesByDate.entrySet()) {
            entry.getValue().sort(new Comparator<UsageChartView.DeviceUsage>() {
                @Override
                public int compare(UsageChartView.DeviceUsage a, UsageChartView.DeviceUsage b) {
                    return Long.compare(b.totalMs, a.totalMs);
                }
            });
        }

        List<UsageChartView.DayBucket> buckets = new ArrayList<>();
        for (Map.Entry<String, Long> entry : totalsByDate.entrySet()) {
            String date = entry.getKey();
            buckets.add(new UsageChartView.DayBucket(
                    date,
                    shortLabel(date),
                    entry.getValue(),
                    devicesByDate.get(date)
            ));
        }

        // 本地兑底：如果今天 D1 还没有本机设备的上传，直接拿 UsageStatsManager 查本机今日累计，
        // 贴到今天那个 bucket 上，让用户进入 app 就能看到「今天」柱条高度。
        try {
            String todayKey = formatDate(toCal);
            if (UsageCollector.hasUsageAccess(this)) {
                UsageCollector.DayBuckets local = UsageCollector.queryDayBuckets(
                        this,
                        startOfDay(toCal).getTimeInMillis(),
                        endOfDay(toCal).getTimeInMillis()
                );
                long localTotal = 0L;
                if (local != null) {
                    localTotal = local.totalBundleMs();
                }
                if (localTotal > 0L) {
                    String selfId = settings.deviceId;
                    String selfAlias = settings.deviceAlias.isEmpty() ? selfId : settings.deviceAlias;
                    int todayIdx = -1;
                    for (int i = 0; i < buckets.size(); i++) {
                        if (todayKey.equals(buckets.get(i).date)) { todayIdx = i; break; }
                    }
                    UsageChartView.DayBucket today = todayIdx >= 0 ? buckets.get(todayIdx) : null;
                    boolean hasSelf = false;
                    if (today != null) {
                        for (UsageChartView.DeviceUsage d : today.devices) {
                            if (selfId.equals(d.deviceId)) { hasSelf = true; break; }
                        }
                    }
                    if (!hasSelf) {
                        List<UsageChartView.DeviceUsage> mergedDevices = new ArrayList<>();
                        if (today != null) mergedDevices.addAll(today.devices);
                        mergedDevices.add(new UsageChartView.DeviceUsage(
                                selfId,
                                selfAlias + " · 本机未上传",
                                localTotal,
                                "本地缓存"
                        ));
                        mergedDevices.sort((a, c) -> Long.compare(c.totalMs, a.totalMs));
                        long newTotal = (today == null ? 0L : today.totalMs) + localTotal;
                        UsageChartView.DayBucket merged = new UsageChartView.DayBucket(
                                todayKey, shortLabel(todayKey), newTotal, mergedDevices);
                        if (todayIdx >= 0) buckets.set(todayIdx, merged);
                        else buckets.add(merged);
                    }
                }
            }
        } catch (Exception ignore) {
            // 本地兑底失败不影响 D1 趋势呈现
        }

        // 排序：旧 → 新（左 → 右），使「今天」永远在最右，符合直觉
        buckets.sort(new Comparator<UsageChartView.DayBucket>() {
            @Override
            public int compare(UsageChartView.DayBucket a, UsageChartView.DayBucket b) {
                return a.date.compareTo(b.date);
            }
        });
        return buckets;
    }

    private void applyBuckets(List<UsageChartView.DayBucket> buckets, int days, int token) {
        List<UsageChartView.DayBucket> snapshot = new ArrayList<>(buckets);
        runOnUiThread(() -> {
            if (token != chartLoadToken || days != currentRangeDays) return;
            currentDays = snapshot;
            chart.setDays(currentDays);
            updateRangeTabsUi();
            if (currentDays.isEmpty()) {
                deviceBreakdown.setData("按设备拆分", Collections.emptyList());
                selectedDayTitle.setText("暂无数据");
                selectedDayMeta.setText(labelOfRange(currentRangeDays) + "没有上传记录");
                if (hourChart != null) hourChart.setDeviceHours(null, null);
                if (hourCardSubtitle != null) hourCardSubtitle.setText("暂无时段数据");
                return;
            }
            // 保留用户已选择的日期；只有没有选择时才默认选今天（最右）
            int idx = -1;
            if (selectedDay != null) {
                for (int i = 0; i < currentDays.size(); i++) {
                    if (selectedDay.equals(currentDays.get(i).date)) { idx = i; break; }
                }
            }
            if (idx < 0) idx = currentDays.size() - 1; // 没找到则选今天
            chart.setSelectedIndex(idx);
            selectedDay = currentDays.get(idx).date; // 记住选中
            refreshDeviceCard(currentDays.get(idx));
            loadHoursForSelected(currentDays.get(idx));
        });
    }

    /** 拉某天的 24 小时分布（全设备，按设备分色）；在后台线程调用。 */
    private void loadHoursForSelected(UsageChartView.DayBucket bucket) {
        if (bucket == null || hourChart == null) return;
        SettingsStore settings = SettingsStore.load(this);
        if (!settings.isCloudConfigured()) return;
        final int token;
        synchronized (this) {
            hourLoadToken += 1;
            token = hourLoadToken;
        }
        JSONArray cachedRows = UsageCache.getDayHours(this, settings, bucket.date);
        if (cachedRows != null) {
            renderHourRows(bucket, cachedRows, token);
        }
        new Thread(() -> {
            try {
                JSONArray rows = new D1Client(settings).queryDayHours(bucket.date);
                UsageCache.putDayHours(this, settings, bucket.date, rows);
                renderHourRows(bucket, rows, token);
            } catch (Exception ignore) { }
        }).start();
    }

    private void renderHourRows(UsageChartView.DayBucket bucket, JSONArray rows, int token) {
        if (bucket == null || rows == null) return;
        // device_id -> [source(String), alias(String), hours(long[24])]
        java.util.LinkedHashMap<String, Object[]> deviceMap = new java.util.LinkedHashMap<>();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String deviceId = row.optString("deviceId", "");
                String source   = row.optString("source", "");
                String alias    = row.optString("deviceAlias", "");
                if (alias.isEmpty()) alias = deviceId.length() >= 8 ? deviceId.substring(0, 8) : deviceId;
                int hour = Math.max(0, Math.min(23, row.optInt("hour", 0)));
                long ms  = row.optLong("durationMs", 0L);
                if (!deviceMap.containsKey(deviceId)) {
                    deviceMap.put(deviceId, new Object[]{source, alias, new long[24]});
                }
                ((long[]) deviceMap.get(deviceId)[2])[hour] += ms;
            }
        } catch (Exception ignore) {
            return;
        }

        // 按设备序号分配颜色（每台设备独立一色，不再区分 source）
        java.util.Map<String, Integer> deviceColor = new java.util.LinkedHashMap<>();
        int deviceSeq = 0;
        for (String devId : deviceMap.keySet()) {
            deviceColor.put(devId, deviceSeq % HourChartView.LINE_COLORS.length);
            deviceSeq++;
        }

        List<HourChartView.DeviceSeries> series = new ArrayList<>();
        for (java.util.Map.Entry<String, Object[]> entry : deviceMap.entrySet()) {
            Object[] val = entry.getValue();
            int cIdx = deviceColor.getOrDefault(entry.getKey(), 0);
            series.add(new HourChartView.DeviceSeries(
                    (String) val[1], (String) val[0], (long[]) val[2], cIdx));
        }

        // 把颜色同步给 bucket.devices，让 DeviceBreakdownView 配色一致
        for (UsageChartView.DeviceUsage du : bucket.devices) {
            Integer c = deviceColor.get(du.deviceId);
            if (c != null) du.colorIndex = c;
        }

        long displayTotal = bucket.totalMs > 0 ? bucket.totalMs : 0L;
        runOnUiThread(() -> {
            if (token != hourLoadToken || !bucket.date.equals(selectedDay)) return;
            hourChart.setDeviceHours(series, null);
            // 刷新设备列表（colorIndex 已更新）
            deviceBreakdown.setData(longLabel(bucket.date), bucket.devices);
            if (hourCardSubtitle != null) {
                if (displayTotal <= 0L) hourCardSubtitle.setText("该日暂无时段数据");
                else hourCardSubtitle.setText("合计 " + formatDuration(displayTotal));
            }
        });
    }

    private void refreshDeviceCard(UsageChartView.DayBucket bucket) {
        if (bucket == null) return;
        selectedDayTitle.setText(longLabel(bucket.date));
        if (bucket.devices.isEmpty()) {
            selectedDayMeta.setText("总计 " + formatDuration(bucket.totalMs) + " · 还没有任何设备上传");
        } else {
            selectedDayMeta.setText("总计 " + formatDuration(bucket.totalMs) + " · "
                    + bucket.devices.size() + " 台设备");
        }
        deviceBreakdown.setData(longLabel(bucket.date), bucket.devices);
    }

    private void runAsync(Task task) {
        showStatus("执行中...", false);
        new Thread(() -> {
            String message;
            boolean error = false;
            try {
                message = task.run();
            } catch (Exception e) {
                message = e.getMessage();
                error = true;
            }
            String finalMsg = message;
            boolean finalErr = error;
            runOnUiThread(() -> {
                showStatus(finalMsg == null ? "" : finalMsg, finalErr);
                refreshTodayCard();
            });
        }).start();
    }

    /**
     * 专用于上传操作的异步执行器。
     * 使用 {@link #uploadingNow} 互斥锁，防止与「进入 app 自动上传」等后台操作并发，
     * 避免同时发出多个写请求导致 D1 API 错误。
     */
    private void runAsyncUpload(Task task) {
        if (!uploadingNow.compareAndSet(false, true)) {
            showStatus("上传中，请稍候…", false);
            return;
        }
        showStatus("执行中...", false);
        new Thread(() -> {
            String message;
            boolean error = false;
            try {
                message = task.run();
            } catch (Exception e) {
                message = e.getMessage();
                error = true;
            } finally {
                uploadingNow.set(false);
            }
            String finalMsg = message;
            boolean finalErr = error;
            runOnUiThread(() -> {
                showStatus(finalMsg == null ? "" : finalMsg, finalErr);
                refreshTodayCard();
            });
        }).start();
    }

    private void loadRecentChartQuietly() {
        new Thread(() -> {
            try {
                loadRecentChart();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "切换失败" : e.getMessage();
                runOnUiThread(() -> showStatus(msg, true));
            }
        }).start();
    }

    private void showStatus(String text, boolean error) {
        statusBar.setText(text == null ? "" : text);
        statusBar.setTextColor(error ? COLOR_DANGER : COLOR_TEXT_SECONDARY);
    }

    // ---------- UI 辅助 ----------

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(getResources().getIdentifier("card_bg", "drawable", getPackageName()));
        card.setPadding(dp(8), dp(16), dp(8), dp(16));
        return card;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(COLOR_TEXT_PRIMARY);
        t.setTextSize(15f);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private EditText inputField(String hint, boolean password) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(13f);
        input.setTextColor(COLOR_TEXT_PRIMARY);
        input.setHintTextColor(COLOR_TEXT_MUTED);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackgroundResource(getResources().getIdentifier("input_bg", "drawable", getPackageName()));
        input.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        return input;
    }

    private LinearLayout labeled(String label, EditText input) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = dp(10);
        box.setLayoutParams(boxLp);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(COLOR_TEXT_SECONDARY);
        t.setTextSize(11f);
        t.setPadding(0, 0, 0, dp(4));
        box.addView(t);

        box.addView(input);
        return box;
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14f);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackgroundResource(getResources().getIdentifier("btn_primary", "drawable", getPackageName()));
        b.setMinHeight(dp(44));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(COLOR_TEXT_PRIMARY);
        b.setTextSize(14f);
        b.setBackgroundResource(getResources().getIdentifier("btn_secondary", "drawable", getPackageName()));
        b.setMinHeight(dp(44));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private View spacer(int dpH) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(dpH)));
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------- 格式化 ----------

    private String formatBig(long ms) {
        long s = Math.max(0L, ms / 1000L);
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        if (h > 0L) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }

    private String formatDuration(long ms) {
        long s = Math.max(0L, ms / 1000L);
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        if (h > 0L) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }

    private String formatDate(Calendar calendar) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
    }

    private Calendar startOfDay(Calendar src) {
        Calendar c = (Calendar) src.clone();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private Calendar endOfDay(Calendar src) {
        Calendar c = (Calendar) src.clone();
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        c.set(Calendar.MILLISECOND, 999);
        return c;
    }

    private String shortLabel(String date) {
        if (date == null || date.length() < 10) return date == null ? "" : date;
        return date.substring(5);
    }

    private String longLabel(String date) {
        if (date == null || date.length() < 10) return date == null ? "" : date;
        return date.substring(0, 4) + "-" + date.substring(5);
    }

    private String mask(String s) {
        if (s == null || s.length() <= 6) return s == null ? "" : s;
        return s.substring(0, 4) + "…" + s.substring(s.length() - 4);
    }

    private String formatUploadTime(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            SimpleDateFormat parser;
            if (raw.contains("T")) {
                parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                parser.setTimeZone(TimeZone.getTimeZone("UTC"));
            } else {
                parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                parser.setTimeZone(TimeZone.getTimeZone("UTC"));
            }
            java.util.Date d = parser.parse(raw.length() >= 19 ? raw.substring(0, 19) : raw);
            if (d == null) return raw;
            SimpleDateFormat out = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            out.setTimeZone(TimeZone.getDefault());
            return out.format(d);
        } catch (Exception e) {
            return raw;
        }
    }

    interface Task {
        String run() throws Exception;
    }
}
