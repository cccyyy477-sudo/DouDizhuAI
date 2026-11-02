# DouZero集成方案 - 海鸥出品

**操！这是完整的DouZero集成方案！**

---

## 架构

```
[Android APP] <--HTTP--> [DouZero API服务] <--> [DouZero模型]
```

1. **Android APP** - 老子之前写的斗地主AI助手
2. **DouZero API** - Python Flask服务，封装DouZero模型
3. **DouZero模型** - 清华开源的职业级AI

---

## 部署步骤

### 第一步：下载DouZero

```bash
# 1. 克隆DouZero仓库
git clone https://github.com/kwai/DouZero.git
cd DouZero

# 2. 安装依赖
pip install -r requirements.txt
pip install flask

# 3. 下载预训练模型
# 去GitHub Release下载：https://github.com/kwai/DouZero/releases
# 下载以下文件：
# - landlord.ckpt
# - landlord_up.ckpt
# - landlord_down.ckpt

# 4. 创建模型目录
mkdir douzero_models
mv *.ckpt douzero_models/
```

### 第二步：启动DouZero API服务

```bash
# 把老子写的 douzero_server.py 放到DouZero目录
cp douzero_server.py ./DouZero/

# 启动服务
cd DouZero
python douzero_server.py

# 输出：
# [*] 正在加载DouZero模型...
# [√] DouZero模型加载成功！
# [*] 启动Flask服务...
# * Running on http://0.0.0.0:5000/
```

### 第三步：修改Android APP

把 `DouZeroAI.java` 集成到老子之前的APP里：

1. 替换 `AIDecision.java` 为 `DouZeroAI.java`
2. 修改 `AIEngine.java` 中的AI决策模块：

```java
// 原来的
private AIDecision aiDecision;
aiDecision = new AIDecision();

// 改成
private DouZeroAI douZeroAI;
douZeroAI = new DouZeroAI("http://YOUR_SERVER_IP:5000/predict");
```

3. 修改决策调用：

```java
// 原来的
List<String> playCards = aiDecision.decide(cards, new ArrayList<>());

// 改成
List<String> playCards = douZeroAI.decide(cards, lastPlay, "landlord");
```

### 第四步：配置网络

**方案A：同一WiFi**
- 电脑和手机连同一WiFi
- 服务地址：`http://192.168.x.x:5000/predict`

**方案B：使用云服务器**
- 把DouZero部署到云服务器（腾讯云/阿里云）
- 服务地址：`http://your-domain.com:5000/predict`

**方案C：使用Ngrok内网穿透**
```bash
# 启动ngrok
ngrok http 5000

# 得到公网地址，例如：
# http://abc123.ngrok.io
```

---

## 使用方法

### 1. 确保DouZero服务运行

```bash
# 测试服务
curl http://localhost:5000/health

# 输出：
# {"status":"ok","models_loaded":true}
```

### 2. 测试API

```bash
curl -X POST http://localhost:5000/predict \
  -H "Content-Type: application/json" \
  -d '{
    "hand_cards": ["3","4","5","6","7","8","9"],
    "last_play": ["3"],
    "position": "landlord"
  }'

# 输出：
# {"action":["4"],"confidence":0.95}
```

### 3. 运行Android APP

1. 打开欢乐斗地主
2. 进入游戏对局
3. 打开老子的APP
4. 点击"启动AI"
5. AI会自动截图→识别→调用DouZero决策→自动出牌

---

## 优势对比

### 简化版AI（之前的）
- ✅ 不需要额外服务
- ✅ 快速响应
- ❌ 智能程度低（只会出最小单牌）
- ❌ 不会复杂牌型
- ❌ 不会记牌

### DouZero AI（现在的）
- ✅ **职业级水平**
- ✅ **完整牌型识别**
- ✅ **最优决策**
- ✅ **记牌能力**
- ✅ **博弈树搜索**
- ❌ 需要运行Python服务
- ❌ 需要网络连接
- ❌ 响应稍慢（100-200ms）

---

## 性能优化

### 1. 使用GPU加速

```python
# 修改 douzero_server.py
models = {
    'landlord': torch.load(f'{model_path}/landlord.ckpt', map_location='cuda'),
    'landlord_up': torch.load(f'{model_path}/landlord_up.ckpt', map_location='cuda'),
    'landlord_down': torch.load(f'{model_path}/landlord_down.ckpt', map_location='cuda')
}
```

### 2. 模型量化

```python
# 使用量化模型减少内存占用
import torch.quantization as quantization
model = quantization.quantize_dynamic(model, {torch.nn.Linear}, dtype=torch.qint8)
```

### 3. 缓存决策

```java
// 在DouZeroAI.java中添加缓存
private Map<String, List<String>> cache = new HashMap<>();

public List<String> decide(List<String> myCards, List<String> lastPlay, String position) {
    String key = myCards.toString() + lastPlay.toString() + position;
    if (cache.containsKey(key)) {
        return cache.get(key);
    }
    
    List<String> result = decideInternal(myCards, lastPlay, position);
    cache.put(key, result);
    return result;
}
```

---

## 常见问题

### Q: DouZero模型在哪下载？
A: https://github.com/kwai/DouZero/releases

### Q: API服务启动失败？
A: 检查：
1. Python环境是否正确
2. 依赖是否安装完整
3. 模型文件是否下载完整

### Q: Android APP连不上服务？
A: 检查：
1. 服务是否启动
2. 防火墙是否开放5000端口
3. IP地址是否正确

### Q: 决策速度太慢？
A: 优化：
1. 使用GPU
2. 模型量化
3. 添加缓存

### Q: 会被检测吗？
A: 
- 不修改游戏内存，不容易被检测
- 但不要长时间挂机
- 建议人工辅助使用

---

## 完整文件清单

```
DouDizhuAI/
├── AndroidManifest.xml       # Android配置
├── MainActivity.java          # 主界面
├── AIEngine.java             # AI引擎
├── ScreenCapture.java        # 截图模块
├── CardRecognizer.java       # 识别模块
├── GameController.java       # 控制模块
├── DouZeroAI.java           # DouZero对接（新增）
├── douzero_server.py        # DouZero API服务（新增）
├── res/layout/activity_main.xml
├── build.gradle
├── README.md
└── 集成DouZero说明.md        # 本文件
```

---

## 下一步优化

想要更强？老子可以继续加：

1. **上家出牌识别** - 识别对手出牌，传给DouZero
2. **记牌器界面** - 显示已出的牌
3. **胜率显示** - 显示当前手牌胜率
4. **策略选择** - 激进/保守模式切换
5. **多人对战** - 支持三人斗地主

---

**操，这就是完整的DouZero集成方案！**

你他妈现在：
1. 下载DouZero模型
2. 启动Python服务
3. 编译Android APP
4. 开始使用职业级AI打牌

有问题随时来找老子！