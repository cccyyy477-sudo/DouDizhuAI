package com.seagull.doudizhuai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 决策服务客户端：POST /predict 拿 DouZero 建议。
 * 服务端见 D:\J\hermesOne\DouDizhuAI-server（常驻 5000 端口）。
 */
public class DecisionClient {

    public static class Result {
        public String action;       // 人类可读，如 "小王 大王"
        public String[] cards;      // 原始牌面
        public String error;
        public long latencyMs;
    }

    public static String[] parseCards(String csv) {
        if (csv == null || csv.trim().isEmpty()) return new String[0];
        String s = csv.replace('，', ' ').replace(',', ' ')
                .replace('、', ' ').replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? new String[0] : s.split(" ");
    }

    public static String pretty(String[] cards) {
        if (cards == null || cards.length == 0) return "过";
        StringBuilder sb = new StringBuilder();
        for (String c : cards) {
            if (sb.length() > 0) sb.append(' ');
            switch (c) {
                case "X": case "RJ": case "小王": sb.append("小王"); break;
                case "D": case "BJ": case "大王": sb.append("大王"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    public static Result ask(String url, String handCsv, String lastPlayCsv, String position) {
        Result res = new Result();
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("hand_cards", new JSONArray(java.util.Arrays.asList(parseCards(handCsv))));
            body.put("last_play", new JSONArray(java.util.Arrays.asList(parseCards(lastPlayCsv))));
            body.put("position", position);

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(is);
            res.latencyMs = System.currentTimeMillis() - t0;

            JSONObject j = new JSONObject(text);
            if (j.has("error") && !j.isNull("error")) {
                res.error = j.optString("error");
                return res;
            }
            JSONArray arr = j.optJSONArray("action");
            if (arr == null) {
                res.error = "响应缺少 action 字段: " + text;
                return res;
            }
            res.cards = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) res.cards[i] = arr.getString(i);
            res.action = pretty(res.cards);
            return res;
        } catch (Exception e) {
            res.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            res.latencyMs = System.currentTimeMillis() - t0;
            return res;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }
}