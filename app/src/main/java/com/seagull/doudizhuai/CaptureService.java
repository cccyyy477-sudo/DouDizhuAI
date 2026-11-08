package com.seagull.doudizhuai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 截屏前台服务 v2.1
 * - 持续抓帧并缓存 lastFrame（抓帧存档不再和循环抢同一个 ImageReader）
 * - 初始化/抓帧失败时把真实错误显示在悬浮窗（不再只显示"待命"）
 * - 抓帧保存到相册 Pictures/DouDizhuAI（低版本退回应用目录）
 */
public class CaptureService extends Service {

    public static final String ACTION_START = "com.seagull.doudizhuai.START";
    public static final String ACTION_STOP = "com.seagull.doudizhuai.STOP";
    public static final String ACTION_SAVE_FRAME = "com.seagull.doudizhuai.SAVE_FRAME";
    public static final String ACTION_SHOW_TEXT = "com.seagull.doudizhuai.SHOW_TEXT";
    public static final String EXTRA_CODE = "resultCode";
    public static final String EXTRA_DATA = "resultData";
    public static final String EXTRA_TEXT = "text";

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "ddz_capture";

    private HandlerThread thread;
    private Handler handler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private OverlayView overlay;
    private int screenW, screenH, screenDpi;
    private volatile boolean running;
    private volatile int frameCount;
    private volatile String lastError = "";
    private volatile int failCount = 0;   // 连续识别失败次数（非对局画面时收敛提示）
    private volatile Bitmap lastFrame;
    private int saveSeq = 0;
    private volatile boolean advising = false;   // 识别请求进行中（防并发）

    // —— 自动身份/座位推断（按帧出牌事件推断谁是地主/上家/下家）——
    private final Object stLock = new Object();
    private int obsN = -1;                  // 上一帧识别到的手牌数
    private int lowNCount = 0;              // 手牌连续减少帧数（防识别漏牌误判"我出牌"）
    private String prevL = "", prevR = "", prevM = "";  // 上一帧左右/我的出牌（检测新出牌事件）
    private String firstArea = "";          // 第一个对手出牌区域 L/R（我是地主时=我的下家；我是农民时=地主）
    private String secondArea = "";         // 第二个不同区域（我还没出牌但第二个区域出了→我是地主的上家）
    private String lastEventArea = "";      // 最后一个对手出牌区域
    private String lastEventCards = "";     // 其牌 → last_play（轮到我时=我的上家的牌）
    private String myBeforeArea = "";       // 我出牌前最后一个对手区域（判断我是地主的上家/下家）
    private boolean pendingAfter = false;   // 我刚出牌，等下一个对手事件
    private volatile String autoPos = "";   // 推断出的 position（landlord/landlord_up/landlord_down）
    private volatile String leftLabel = "", rightLabel = "";  // 左右区域座位标签（地主/上家/下家）
    private volatile boolean manualPos = false;  // 手动选过身份（覆盖自动推断，直到重置）

    private static final String[] POS = {"landlord", "landlord_up", "landlord_down"};
    private static final String[] POS_LABEL = {"地主", "上家", "下家"};

    /** 服务实例是否存活（静态，供 MainActivity 判断） */
    private static volatile boolean sRunning;
    public static boolean isRunning() { return sRunning; }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        createChannel();
        thread = new HandlerThread("capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        overlay = new OverlayView(this);
        // 单击悬浮窗 = 抓帧存档；点身份按钮 = 直接选中身份；点重置 = 开新一局清状态
        overlay.setOnTap(() -> handler.post(this::grabAndSave));
        overlay.setOnIdentityListener(this::setIdentity);
        overlay.setOnResetListener(this::resetState);

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        screenDpi = dm.densityDpi;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            int code = intent.getIntExtra(EXTRA_CODE, -1);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            startForegroundCompat();
            // 每次启动都重建投影（MediaProjection 的授权 token 是一次性的，重复授权后旧管道会失效）
            releaseProjection();
            running = true;
            initProjection(code, data);
            handler.removeCallbacks(frameLoop);      // 防重复启动叠加
            handler.removeCallbacks(adviseLoop);
            handler.postDelayed(frameLoop, 800);
            handler.postDelayed(adviseLoop, 1500);   // 自动识别循环：首帧后每 2.5s 刷新
        } else if (ACTION_SAVE_FRAME.equals(action)) {
            handler.post(this::grabAndSave);
        } else if (ACTION_SHOW_TEXT.equals(action)) {
            String text = intent.getStringExtra(EXTRA_TEXT);
            if (text != null) overlay.show(text);
        }
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("斗地主AI")
                .setContentText("屏幕捕获运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
        try {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } catch (Exception e) {
            startForeground(NOTIF_ID, notif);
        }
    }

    private void initProjection(int resultCode, Intent resultData) {
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                fail("投影为空：授权已失效，请重新点『启动悬浮窗』");
                return;
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    running = false;
                    if (overlay != null) overlay.show("截屏已被系统停止（请重新启动）");
                }
            }, handler);

            reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3);
            virtualDisplay = projection.createVirtualDisplay(
                    "ddz_capture", screenW, screenH, screenDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
            if (virtualDisplay == null) {
                fail("创建虚拟屏幕失败");
                return;
            }
            overlay.show("已连接 " + screenW + "x" + screenH + " · 自动识别中\n单击=存图识别 · 点左侧=换身份");
        } catch (Exception e) {
            fail("截屏启动失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void fail(String msg) {
        lastError = msg;
        running = false;
        if (overlay != null) overlay.show("斗地主AI · 错误\n" + msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private final Runnable frameLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            Bitmap b = grab();
            if (b != null) {
                lastFrame = b;   // 缓存给「抓帧存档」，避免和保存逻辑抢同一 ImageReader
                frameCount++;
                if (frameCount == 1) {
                    overlay.show("斗地主AI · 已连接 " + b.getWidth() + "x" + b.getHeight());
                }
            }
            handler.postDelayed(this, 700);
        }
    };

    /** 取最新一帧；失败返回 null 并记录原因 */
    private Bitmap grab() {
        if (reader == null) {
            lastError = "ImageReader 未创建（截屏未启动）";
            return null;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                lastError = "暂无画面帧（等待系统推帧）";
                return null;
            }
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) {
                lastError = "帧无数据平面";
                return null;
            }
            Image.Plane p = planes[0];
            int width = image.getWidth();
            int height = image.getHeight();
            int pixelStride = p.getPixelStride();
            int rowStride = p.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            int bmpW = width + (pixelStride > 0 ? rowPadding / pixelStride : 0);

            Bitmap bitmap = Bitmap.createBitmap(bmpW, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(p.getBuffer());
            lastError = "";
            return bitmap;
        } catch (Throwable e) {
            lastError = "抓帧异常: " + e.getClass().getSimpleName() + " " + e.getMessage();
            return null;
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void grabAndSave() {
        // 临时隐藏悬浮窗，避免它出现在截图里挡住牌面
        overlay.hide();
        handler.postDelayed(() -> {
            Bitmap bmp = grab(); // 悬浮窗已隐藏，现抓一帧是干净画面
            if (bmp == null) {
                String msg = "抓帧失败：" + (lastError.isEmpty() ? "无画面" : lastError);
                overlay.show("斗地主AI · 抓帧失败\n" + msg);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                return;
            }
            // 1) 存图（调试 + 补识别样本用）
            saveSeq++;
            String name = "ddz_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                    + "_" + saveSeq + ".png";
            String w = saveToGallery(bmp, name);
            if (w == null) {
                w = saveToAppDir(bmp, name);
            }
            final String where = (w == null) ? ("存档失败:" + lastError) : w;

            // 2) 上传识别 + DouZero 决策，悬浮窗显示建议
            overlay.show("识别中…");
            adviseAsync(bmp, where);
        }, 300);
    }

    /** 点左侧身份标签：地主→上家→下家→地主，写入偏好并立即重识别 */
    private void cycleIdentity() {
        SharedPreferences sp = getSharedPreferences("ddz", Context.MODE_PRIVATE);
        String cur = sp.getString("position", "landlord_up");
        int i = 0;
        for (int k = 0; k < POS.length; k++) {
            if (POS[k].equals(cur)) i = (k + 1) % POS.length;
        }
        sp.edit().putString("position", POS[i]).apply();
        overlay.showIdentity(POS_LABEL[i]);
        Toast.makeText(this, "身份: " + POS_LABEL[i], Toast.LENGTH_SHORT).show();
        handler.post(() -> adviseAuto(true));   // 换身份后立即按新身份识别
    }

    /** 点身份按钮：直接选中该身份（地主/上家/下家），写入偏好并立即重识别 */
    private void setIdentity(String pos) {
        SharedPreferences sp = getSharedPreferences("ddz", Context.MODE_PRIVATE);
        sp.edit().putString("position", pos).apply();
        synchronized (stLock) { manualPos = true; }   // 手动选择覆盖自动推断
        Toast.makeText(this, "身份: " + identityLabel(pos), Toast.LENGTH_SHORT).show();
        handler.post(() -> adviseAuto(true));         // 换身份后立即按新身份识别
    }

    /** 重置：清空身份/座位/出牌事件状态（开新一局），回到自动推断，立即重识别 */
    private void resetState() {
        synchronized (stLock) {
            obsN = -1; prevL = ""; prevR = ""; prevM = "";
            lowNCount = 0;
            firstArea = ""; secondArea = ""; lastEventArea = ""; lastEventCards = "";
            myBeforeArea = ""; pendingAfter = false;
            autoPos = ""; leftLabel = ""; rightLabel = "";
            manualPos = false;
        }
        SharedPreferences sp = getSharedPreferences("ddz", Context.MODE_PRIVATE);
        sp.edit().remove("position").apply();   // 删身份偏好 → 回自动推断
        Toast.makeText(this, "已重置，重新推断身份", Toast.LENGTH_SHORT).show();
        handler.post(() -> adviseAuto(true));   // 立即重识别
    }

    private String identityLabel(String pos) {
        for (int i = 0; i < POS.length; i++) {
            if (POS[i].equals(pos)) return POS_LABEL[i];
        }
        return POS_LABEL[1];
    }

    /** 每帧识别结果 → 更新出牌事件序列并推断身份/座位（须持 stLock 调用） */
    private void onFrameResultLocked(int n, String left, String right, String mine) {
        // 新一局：手牌从少大幅回升到 17/20 → 清状态重新推断
        if (obsN > 0 && n >= 17 && n > obsN + 5) {
            firstArea = ""; secondArea = ""; lastEventArea = ""; lastEventCards = "";
            myBeforeArea = ""; pendingAfter = false;
            autoPos = ""; leftLabel = ""; rightLabel = "";
            prevL = ""; prevR = ""; prevM = "";
            lowNCount = 0;
        }
        // 开局身份：地主 20 张；农民 17 张 → 等出牌顺序再判断上家/下家
        if (autoPos.isEmpty() && n >= 20) {
            autoPos = "landlord";
        }
        // 出牌事件：左右区域出现新牌 = 对手出牌；我出牌区出现新牌 或 手牌减少 = 我出牌
        // 事件判定用"点数集合"而非原始字符串：识别顺序抖动(4,3,3 vs 3,4,3)不应重复触发事件，
        // 否则同一手牌会反复刷 lastEventArea/lastEventCards，身份推断和 last_play 全被污染。
        // 注意：normCards() 返回带方括号的 "[3, 7]"（只用于比较），传给 lastEventCards 必须用原始串
        //       （空格分隔 "3 7"），否则服务端 parseCards 会得到 "[3" 这类非法牌名 → 400。
        String nL = normCards(left), nR = normCards(right), nM = normCards(mine);
        boolean newL = !nL.isEmpty() && !nL.equals(normCards(prevL));
        boolean newR = !nR.isEmpty() && !nR.equals(normCards(prevR));
        boolean newM = !nM.isEmpty() && !nM.equals(normCards(prevM));
        // 手牌减少=我出牌，但识别漏一张是常态（17→16 抖动）→ 需连续 2 帧确认才触发，
        // 否则一次漏检就把 myBeforeArea/身份推断带偏。
        boolean nDrop = obsN > 0 && n > 0 && n < obsN;
        lowNCount = nDrop ? lowNCount + 1 : 0;
        boolean myPlay = newM || lowNCount >= 2;
        if (newL) handleEventLocked("L", left);      // 原始串（"3 7"），不是 normCards 的 "[3, 7]"
        if (newR) handleEventLocked("R", right);
        if (myPlay && !lastEventArea.isEmpty()) {
            myBeforeArea = lastEventArea;   // 我出牌前最后出牌的 = 我的上家
            pendingAfter = true;
        }
        prevL = nL; prevR = nR; prevM = nM; obsN = n;
        inferLocked(n);
    }

    /** 点数集合归一化："4,3,3" / "3,4,3" → "3,3,4"（识别顺序抖动去重用） */
    private static String normCards(String s) {
        if (s == null || s.isEmpty()) return "";
        String[] a = s.split(",");
        java.util.Arrays.sort(a);
        return java.util.Arrays.toString(a);
    }

    private void handleEventLocked(String area, String cards) {
        if (firstArea.isEmpty()) firstArea = area;
        else if (secondArea.isEmpty() && !area.equals(firstArea)) secondArea = area;
        lastEventArea = area;
        lastEventCards = cards;
        if (pendingAfter) pendingAfter = false;
    }

    /** 推断 position 与左右区域标签（须持 stLock）
     *  规则（斗地主顺序：地主 → 地主的下家 → 地主的上家 → 地主）：
     *  - 我是地主(20张)：第一个出牌的对手 = 我的下家，第二个 = 我的上家
     *  - 我是农民(17张)：第一个出牌的 = 地主；我出牌前最后出牌的==地主 → 我是地主的下家，
     *                    否则（另一个农民先出）→ 我是地主的上家 */
    private void inferLocked(int n) {
        // 农民开局 17 张：识别漏一张(16)或误检一张(18)都常见 → 放宽到 16~17，
        // 原 n==17 精确匹配一旦漏检就永远不推断，autoPos 一直空 → 全程用默认身份（身份"分不清"主因之一）。
        if (autoPos.isEmpty() && n >= 16 && n <= 17) {
            if (!myBeforeArea.isEmpty()) {
                autoPos = myBeforeArea.equals(firstArea) ? "landlord_down" : "landlord_up";
            } else if (!secondArea.isEmpty()) {
                autoPos = "landlord_up";    // 地主+另一农民都出了、我还没出 → 我是地主的上家
            } else if (!firstArea.isEmpty()) {
                autoPos = "landlord_down";  // 只有地主出过 → 我是下家（即将轮到我）
            }
        }
        if (firstArea.isEmpty() || autoPos.isEmpty()) return;
        if ("landlord".equals(autoPos)) {
            // 我是地主：第一个出牌的对手 = 我的下家，第二个 = 我的上家
            setLabelLocked(firstArea, "下家");
            if (!secondArea.isEmpty()) setLabelLocked(secondArea, "上家");
        } else {
            // 我是农民：firstArea = 地主；other = 另一个农民（我的对手）
            // 斗地主顺序：地主 → 地主下家 → 地主上家 → 地主
            //   我是 landlord_down(地主下家) → 我出完轮到对手 → 对手是地主的【上家】
            //   我是 landlord_up(地主上家)   → 对手是地主的【下家】
            // （原代码两个标签写反，导致"上家出牌显示成下家"）
            setLabelLocked(firstArea, "地主");
            String other = "L".equals(firstArea) ? "R" : "L";
            if ("landlord_down".equals(autoPos)) setLabelLocked(other, "上家");
            else if ("landlord_up".equals(autoPos)) setLabelLocked(other, "下家");
        }
    }

    private void setLabelLocked(String area, String label) {
        if ("L".equals(area)) leftLabel = label; else rightLabel = label;
    }

    /** 是否轮到我出牌（须持 stLock）。pos=当前生效身份(手动优先)。
     *  依据：firstArea=第一个出牌的对手、secondArea=第二个、lastEventArea=最后出牌的区域、
     *        pendingAfter=我刚出完牌(等对手)。
     *  出牌顺序：地主 → 地主下家 → 地主上家 → 地主 … */
    private boolean isMyTurnLocked(String pos) {
        if (pendingAfter) return false;                 // 我刚出完，等对手
        if (lastEventArea.isEmpty()) {
            return "landlord".equals(pos);              // 没人出过：只有地主有首出权
        }
        if ("landlord".equals(pos)) {
            // 我是地主：我出→下家(firstArea)→上家(secondArea)→轮到我
            return !secondArea.isEmpty() && lastEventArea.equals(secondArea);
        } else if ("landlord_down".equals(pos)) {
            // 我是地主下家：地主(firstArea)出完 → 轮到我
            return !firstArea.isEmpty() && lastEventArea.equals(firstArea);
        } else if ("landlord_up".equals(pos)) {
            // 我是地主上家：地主→下家(other)出完 → 轮到我
            if (firstArea.isEmpty()) return false;
            String other = "L".equals(firstArea) ? "R" : "L";
            return lastEventArea.equals(other);
        }
        return false;                                   // 身份未知 → 不给建议
    }

    /** 自动识别：用已缓存的最新帧（不存图，省相册），悬浮窗刷新建议 */
    private void adviseAuto(boolean force) {
        if (advising && !force) return;
        Bitmap b = lastFrame;
        if (b == null) {
            overlay.show("等待截屏画面…");
            return;
        }
        if (advising) return;
        advising = true;
        overlay.show("识别中…");
        adviseAsync(b, null);
    }

    /** 自动识别循环：每 2.5 秒一次（服务启动后自动跑，无需点击） */
    private final Runnable adviseLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            adviseAuto(false);
            handler.postDelayed(this, 2500);
        }
    };

    /** 抓帧图 -> POST /advise（识别手牌+决策）-> 悬浮窗显示建议 */
    private void adviseAsync(Bitmap bmp, String savedAt) {
        advising = true;
        SharedPreferences sp = getSharedPreferences("ddz", Context.MODE_PRIVATE);
        final String server = sp.getString("server", "http://10.50.39.114:5000/predict");
        final String pos;
        final String last;
        final boolean myTurn;   // 请求前捕获（请求期间状态可能变，以发起时为准）
        synchronized (stLock) {
            String manual = sp.getString("position", "");
            pos = (manualPos && !manual.isEmpty()) ? manual
                    : (autoPos.isEmpty() ? "landlord_up" : autoPos);
            last = lastEventCards;   // 最后一个对手出的牌 = 轮到我时我的上家的牌
            myTurn = isMyTurnLocked(pos);
        }

        // 压缩成 JPEG 再 base64（PNG 原图 2-5MB，JPEG q72 约 200-400KB）
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 72, bos);
        final String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);

        new Thread(() -> {
            DecisionClient.AdviseResult r = DecisionClient.advise(server, b64, pos, last);
            final String text;
            if (r.error != null) {
                // 服务端已给用户能懂的文案（未在对局画面/画面无内容等），不加"识别失败"前缀吓人；
                // 连续失败（结算页/微信/黑屏时每2.5s一次）第4次起收敛为一行，避免刷屏。
                failCount++;
                if (failCount >= 4) {
                    text = "等待对局画面…\n（进入牌局后自动识别）";
                } else {
                    text = r.error;
                }
            } else if (r.warn != null) {
                failCount = 0;
                text = r.warn;                       // 点数样本不足等警告
            } else if (!myTurn) {
                failCount = 0;
                // 未轮到我（开局/对手出牌中）→ 不给建议，只报状态
                text = "等待对手出牌…\n手牌" + r.n + "张 · " + r.latencyMs + "ms";
            } else if (r.action == null || r.action.isEmpty()) {
                failCount = 0;
                text = "不出\n手牌" + r.n + "张 · " + r.latencyMs + "ms";
            } else {
                failCount = 0;
                text = "建议出 " + r.action + "\n"
                        + r.n + "张 · " + r.latencyMs + "ms";
            }
            handler.post(() -> {
                boolean okFrame = (r.error == null && r.warn == null && r.n > 0);
                String ll = "", rl = "", showPos = "";
                boolean showManual = false;
                if (okFrame) {
                    synchronized (stLock) {
                        onFrameResultLocked(r.n,
                                r.playedLeft == null ? "" : r.playedLeft,
                                r.playedRight == null ? "" : r.playedRight,
                                r.playedMine == null ? "" : r.playedMine);
                        ll = leftLabel; rl = rightLabel;
                        showPos = autoPos; showManual = manualPos;
                    }
                }
                overlay.show(text);
                overlay.showPlayed(ll, r.playedLeft, rl, r.playedRight, r.playedMine);
                if (okFrame && !showManual && !showPos.isEmpty()) {
                    overlay.showIdentity(showPos);   // 自动推断的身份同步高亮
                }
                advising = false;
            });
        }).start();
    }

    /** Android Q+ 写入相册 Pictures/DouDizhuAI；返回可读路径说明 */
    private String saveToGallery(Bitmap bmp, String name) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            ContentResolver cr = getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DouDizhuAI");
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) return null;
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            return "相册 Pictures/DouDizhuAI/" + name;
        } catch (Throwable e) {
            lastError = "写相册失败: " + e.getMessage();
            return null;
        }
    }

    private String saveToAppDir(Bitmap bmp, String name) {
        try {
            File dir = new File(getExternalFilesDir(null), "frames");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            return f.getAbsolutePath();
        } catch (Throwable e) {
            lastError = "写文件失败: " + e.getMessage();
            return null;
        }
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "斗地主AI截屏", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void releaseProjection() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception ignored) {
            }
            virtualDisplay = null;
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
            reader = null;
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        sRunning = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        releaseProjection();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        overlay.hide();
        super.onDestroy();
    }
}