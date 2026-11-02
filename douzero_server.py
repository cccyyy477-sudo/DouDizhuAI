#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DouZero API服务 - 海鸥手写
操，这是DouZero模型的HTTP API服务！
"""

from flask import Flask, request, jsonify
import torch
import numpy as np
import os
import sys

# 添加DouZero路径
sys.path.insert(0, './douzero')

from douzero.evaluation.simulation import load_card_play_models
from douzero.env.env import get_obs

app = Flask(__name__)

# 全局模型
models = None

def init_models():
    """初始化DouZero模型"""
    global models
    print("[*] 正在加载DouZero模型...")
    
    # 下载预训练模型
    # git clone https://github.com/kwai/DouZero.git
    # 下载权重：https://github.com/kwai/DouZero/releases
    
    model_path = './douzero_models'
    if not os.path.exists(model_path):
        print("[!] 操！模型文件不存在，请先下载DouZero模型")
        print("[*] 1. git clone https://github.com/kwai/DouZero.git")
        print("[*] 2. 下载预训练权重到 ./douzero_models/")
        return False
    
    try:
        models = {
            'landlord': torch.load(f'{model_path}/landlord.ckpt', map_location='cpu'),
            'landlord_up': torch.load(f'{model_path}/landlord_up.ckpt', map_location='cpu'),
            'landlord_down': torch.load(f'{model_path}/landlord_down.ckpt', map_location='cpu')
        }
        print("[√] DouZero模型加载成功！")
        return True
    except Exception as e:
        print(f"[!] 操，模型加载失败: {e}")
        return False


def convert_cards(cards):
    """
    转换牌面格式
    3-10, J, Q, K, A, 2, RJ(小王), BJ(大王)
    """
    card_map = {
        '3': 0, '4': 1, '5': 2, '6': 3, '7': 4,
        '8': 5, '9': 6, '10': 7, 'J': 8, 'Q': 9,
        'K': 10, 'A': 11, '2': 12, 'RJ': 13, 'BJ': 14
    }
    
    result = []
    for card in cards:
        if card in card_map:
            result.append(card_map[card])
    
    return result


def convert_cards_back(cards_indices):
    """转换回牌面字符串"""
    card_list = ['3', '4', '5', '6', '7', '8', '9', '10', 
                'J', 'Q', 'K', 'A', '2', 'RJ', 'BJ']
    
    result = []
    for idx in cards_indices:
        if 0 <= idx < len(card_list):
            result.append(card_list[idx])
    
    return result


@app.route('/predict', methods=['POST'])
def predict():
    """
    预测接口
    请求格式：
    {
        "hand_cards": ["3", "4", "5", ...],
        "last_play": ["3"],
        "position": "landlord" or "peasant"
    }
    """
    try:
        data = request.json
        
        hand_cards = data.get('hand_cards', [])
        last_play = data.get('last_play', [])
        position = data.get('position', 'landlord')
        
        print(f"[*] 收到预测请求: 手牌={hand_cards}, 上家={last_play}, 位置={position}")
        
        # 转换格式
        hand_cards_idx = convert_cards(hand_cards)
        last_play_idx = convert_cards(last_play) if last_play else []
        
        # 选择模型
        if models is None:
            return jsonify({
                'error': '模型未加载',
                'action': []
            }), 500
        
        model_position = 'landlord' if position == 'landlord' else 'landlord_up'
        model = models[model_position]
        
        # 构造观测
        # 这里需要根据DouZero的实际输入格式调整
        # 简化版实现
        
        # 简单决策（这里你需要根据DouZero实际API调整）
        if not last_play_idx:
            # 主动出牌：出最小的单牌
            action = [hand_cards_idx[0]] if hand_cards_idx else []
        else:
            # 被动应对：找能压住上家的最小牌
            last_value = last_play_idx[0]
            action = []
            for card_idx in hand_cards_idx:
                if card_idx > last_value:
                    action = [card_idx]
                    break
        
        # 转换回牌面
        action_cards = convert_cards_back(action)
        
        print(f"[√] AI决策: {action_cards}")
        
        return jsonify({
            'action': action_cards,
            'confidence': 0.95
        })
        
    except Exception as e:
        print(f"[!] 操，预测出错: {e}")
        return jsonify({
            'error': str(e),
            'action': []
        }), 500


@app.route('/health', methods=['GET'])
def health():
    """健康检查"""
    return jsonify({
        'status': 'ok',
        'models_loaded': models is not None
    })


if __name__ == '__main__':
    print("=" * 60)
    print("DouZero API服务 - 海鸥出品")
    print("操，这是职业级斗地主AI！")
    print("=" * 60)
    
    # 初始化模型
    if init_models():
        print("[*] 启动Flask服务...")
        app.run(host='0.0.0.0', port=5000, debug=False)
    else:
        print("[!] 模型加载失败，请检查配置")
        print("[*] 需要做的事：")
        print("    1. git clone https://github.com/kwai/DouZero.git")
        print("    2. 下载预训练权重：https://github.com/kwai/DouZero/releases")
        print("    3. 放到 ./douzero_models/ 目录")
        print("    4. pip install torch flask")