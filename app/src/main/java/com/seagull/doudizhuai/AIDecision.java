package com.seagull.doudizhuai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI决策模块 - 海鸥手写
 * 操，这是AI的大脑！
 */
public class AIDecision {
    
    private Map<String, Integer> cardValue = new HashMap<>();
    
    public AIDecision() {
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
    
    public List<String> decide(List<String> myCards, List<String> lastPlay) {
        if (myCards == null || myCards.isEmpty()) {
            return null;
        }
        
        // 排序手牌
        List<String> sortedCards = new ArrayList<>(myCards);
        Collections.sort(sortedCards, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return cardValue.get(a) - cardValue.get(b);
            }
        });
        
        // 如果是主动出牌
        if (lastPlay == null || lastPlay.isEmpty()) {
            return playActive(sortedCards);
        }
        
        // 如果是被动应对
        return playPassive(sortedCards, lastPlay);
    }
    
    private List<String> playActive(List<String> cards) {
        // 简单策略：出最小的单牌
        List<String> play = new ArrayList<>();
        play.add(cards.get(0));
        return play;
    }
    
    private List<String> playPassive(List<String> myCards, List<String> lastPlay) {
        // 简单策略：找能压住上家的最小牌
        
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
    
    // TODO: 实现完整的AI算法
    // - 识别牌型（单牌、对子、三张、炸弹等）
    // - 博弈树搜索
    // - 蒙特卡洛树搜索
    // - 记牌器功能
}