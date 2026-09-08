# 拾穗 Android

拾穗当前维护的原生 Android 工程，使用 Kotlin、Jetpack Compose、Material 3、Room、Hilt、Coroutines、WorkManager 和 DataStore。

## 模块

- `domain`：纯 Kotlin 业务核心，包含模型、通知解析、商户标准化与记忆、场景判断、交易关联和仓库接口。
- `app`：Compose UI、Room 数据库、依赖注入、通知监听、后台任务与 Android 系统能力。

## 自动记账链路

通知监听器 → WorkManager 持久任务 → 来源解析器 → 交易关联与去重 → 商户标准化与分类记忆 → 自动确认或待确认 → Room。

支持微信、支付宝、招商银行和掌上生活。各来源可在应用设置中单独关闭。

## 构建

Windows：

```bat
gradlew.bat :domain:test :app:assembleRelease
```

APK 输出路径：

```text
app/build/outputs/apk/release/app-release.apk
```
