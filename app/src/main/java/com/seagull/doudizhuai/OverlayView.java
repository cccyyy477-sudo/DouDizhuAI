package com.seagull.doudizhuai;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 悬浮窗：显示 AI 建议。可拖动。TYPE_APPLICATION_OVERLAY。
 */
public class OverlayView {

    private final Context context;
    private final WindowManager wm;
    private TextView view;
    private WindowManager.LayoutParams params;
    private int lastX, lastY;
    private boolean dragging;

    public OverlayView(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    private void create() {
        view = new TextView(context);
        view.setText("斗地主AI");
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setPadding(24, 18, 24, 18);
        view.setBackgroundColor(Color.parseColor("#CC1A1A2E"));
        view.setGravity(Gravity.CENTER);
        view.setAlpha(0.95f);
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = (int) event.getRawX();
                        lastY = (int) event.getRawY();
                        dragging = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getRawX() - lastX) > 8 || Math.abs(event.getRawY() - lastY) > 8) {
                            dragging = true;
                            params.x += (int) event.getRawX() - lastX;
                            params.y += (int) event.getRawY() - lastY;
                            lastX = (int) event.getRawX();
                            lastY = (int) event.getRawY();
                            wm.updateViewLayout(view, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        return dragging;
                }
                return false;
            }
        });
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 30;
        params.y = 200;
    }

    public synchronized void show(String text) {
        if (view == null) create();
        if (view.getParent() == null) {
            try {
                wm.addView(view, params);
            } catch (Exception e) {
                // 无悬浮窗权限等
                return;
            }
        }
        view.setText(text);
    }

    public synchronized void hide() {
        if (view != null && view.getParent() != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
        }
        view = null;
    }
}