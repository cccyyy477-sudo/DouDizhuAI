package com.seagull.doudizhuai;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.os.Handler;
import java.io.File;
import java.io.FileOutputStream;

/**
 * 欢乐斗地主AI助手 - 海鸥出品
 * 操，这他妈是个完整的APP！
 */
public class MainActivity extends Activity {
    
    private Button btnStart;
    private Button btnStop;
    private Button btnSettings;
    private TextView tvStatus;
    private TextView tvCards;
    private TextView tvLog;
    
    private boolean isRunning = false;
    private Handler handler = new Handler();
    private AIEngine aiEngine;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initEngine();
        checkPermissions();
    }
    
    private void initViews() {
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnSettings = findViewById(R.id.btn_settings);
        tvStatus = findViewById(R.id.tv_status);
        tvCards = findViewById(R.id.tv_cards);
        tvLog = findViewById(R.id.tv_log);
        
        btnStart.setOnClickListener(v -> startAI());
        btnStop.setOnClickListener(v -> stopAI());
        btnSettings.setOnClickListener(v -> openSettings());
        
        updateUI();
    }
    
    private void initEngine() {
        aiEngine = new AIEngine(this, new AIEngine.Callback() {
            @Override
            public void onStatusUpdate(String status) {
                runOnUiThread(() -> {
                    tvStatus.setText("状态: " + status);
                    addLog(status);
                });
            }
            
            @Override
            public void onCardsDetected(String cards) {
                runOnUiThread(() -> {
                    tvCards.setText("手牌: " + cards);
                    addLog("识别到手牌: " + cards);
                });
            }
            
            @Override
            public void onPlayDecision(String decision) {
                runOnUiThread(() -> {
                    addLog("AI决策: " + decision);
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    addLog("错误: " + error);
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    private void checkPermissions() {
        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 1001);
        }
        
        // 检查无障碍权限
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请开启无障碍服务", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        }
    }
    
    private boolean isAccessibilityEnabled() {
        // TODO: 检查无障碍服务是否开启
        return false;
    }
    
    private void startAI() {
        if (isRunning) {
            Toast.makeText(this, "AI已经在运行了", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show();
            return;
        }
        
        isRunning = true;
        updateUI();
        aiEngine.start();
        addLog("AI启动成功");
    }
    
    private void stopAI() {
        if (!isRunning) {
            return;
        }
        
        isRunning = false;
        updateUI();
        aiEngine.stop();
        addLog("AI已停止");
    }
    
    private void openSettings() {
        // TODO: 打开设置界面
        Toast.makeText(this, "设置功能开发中", Toast.LENGTH_SHORT).show();
    }
    
    private void updateUI() {
        btnStart.setEnabled(!isRunning);
        btnStop.setEnabled(isRunning);
        
        if (isRunning) {
            tvStatus.setText("状态: 运行中");
            btnStart.setAlpha(0.5f);
            btnStop.setAlpha(1.0f);
        } else {
            tvStatus.setText("状态: 已停止");
            btnStart.setAlpha(1.0f);
            btnStop.setAlpha(0.5f);
        }
    }
    
    private void addLog(String log) {
        String currentLog = tvLog.getText().toString();
        String newLog = "[" + getCurrentTime() + "] " + log + "\n" + currentLog;
        
        // 限制日志长度
        String[] lines = newLog.split("\n");
        if (lines.length > 50) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                sb.append(lines[i]).append("\n");
            }
            newLog = sb.toString();
        }
        
        tvLog.setText(newLog);
    }
    
    private String getCurrentTime() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss");
        return sdf.format(new java.util.Date());
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isRunning) {
            stopAI();
        }
    }
}