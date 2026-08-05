# 超级小爱版本适配指南

小爱(com.miui.voiceassist)每个大版本的**混淆类名和方法名都可能变化**。本模块通过两层机制应对:

1. **候选类名数组**(`HookEntry.kt` 里的 `*_CLASSES` 列表)—— 已知版本直接命中
2. **运行时特征探测**(`ClassProbe.kt`)—— 候选全部失败时,自动扫描小爱 APK 的 dex,
   按「混淆不碰的锚点」(字符串字面量/未混淆方法名/Kotlin object 结构)定位类

**大多数版本更新会自动适配,无需人工干预。** 只有小爱**功能重构**(锚点消失)时才需要人工适配,
本文件是那个场景的操作手册。

---

## 已适配版本映射表

| Hook 点 | 7.513.23.0010(作者测试版) | 7.12.2.0318(本机) |
|---|---|---|
| ASR 处理器 | `z10.a` / `processed` | `g10.d` / `filterInstruction` |
| ASR 识别通道 | (无) | `nl/p.onMessage` |
| ASR 对话引擎入口 | (无) | `ql/f.interceptInstruction` |
| RN 桥 | `r70.a` | `yp0.a` |
| Agent 动作 | `kh0.s0` | `dh0.s0` |
| ToastStreamPlayer | `la0.n1` | `ea0.n1` |
| 音轨管理 | `v20.e` | `o20.e` |
| Toast 卡片 | `jb0.vd` | `instruction.base.b` |
| UI 导航 | `jb0.ue` | `cb0.qe` |
| Compose 卡内容流 | (无) | `cb0/db.A0` |
| ToastStream 操作 | (无) | `pb0/s.r0()` |
| 停止生成按钮 | (无) | `ResultOperationComposeCard.setLlmStopGenerateVisible` |

### 特征锚点(ClassProbe 使用的)

| Hook 点 | 字符串锚点 | 方法锚点 |
|---|---|---|
| ASR 处理器 | `SpeechRecognizer.RecognizeResult` | `processed` / `filterInstruction` |
| RN 桥 | — | `sendStreamData`(JS 桥方法名不能混淆) |
| Agent 动作 | — | `executeActionsAsync` |
| ToastStreamPlayer | `ToastStreamPlayer`(TAG) | Kotlin object 单例 |
| 音轨管理 | `toastStreamTts` | — |
| Toast 卡片 | — | `g0(int)` / `i0(int)` |
| UI 导航 | `setSimulateKeyEvent` | `z0()` |

---

## 何时需要人工适配

- 小爱更新后测试「现在几点了」,**回答回到小爱原生**(模块静默降级)
- 模块 App「记录」页**没有任何新记录**
- logcat 里出现 `ClassProbe: no class matched`(某个锚点失配)

## 人工适配流程

前置:PC 上有设备小爱 APK(从手机拉取或应用商店获取)。

### 1. 定位新类名(方法一:反编译)

```bash
# 拉取设备上的小爱 APK
adb shell su -c 'cp /product/app/VoiceAssistAndroidT/VoiceAssistAndroidT.apk /data/local/tmp/'
adb pull /data/local/tmp/VoiceAssistAndroidT.apk

# 反编译(需 JDK 17)
export JAVA_HOME="C:\\Users\\<你的用户>\\jdk17\\jdk-17.0.20+8"
jadx/bin/jadx.bat -d out classes3.dex classes7.dex   # 按需指定 dex
```

然后用特征字符串搜索:`grep -rln "<锚点字符串>" out/sources/`

### 2. 定位新类名(方法二:dex 字符串扫描脚本)

仓库根目录的 `tools/` 下可放一份 dex 扫描脚本(Python),
原理:先查字符串表过滤 → 对命中 dex 做 const-string 指令扫描 → 反查所属类。

### 3. 验证候选类

- 确认类的**方法签名**与 HookEntry 里 `getDeclaredMethod` 的期望一致
- 确认 Kotlin object / 静态注册表等结构特征

### 4. 更新映射

- 把新类名**追加**到 `HookEntry.kt` 对应 `*_CLASSES` 数组末尾(旧的在前面,保持兼容)
- 方法名变化时,更新对应 hook 的方法候选(如 ASR 的 `processed`/`filterInstruction`)
- 锚点变化时,更新 `featureFor()` 里的特征定义

### 5. 重建安装

```bash
export JAVA_HOME="C:\\Users\\<你的用户>\\jdk17\\jdk-17.0.20+8"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/
adb shell su -c 'pm install -r -d /data/local/tmp/app-debug.apk'
```

### 6. 验证

小爱输入「现在几点了」→ 回答来自 MiniMax → 成功。
(若模块被卸载重装,需在模块 App 里重新填 API Key)

---

## 构建环境备忘(本机)

- JDK 17:`C:\Users\MindTrace\jdk17\jdk-17.0.20+8`
- Android SDK:`C:\Users\MindTrace\android-sdk`(platform-37 + build-tools 36/37)
- `local.properties` 里 `sdk.dir` 必须用**双反斜杠**(Java properties 转义)

## OTA 后恢复

运行 `fix-after-ota.sh`(检测版本 → 重装模块 → 恢复无障碍 → 重启小爱 → 验证)。
