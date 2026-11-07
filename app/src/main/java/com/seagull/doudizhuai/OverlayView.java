package com.seagull.doudizhuai;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 悬浮窗 v4：可收缩 + 身份一步选择 + 出牌显示。
 * - 展开态：[地主][上家][下家] 三个按钮（点即选中）+ [−] 收起；
 *           主文本（建议）+ 出牌行（上家/下家出了什么）。
 * - 收缩态：只留一个小方块（当前身份首字 + 展开图标），点一下展开。面积很小。
 * - 拖动：按住任意处拖动移动。
 * 所有 UI 操作强制主线程。
 */
public class OverlayView {

    public interface OnIdentityListener { void onIdentity(String pos); }

    private final Context context;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout header;
    private TextView btnLandlord, btnUp, btnDown, collapseBtn, btnReset;
    private TextView mainText, playedText;
    private TextView expandDot;      // 收缩态小方块
    private WindowManager.LayoutParams params;
    private int lastX, lastY;
    private boolean dragging;
    private float downLocalX, downLocalY;
    private boolean attached;
    private boolean collapsed = false;
    private String identity = "landlord";

    private Runnable onTap;                    // 点主文本 = 手动识别
    private OnIdentityListener onIdentity;     // 点身份按钮 = 直接选中
    private Runnable onReset;                  // 点重置 = 清状态开新一局

    public OverlayView(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void setOnTap(Runnable r) { this.onTap = r; }

    public void setOnIdentityListener(OnIdentityListener l) { this.onIdentity = l; }

    public void setOnResetListener(Runnable r) { this.onReset = r; }

    private TextView mkBtn(String text, String bg, String fg, float size) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(Color.parseColor(fg));
        t.setTextSize(size);
        t.setBackgroundColor(Color.parseColor(bg));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(7), dp(4), dp(7), dp(4));
        return t;
    }

    private void create() {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#DD141426"));
        root.setAlpha(0.96f);

        // 收缩态小方块
        expandDot = mkBtn("地+", "#EE2B2B44", "#FFD166", 13f);
        expandDot.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lpDot = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(expandDot, lpDot);

        // 展开态：身份按钮行
        header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        btnLandlord = mkBtn("地主", "#33FFD166", "#FFD166", 12f);
        btnUp = mkBtn("上家", "#33FFFFFF", "#DDDDDD", 12f);
        btnDown = mkBtn("下家", "#33FFFFFF", "#DDDDDD", 12f);
        btnReset = mkBtn("重置", "#33FF6B6B", "#FF9A9A", 10f);
        collapseBtn = mkBtn("—", "#33FFFFFF", "#AAAAAA", 13f);

        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpB.setMargins(0, 0, dp(4), 0);
        header.addView(btnLandlord, lpB);
        header.addView(btnUp, lpB);
        header.addView(btnDown, lpB);
        header.addView(btnReset, lpB);
        LinearLayout.LayoutParams lpCol = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpCol.setMargins(dp(6), 0, dp(2), 0);
        header.addView(collapseBtn, lpCol);
        root.addView(header);

        mainText = new TextView(context);
        mainText.setText("启动中…");
        mainText.setTextColor(Color.WHITE);
        mainText.setTextSize(13f);
        mainText.setPadding(dp(8), dp(4), dp(8), dp(2));
        mainText.setMaxLines(3);
        root.addView(mainText);

        playedText = new TextView(context);
        playedText.setText("");
        playedText.setTextColor(Color.parseColor("#9FE3C8"));
        playedText.setTextSize(11f);
        playedText.setPadding(dp(8), 0, dp(8), dp(5));
        playedText.setMaxLines(2);
        root.addView(playedText);

        applyVisibility();

        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = (int) event.getRawX();
                        lastY = (int) event.getRawY();
                        downLocalX = event.getX();
                        downLocalY = event.getY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getRawX() - lastX) > 8
                                || Math.abs(event.getRawY() - lastY) > 8) {
                            dragging = true;
                            params.x += (int) event.getRawX() - lastX;
                            params.y += (int) event.getRawY() - lastY;
                            lastX = (int) event.getRawX();
                            lastY = (int) event.getRawY();
                            if (attached) {
                                try { wm.updateViewLayout(root, params); }
                                catch (Exception ignored) {}
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        boolean tapped = !dragging;
                        dragging = false;
                        if (event.getActionMasked() == MotionEvent.ACTION_UP && tapped) {
                            dispatchTap((int) downLocalX, (int) downLocalY);
                        }
                        return true;
                }
                return false;
            }
        });

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(160);
    }

    /** 按按下位置分发点击（root 拦截了事件，子 view 收不到 click） */
    private void dispatchTap(int x, int y) {
        if (collapsed) {
            setCollapsed(false);           // 收缩态：点小方块 = 展开
            return;
        }
        if (inView(btnLandlord, x, y)) { selectIdentity("landlord"); return; }
        if (inView(btnUp, x, y)) { selectIdentity("landlord_up"); return; }
        if (inView(btnDown, x, y)) { selectIdentity("landlord_down"); return; }
        if (inView(btnReset, x, y)) { if (onReset != null) onReset.run(); return; }
        if (inView(collapseBtn, x, y)) { setCollapsed(true); return; }
        if (onTap != null) onTap.run();    // 其余区域 = 手动识别
    }

    private boolean inView(View v, int x, int y) {
        return v != null && x >= v.getLeft() && x <= v.getRight()
                && y >= v.getTop() && y <= v.getBottom();
    }

    private void selectIdentity(String pos) {
        identity = pos;
        highlightIdentity();
        if (onIdentity != null) onIdentity.onIdentity(pos);
    }

    private void highlightIdentity() {
        btnLandlord.setBackgroundColor(Color.parseColor(
                "landlord".equals(identity) ? "#66FFD166" : "#33FFD166"));
        btnUp.setBackgroundColor(Color.parseColor(
                "landlord_up".equals(identity) ? "#66FFFFFF" : "#33FFFFFF"));
        btnDown.setBackgroundColor(Color.parseColor(
                "landlord_down".equals(identity) ? "#66FFFFFF" : "#33FFFFFF"));
        expandDot.setText(identityDot() + "+");
    }

    private String identityDot() {
        if ("landlord".equals(identity)) return "地";
        if ("landlord_up".equals(identity)) return "上";
        return "下";
    }

    private void applyVisibility() {
        int exp = collapsed ? View.GONE : View.VISIBLE;
        int col = collapsed ? View.VISIBLE : View.GONE;
        header.setVisibility(exp);
        mainText.setVisibility(exp);
        playedText.setVisibility(exp);
        expandDot.setVisibility(col);
        if (attached) {
            try { wm.updateViewLayout(root, params); } catch (Exception ignored) {}
        }
    }

    private void setCollapsed(boolean c) {
        collapsed = c;
        applyVisibility();
    }

    /** 显示主文本（自动切主线程；首次调用会把悬浮窗加到窗口） */
    public void show(final String text) {
        main.post(() -> {
            if (root == null) create();
            if (!attached) {
                try {
                    wm.addView(root, params);
                    attached = true;
                } catch (Exception e) {
                    Toast.makeText(context,
                            "悬浮窗添加失败：请授予「显示在其他应用上层」权限\n"
                                    + e.getClass().getSimpleName() + " " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
            mainText.setText(text);
        });
    }

    /** 显示出牌行（带座位标签；null/空则隐藏该行）
     *  leftLabel/rightLabel: 该区域出牌者的身份（地主/上家/下家/空=未知）
     *  mine: 我出的牌（下方中央），非空时显示"我: xxx" */
    public void showPlayed(final String leftLabel, final String left,
                           final String rightLabel, final String right,
                           final String mine) {
        main.post(() -> {
            if (root == null) return;
            StringBuilder sb = new StringBuilder();
            if (mine != null && !mine.isEmpty()) {
                sb.append("我: ").append(mine);
            }
            if (left != null && !left.isEmpty()) {
                if (sb.length() > 0) sb.append("   ");
                sb.append((leftLabel == null || leftLabel.isEmpty()) ? "?" : leftLabel)
                        .append(": ").append(left);
            }
            if (right != null && !right.isEmpty()) {
                if (sb.length() > 0) sb.append("   ");
                sb.append((rightLabel == null || rightLabel.isEmpty()) ? "?" : rightLabel)
                        .append(": ").append(right);
            }
            playedText.setText(sb.toString());
            playedText.setVisibility(sb.length() > 0 && !collapsed ? View.VISIBLE : View.GONE);
        });
    }

    /** 同步身份高亮（pos: landlord / landlord_up / landlord_down） */
    public void showIdentity(final String pos) {
        main.post(() -> {
            if (root == null) return;
            identity = pos;
            highlightIdentity();
        });
    }

    /** 收起/展开（供外部调用） */
    public void setCollapsedExternal(final boolean c) {
        main.post(() -> {
            if (root == null) return;
            setCollapsed(c);
        });
    }

    public void hide() {
        main.post(() -> {
            if (root != null && attached) {
                try { wm.removeView(root); } catch (Exception ignored) {}
                attached = false;
            }
            root = null;
            header = null;
            btnLandlord = btnUp = btnDown = collapseBtn = null;
            mainText = playedText = expandDot = null;
        });
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
