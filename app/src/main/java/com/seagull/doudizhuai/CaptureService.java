package com.seagull.doudizhuai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 截屏前台服务：MediaProjection 抓帧 + 悬浮窗建议。
 * 第一版职责：
 *  - 持续抓帧（每 1.5s），供后续识别
 *  - ACTION_SAVE_FRAME：把当前帧存到应用外部目录（供调试识别用）
 *  - ACTION_SHOW_TEXT：在悬浮窗显示建议
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
    private boolean running;
    private long frameSeq = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        thread = new HandlerThread("capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        overlay = new OverlayView(this);

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
            if (!running) {
                running = true;
                initProjection(code, data);
                overlay.show("斗地主AI · 待命");
                handler.postDelayed(frameLoop, 1500);
            }
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
            reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay(
                    "ddz_capture", screenW, screenH, screenDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
        } catch (Exception e) {
            overlay.show("截屏启动失败: " + e.getMessage());
        }
    }

    private final Runnable frameLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            grab(); // 丢弃帧，只维持管道（识别阶段再接入）
            handler.postDelayed(this, 1500);
        }
    };

    private Bitmap grab() {
        if (reader == null) return null;
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return null;
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) return null;
            Image.Plane p = planes[0];
            int width = image.getWidth();
            int height = image.getHeight();
            int pixelStride = p.getPixelStride();
            int rowStride = p.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(p.getBuffer());
            return bitmap;
        } catch (Exception e) {
            return null;
        } finally {
            if (image != null) image.close();
        }
    }

    private void grabAndSave() {
        Bitmap bmp = grab();
        if (bmp == null) {
            Toast.makeText(this, "抓帧失败", Toast.LENGTH_SHORT).show();
            return;
        }
        frameSeq++;
        try {
            File dir = new File(getExternalFilesDir(null), "frames");
            if (!dir.exists()) dir.mkdirs();
            String name = "frame_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_" + frameSeq + ".png";
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            String msg = "已存档: " + f.getAbsolutePath();
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            overlay.show("已抓帧\n" + f.getAbsolutePath());
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        overlay.hide();
        super.onDestroy();
    }
}