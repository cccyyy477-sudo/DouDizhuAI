package com.seagull.doudizhuai;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.ArrayList;

/**
 * AI引擎核心 - 海鸥手写
 * 操，这是AI的大脑！
 */
public class AIEngine {
    
    private Context context;
    private Callback callback;
    private Handler handler;
    private boolean isRunning = false;
    
    private ScreenCapture screenCapture;
    private CardRecognizer cardRecognizer;
    private GameController gameController;
    private AIDecision aiDecision;
    
    private Runnable aiTask = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            
            try {
                // 1. 截图
                callback.onStatusUpdate("正在截图...");
                Bitmap screenshot = screenCapture.capture();
                
                if (screenshot == null) {
                    callback.onError("截图失败");
                    handler.postDelayed(this, 3000);
                    return;
                }
                
                // 2. 识别手牌
                callback.onStatusUpdate("正在识别手牌...");
                List<String> cards = cardRecognizer.recognize(screenshot);
                
                if (cards == null || cards.isEmpty()) {
                    callback.onStatusUpdate("等待游戏中...");
                    handler.postDelayed(this, 3000);
                    return;
                }
                
                callback.onCardsDetected(cards.toString());
                
                // 3. AI决策
                callback.onStatusUpdate("AI决策中...");
                List<String> playCards = aiDecision.decide(cards, new ArrayList<>());
                
                // 4. 执行出牌
                if (playCards != null && !playCards.isEmpty()) {
                    callback.onPlayDecision("出牌: " + playCards.toString());
                    callback.onStatusUpdate("正在出牌...");
                    gameController.playCards(playCards);
                } else {
                    callback.onPlayDecision("过牌");
                    callback.onStatusUpdate("正在过牌...");
                    gameController.pass();
                }
                
                callback.onStatusUpdate("等待下一轮...");
                handler.postDelayed(this, 3000);
                
            } catch (Exception e) {
                callback.onError("运行出错: " + e.getMessage());
                handler.postDelayed(this, 3000);
            }
        }
    };
    
    public interface Callback {
        void onStatusUpdate(String status);
        void onCardsDetected(String cards);
        void onPlayDecision(String decision);
        void onError(String error);
    }
    
    public AIEngine(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
        this.handler = new Handler(Looper.getMainLooper());
        
        // 初始化各个模块
        this.screenCapture = new ScreenCapture(context);
        this.cardRecognizer = new CardRecognizer(context);
        this.gameController = new GameController(context);
        this.aiDecision = new AIDecision();
    }
    
    public void start() {
        if (isRunning) return;
        
        isRunning = true;
        callback.onStatusUpdate("AI启动");
        handler.post(aiTask);
    }
    
    public void stop() {
        if (!isRunning) return;
        
        isRunning = false;
        handler.removeCallbacks(aiTask);
        callback.onStatusUpdate("AI停止");
    }
    
    public boolean isRunning() {
        return isRunning;
    }
}