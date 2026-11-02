# GitHub Actions 自动编译APK - 海鸥出品

**操！这是最省事的编译方案！**

---

## 优势

✅ **完全免费** - GitHub Actions免费给你编译  
✅ **不需要本地环境** - 不用装Android Studio  
✅ **自动化** - 推送代码就自动编译  
✅ **可靠** - GitHub服务器编译，稳定可靠  

---

## 使用步骤

### 第一步：上传代码到GitHub

```bash
# 1. 初始化Git仓库
cd /sdcard/Download/DouDizhuAI/
git init

# 2. 添加所有文件
git add .

# 3. 提交
git commit -m "斗地主AI - 海鸥出品"

# 4. 在GitHub创建仓库
# 去 https://github.com/new 创建一个新仓库
# 名字随便，比如：DouDizhuAI

# 5. 关联远程仓库
git remote add origin https://github.com/你的用户名/DouDizhuAI.git

# 6. 推送
git push -u origin main
```

### 第二步：等待自动编译

推送代码后，GitHub Actions会自动开始编译：

1. 打开你的GitHub仓库
2. 点击 **Actions** 标签
3. 看到 **Build APK** 工作流正在运行
4. 等待几分钟（第一次会慢，要下载依赖）
5. 编译完成后，点击工作流
6. 在 **Artifacts** 里下载 **app-debug**

### 第三步：安装APK

1. 下载的是个zip文件
2. 解压得到 `app-debug.apk`
3. 传到手机上安装
4. 开始使用！

---

## 自动Release（可选）

如果你推送到main/master分支，会自动创建Release：

1. 去仓库的 **Releases** 页面
2. 看到自动创建的版本（v1.0.1, v1.0.2...）
3. 直接下载APK
4. 不用解压，直接安装

---

## 手动触发编译

如果你想手动触发编译：

1. 去 **Actions** 标签
2. 选择 **Build APK** 工作流
3. 点击右边的 **Run workflow** 按钮
4. 选择分支，点击 **Run workflow**
5. 等待编译完成

---

## 修改代码后重新编译

```bash
# 1. 修改代码
# 编辑你想改的文件

# 2. 提交修改
git add .
git commit -m "修改了XXX功能"

# 3. 推送
git push

# 4. GitHub会自动重新编译
# 去Actions页面查看进度
```

---

## 常见问题

### Q: 编译失败了？

A: 去Actions页面看日志，找到红色的错误信息。常见原因：
- Gradle版本不对
- 依赖下载失败
- 代码有语法错误

### Q: 编译太慢？

A: 第一次编译要下载依赖，很慢。之后会快很多（有缓存）。

### Q: 能不能编译Release版本？

A: 可以！修改 `.github/workflows/build.yml`：
```yaml
- name: 构建Release APK
  run: ./gradlew assembleRelease
```

但Release版本需要签名，得配置keystore。

### Q: 怎么签名Release版本？

A: 需要：
1. 生成keystore文件
2. 把keystore加密后存到GitHub Secrets
3. 修改build.yml添加签名步骤

老子可以给你写，但你现在先用Debug版本。

### Q: 可以自动发布到其他平台吗？

A: 可以！可以配置自动发布到：
- Google Play（需要配置API密钥）
- 蒲公英（内测分发平台）
- Fir.im（内测分发平台）

### Q: 私有仓库也能用吗？

A: 可以！GitHub Actions对私有仓库也免费（每月有限额）。

---

## 完整文件结构

```
DouDizhuAI/
├── .github/
│   └── workflows/
│       └── build.yml          # GitHub Actions配置✨
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/seagull/doudizhuai/
│   │       │   ├── MainActivity.java
│   │       │   ├── AIEngine.java
│   │       │   ├── DouZeroAI.java
│   │       │   └── ... (其他Java文件)
│   │       ├── res/
│   │       │   └── layout/
│   │       │       └── activity_main.xml
│   │       └── AndroidManifest.xml
│   └── build.gradle           # App级别配置✨
├── build.gradle               # 项目级别配置✨
├── settings.gradle            # 项目设置✨
├── gradlew                    # Gradle包装器
├── gradlew.bat
└── README.md
```

---

## 下一步优化

想要更高级的功能？老子可以继续加：

1. **自动测试** - 在编译前运行单元测试
2. **代码检查** - Lint检查代码质量
3. **多变体编译** - 同时编译Debug/Release/Free/Pro版本
4. **自动发布** - 编译后自动发布到分发平台
5. **通知推送** - 编译完成后发邮件/Telegram通知

---

**操，这就是最省事的编译方案！**

你他妈现在：
1. 把代码推到GitHub
2. 等GitHub自动编译
3. 下载APK
4. 装到手机上用

**有问题随时来找老子！**