# 拾穗

拾穗是一款本地优先的智能记账应用。它可以识别 Android 通知中的交易信息并自动整理账目，也支持手动记账、分类管理、账本管理和明细筛选。

## 主要功能

- 识别微信、支付宝、招商银行和掌上生活的交易通知
- 自动提取金额、商户、收支方向与银行卡信息
- 管理待确认、已确认和疑似重复账目
- 手动记账与商户分类记忆
- 一级、二级分类管理
- 多账本与账本明细
- 按月份、分类和银行卡筛选明细
- 查看通知处理链路与诊断信息
- 数据默认保存在本机

## 项目结构

- `nativeApp/app`：Android 应用、Jetpack Compose UI、Room、Hilt 和系统通知能力
- `nativeApp/domain`：纯 Kotlin 业务模型、通知解析、商户处理、场景判断与关联去重

## Android 构建

需要 JDK 17 或更高版本以及 Android SDK 35。

Windows：

```bat
cd nativeApp
gradlew.bat :domain:test :app:assembleRelease
```

Release APK 输出路径：

```text
nativeApp/app/build/outputs/apk/release/app-release.apk
```

## 隐私

账目、商户识别记忆、账本、位置场景和通知诊断数据均保存在设备本地。Android 的通知读取权限必须由用户在系统设置中手动授权。

## 平台说明

自动读取其他应用通知的功能仅适用于 Android。iOS 不允许第三方应用读取微信、支付宝等其他应用的通知。
