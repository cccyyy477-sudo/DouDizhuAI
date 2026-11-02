package com.seagull.doudizhuai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * DouZero AI决策模块 - 海鸥手写
 * 操，这是对接DouZero模型的核心！
 */
public class DouZeroAI {
    
    private String apiUrl = "http://localhost:5000/predict"; // DouZero API地址
    private Map<String, Integer> cardValue = new HashMap<>();
    
    public DouZeroAI() {
        initCardValue();
    }
    
    public DouZeroAI(String apiUrl) {
        this.apiUrl = apiUrl;
        initCardValue();
    }
    
    private void initCardValue() {
        cardValue.put("3", 3);
        cardValue.put("4", 4);
        cardValue.put("5", 5);
        cardValue.put("6", 6);
        cardValue.put("7", 7);
        cardValue.put("8", 8);
        cardValue.put("9", 9);
        cardValue.put("10", 10);
        cardValue.put("J", 11);
        cardValue.put("Q", 12);
        cardValue.put("K", 13);
        cardValue.put("A", 14);
        cardValue.put("2", 15);
        cardValue.put("小王", 16);
        cardValue.put("大王", 17);
    }
    
    /**
     * 调用DouZero模型决策
     */
    public List<String> decide(List<String> myCards, List<String> lastPlay, String position) {
        try {
            // 构造请求数据
            JSONObject request = new JSONObject();
            
            // 转换手牌格式
            JSONArray cardsArray = new JSONArray();
            for (String card : myCards) {
                cardsArray.put(convertCard(card));
            }
            request.put("hand_cards", cardsArray);
            
            // 上家出牌
            JSONArray lastPlayArray = new JSONArray();
            if (lastPlay != null && !lastPlay.isEmpty()) {
                for (String card : lastPlay) {
                    lastPlayArray.put(convertCard(card));
                }
            }
            request.put("last_play", lastPlayArray);
            
            // 位置（地主/农民）
            request.put("position", position); // "landlord" or "peasant"
            
            // 发送HTTP请求
            String response = sendPostRequest(apiUrl, request.toString());
            
            // 解析响应
            JSONObject result = new JSONObject(response);
            JSONArray action = result.getJSONArray("action");
            
            // 转换回我们的格式
            List<String> playCards = new ArrayList<>();
            for (int i = 0; i < action.length(); i++) {
                String card = convertCardBack(action.getString(i));
                playCards.add(card);
            }
            
            return playCards;
            
        } catch (Exception e) {
            e.printStackTrace();
            // 如果调用失败，回退到简单策略
            return fallbackDecision(myCards, lastPlay);
        }
    }
    
    /**
     * 发送POST请求
     */
    private String sendPostRequest(String urlString, String jsonData) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        
        // 发送数据
        OutputStream os = conn.getOutputStream();
        os.write(jsonData.getBytes("UTF-8"));
        os.flush();
        os.close();
        
        // 读取响应
        BufferedReader br = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), "UTF-8")
        );
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            response.append(line);
        }
        br.close();
        
        return response.toString();
    }
    
    /**
     * 转换牌面格式（我们的格式 -> DouZero格式）
     */
    private String convertCard(String card) {
        // DouZero使用的格式：3-10, J, Q, K, A, 2, RJ(小王), BJ(大王)
        switch (card) {
            case "小王": return "RJ";
            case "大王": return "BJ";
            default: return card;
        }
    }
    
    /**
     * 转换牌面格式（DouZero格式 -> 我们的格式）
     */
    private String convertCardBack(String card) {
        switch (card) {
            case "RJ": return "小王";
            case "BJ": return "大王";
            default: return card;
        }
    }
    
    /**
     * 降级策略：如果DouZero调用失败，使用简单策略
     */
    private List<String> fallbackDecision(List<String> myCards, List<String> lastPlay) {
        if (lastPlay == null || lastPlay.isEmpty()) {
            // 主动出牌：出最小的单牌
            List<String> play = new ArrayList<>();
            play.add(myCards.get(0));
            return play;
        } else {
            // 被动应对：找能压住上家的最小牌
            String lastCard = lastPlay.get(0);
            int lastValue = cardValue.get(lastCard);
            
            for (String card : myCards) {
                if (cardValue.get(card) > lastValue) {
                    List<String> play = new ArrayList<>();
                    play.add(card);
                    return play;
                }
            }
            
            // 打不起，过牌
            return new ArrayList<>();
        }
    }
}