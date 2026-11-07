package com.seagull.doudizhuai;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 斗地主AI 主界面（真实可用版 v2）
 * - 连接本机/局域网 DouZero 决策服务
 * - 手动输入手牌问 AI（服务端真模型）
 * - MediaProjection 截屏 + 悬浮窗（为后续自动识别做准备）
 * - 抓帧存档：供调试手牌识别
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_PROJECTION = 1002;
    private static final int REQ_NOTIFICATION = 1003;

    private static final String[] POSITIONS = {"landlord", "landlord_up", "landlord_down"};

    private Button btnStart, btnStop, btnAsk, btnCapture, btnPos;
    private EditText etServer, etHand, etLast;
    private TextView tvStatus, tvAdvice, tvLog;

    private String position = "landlord_up";
    private boolean serviceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnAsk = findViewById(R.id.btn_ask);
        btnCapture = findViewById(R.id.btn_capture);
        btnPos = findViewById(R.id.btn_pos);
        etServer = findViewById(R.id.et_server);
        etHand = findViewById(R.id.et_hand);
        etLast = findViewById(R.id.et_last);
        tvStatus = findViewById(R.id.tv_status);
        tvAdvice = findViewById(R.id.tv_advice);
        tvLog = findViewById(R.id.tv_log);

        if (savedInstanceState != null) {
            position = savedInstanceState.getString("position", "landlord_up");
            serviceRunning = savedInstanceState.getBoolean("running", false);
            etServer.setText(savedInstanceState.getString("server", etServer.getText().toString()));
            etHand.setText(savedInstanceState.getString("hand", ""));
            etLast.setText(savedInstanceState.getString("last", ""));
        }

        btnStart.setOnClickListener(v -> onStartClick());
        btnStop.setOnClickListener(v -> onStopClick());
        btnAsk.setOnClickListener(v -> askAi());
        btnCapture.setOnClickListener(v -> captureFrame());
        btnPos.setOnClickListener(v -> {
            int i = 0;
            for (int k = 0; k < POSITIONS.length; k++) {
                if (POSITIONS[k].equals(position)) i = (k + 1) % POSITIONS.length;
            }
            position = POSITIONS[i];
            updatePosButton();
            addLog("身份切换: " + position);
            persistPrefs();
        });

        // 服务地址 / 上家出牌 一改动就落盘（CaptureService 抓帧时读取）
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { persistPrefs(); }
        };
        etServer.addTextChangedListener(tw);
        etLast.addTextChangedListener(tw);

        updateUI();
        checkPermissions();
        persistPrefs();
        addLog("斗地主AI 已启动。服务地址: " + defaultServer());
    }

    private String defaultServer() {
        return etServer.getText().toString().trim();
    }

    /** 服务地址/身份/上家出牌 落盘，供 CaptureService 抓帧时读取 */
    private void persistPrefs() {
        getSharedPreferences("ddz", MODE_PRIVATE).edit()
                .putString("server", defaultServer())
                .putString("position", position)
                .putString("last_play", etLast.getText().toString().trim())
                .apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistPrefs();
    }

    private void checkPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            addLog("需要悬浮窗权限，正在跳转设置…");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    private void onStartClick() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
        // 请求屏幕捕获授权
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, CaptureService.class);
                svc.setAction(CaptureService.ACTION_START);
                svc.putExtra(CaptureService.EXTRA_CODE, resultCode);
                svc.putExtra(CaptureService.EXTRA_DATA, data);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                serviceRunning = true;
                addLog("屏幕捕获已授权，悬浮窗服务启动");
                Toast.makeText(this, "截屏服务已启动", Toast.LENGTH_SHORT).show();
            } else {
                addLog("用户取消了屏幕捕获授权");
            }
        }
        updateUI();
    }

    private void onStopClick() {
        Intent svc = new Intent(this, CaptureService.class);
        svc.setAction(CaptureService.ACTION_STOP);
        startService(svc);
        serviceRunning = false;
        addLog("服务停止");
        updateUI();
    }

    private void captureFrame() {
        if (!CaptureService.isRunning() && !serviceRunning) {
            Toast.makeText(this, "先启动悬浮窗服务（点启动并完成截屏授权）", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class);
        svc.setAction(CaptureService.ACTION_SAVE_FRAME);
        startService(svc);
        addLog("抓帧命令已发送");
    }

    private void askAi() {
        String hand = etHand.getText().toString().trim();
        if (hand.isEmpty()) {
            Toast.makeText(this, "请先输入手牌", Toast.LENGTH_SHORT).show();
            return;
        }
        String server = defaultServer();
        if (server.isEmpty()) {
            Toast.makeText(this, "请填写服务地址", Toast.LENGTH_SHORT).show();
            return;
        }
        tvAdvice.setText("建议: 请求中…");
        addLog("问 AI: 手牌=" + hand + " 身份=" + position + " 服务=" + server);
        new Thread(() -> {
            DecisionClient.Result r = DecisionClient.ask(server, hand, etLast.getText().toString().trim(), position);
            runOnUiThread(() -> {
                if (r.error != null) {
                    tvAdvice.setText("建议: 出错 " + r.error);
                    addLog("问 AI 失败: " + r.error);
                } else {
                    tvAdvice.setText("建议: 出 " + r.action + "（" + r.latencyMs + "ms）");
                    addLog("AI 建议: " + r.action);
                    Intent svc = new Intent(MainActivity.this, CaptureService.class);
                    svc.setAction(CaptureService.ACTION_SHOW_TEXT);
                    svc.putExtra(CaptureService.EXTRA_TEXT, "AI 建议: 出 " + r.action);
                    startService(svc);
                }
            });
        }).start();
    }

    private void updateUI() {
        if (serviceRunning) {
            tvStatus.setText("状态: 运行中（截屏+悬浮窗）");
            btnStart.setAlpha(0.5f);
            btnStop.setEnabled(true);
        } else {
            tvStatus.setText("状态: 未启动");
            btnStart.setAlpha(1.0f);
            btnStop.setEnabled(false);
        }
        updatePosButton();
    }

    private void updatePosButton() {
        String label;
        switch (position) {
            case "landlord": label = "地主"; break;
            case "landlord_down": label = "地主下家"; break;
            default: label = "非地主（上家视角）"; break;
        }
        btnPos.setText("身份: " + label + " → 点击切换");
    }

    private void addLog(String log) {
        String current = tvLog.getText().toString();
        String newLog = "[" + getCurrentTime() + "] " + log + "\n" + current;
        String[] lines = newLog.split("\n");
        if (lines.length > 60) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 60; i++) sb.append(lines[i]).append("\n");
            newLog = sb.toString();
        }
        tvLog.setText(newLog);
    }

    private String getCurrentTime() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("position", position);
        outState.putBoolean("running", serviceRunning);
        outState.putString("server", etServer.getText().toString());
        outState.putString("hand", etHand.getText().toString());
        outState.putString("last", etLast.getText().toString());
    }
}