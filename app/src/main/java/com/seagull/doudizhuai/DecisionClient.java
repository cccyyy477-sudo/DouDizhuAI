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

    /** /advise 结果：识别手牌 + 建议出牌 */
    public static class AdviseResult {
        public String action;       // 建议出的牌（pretty），如 "10" / "过"
        public String[] hand;       // 识别出的全部手牌
        public String warn;         // 未识别警告（点数样本不足）
        public String error;
        public long latencyMs;
        public int n;               // 识别张数
        public int unknown;         // 未识别张数
        public String playedLeft;   // 上家（左）出的牌，空格分隔，空=无
        public String playedRight;  // 下家（右）出的牌
        public String playedMine;   // 我出的牌（下方中央）
        public Boolean myTurn;      // 服务端出牌按钮检测：轮到我出牌（null=未返回）
    }

    /** 从 /predict 或 /base URL 推导 /advise URL */
    public static String adviseUrl(String server) {
        String u = server.trim();
        if (u.isEmpty()) return u;
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        if (u.endsWith("/predict") || u.endsWith("/recognize") || u.endsWith("/advise")) {
            u = u.substring(0, u.lastIndexOf('/'));
        }
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + "/advise";
    }

    /**
     * 抓帧图(base64 JPEG) -> 服务端识别手牌 + DouZero 决策。
     * @param server 服务地址（/predict 或 base 均可）
     * @param base64Jpeg 已压缩 JPEG 的 base64（不带 data: 前缀）
     */
    public static AdviseResult advise(String server, String base64Jpeg,
                                      String position, String lastPlayCsv) {
        AdviseResult res = new AdviseResult();
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("image", base64Jpeg);
            body.put("position", position == null || position.isEmpty() ? "landlord_up" : position);
            body.put("last_play", new JSONArray(java.util.Arrays.asList(parseCards(lastPlayCsv))));

            String url = adviseUrl(server);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(30000);          // 识别+决策，给足时间
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(is);
            res.latencyMs = System.currentTimeMillis() - t0;

            JSONObject j = new JSONObject(text);
            res.n = j.optInt("n", 0);
            res.unknown = j.optInt("unknown", 0);
            res.warn = j.has("warn") ? j.optString("warn") : null;
            if (j.has("error") && !j.isNull("error") && !j.optString("error").isEmpty()) {
                res.error = j.optString("error");
                return res;
            }
            JSONArray h = j.optJSONArray("cards");
            if (h != null) {
                res.hand = new String[h.length()];
                for (int i = 0; i < h.length(); i++) res.hand[i] = h.getString(i);
            }
            JSONArray a = j.optJSONArray("action");
            if (a != null && a.length() > 0) {
                String[] cards = new String[a.length()];
                for (int i = 0; i < a.length(); i++) cards[i] = a.getString(i);
                res.action = pretty(cards);
            } else if (j.has("action")) {
                res.action = "过";                // 空 action = 不出
            }
            JSONObject pl = j.optJSONObject("played");
            if (pl != null) {
                res.playedLeft = joinCards(pl.optJSONArray("left"));
                res.playedRight = joinCards(pl.optJSONArray("right"));
                res.playedMine = joinCards(pl.optJSONArray("mine"));
            }
            if (j.has("my_turn") && !j.isNull("my_turn")) {
                res.myTurn = j.getBoolean("my_turn");   // 服务端出牌按钮检测
            }
            return res;
        } catch (Exception e) {
            res.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            res.latencyMs = System.currentTimeMillis() - t0;
            return res;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String[] parseCards(String csv) {
        if (csv == null || csv.trim().isEmpty()) return new String[0];
        String s = csv.replace('\uff0c', ' ').replace(',', ' ')
                .replace('\u3001', ' ').replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? new String[0] : s.split(" ");
    }

    /** JSONArray → "A A A 6"（空/无则返回空串） */
    private static String joinCards(org.json.JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (i > 0) sb.append(' ');
            try { sb.append(arr.getString(i)); } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    public static String pretty(String[] cards) {
        if (cards == null || cards.length == 0) return "过";
        StringBuilder sb = new StringBuilder();
        for (String c : cards) {
            if (sb.length() > 0) sb.append(' ');
            switch (c) {
                case "X": case "RJ": case "\u5c0f\u738b": sb.append("\u5c0f\u738b"); break;
                case "D": case "BJ": case "\u5927\u738b": sb.append("\u5927\u738b"); break;
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
                res.error = "\u54cd\u5e94\u7f3a\u5c11 action \u5b57\u6bb5: " + text;
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
