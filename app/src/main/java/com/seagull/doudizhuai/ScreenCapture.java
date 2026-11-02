package com.seagull.doudizhuai;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.nio.ByteBuffer;

/**
 * 屏幕截图模块 - 海鸥手写
 * 操，这玩意儿用来抓屏幕！
 */
public class ScreenCapture {
    
    private Context context;
    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    
    public ScreenCapture(Context context) {
        this.context = context;
        initScreen();
    }
    
    private void initScreen() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
    }
    
    public void init(MediaProjection projection) {
        this.mediaProjection = projection;
        
        imageReader = ImageReader.newInstance(
            screenWidth, 
            screenHeight,
            android.graphics.PixelFormat.RGBA_8888,
            2
        );
        
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            null
        );
    }
    
    public Bitmap capture() {
        if (imageReader == null) {
            return captureByShell();
        }
        
        try {
            Image image = imageReader.acquireLatestImage();
            if (image == null) {
                return null;
            }
            
            int width = image.getWidth();
            int height = image.getHeight();
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            
            Bitmap bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);
            image.close();
            
            return bitmap;
            
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    private Bitmap captureByShell() {
        // 使用shell命令截图作为备用方案
        try {
            String screenshotPath = "/sdcard/screenshot_temp.png";
            Runtime.getRuntime().exec("screencap -p " + screenshotPath).waitFor();
            
            Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(screenshotPath);
            new java.io.File(screenshotPath).delete();
            
            return bitmap;
            
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    public void release() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }
}