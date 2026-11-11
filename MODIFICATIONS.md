# 相对上游的修改

本仓库基于以下两个上游项目：

- Magisk — https://github.com/topjohnwu/Magisk （GPL-3.0）
- SukiSU-Ultra — https://github.com/SukiSU-Ultra/SukiSU-Ultra （GPL-3.0）

按照 GPL-3.0 第 5(a) 条，此处列出本仓库对上游文件的全部修改。修改日期：2026-11-11。

## manager/（前端）

| 文件 | 修改内容 |
|---|---|
| `gradle.properties` | 新增 `KSU_NAME=Sukigisk`、`KSU_PACKAGE_NAME=com.appstor678.sukigk`；新增 `KSU_VERSION_NAME`、`KSU_VERSION_CODE` 用于覆盖版本号 |
| `build.gradle.kts` | `getVersionName()` / `getVersionCode()` 支持通过 Gradle 属性或环境变量覆盖，未提供时仍回落到 `git describe` 与 commit 计数 |
| `app/build.gradle.kts` | 图标依赖由本地 `libs/material-icons-custom.jar` 改为 Maven 坐标 `androidx.compose.material:material-icons-extended`，仓库中不再包含该预编译 jar |
| `app/src/main/java/com/sukisu/ultra/ui/screen/about/AboutMaterial.kt` | 移除「检查更新」「检查 CI 版本更新」两个开关；移除贡献者头像墙与贡献者姓名墙整块（含人数徽章与两段提示）；关于正文改为本地文本，GitHub 地址渲染为内联超链接而非点击跳转网页 |
| `app/src/main/java/com/sukisu/ultra/ui/screen/home/HomeMaterial.kt` | 首页底部入口原本跳转 `https://sukisu.org/guide/`，改为跳转应用内关于页；条目文案改为「关于 Sukigisk」 |
| `app/src/main/java/com/sukisu/ultra/ui/screen/home/HomeMiuix.kt` | 同上（Miuix 界面实现） |
| `app/src/main/java/com/sukisu/ultra/ui/screen/home/HomeScreen.kt` | 新增 `onOpenAbout` 动作，接 `navigator.push(Route.About)` |
| `app/src/main/java/com/sukisu/ultra/ui/screen/home/HomeUiState.kt` | `HomeActions` 新增 `onOpenAbout` 字段 |
| `app/src/main/res/values/strings.xml` | 新增 `about_entry_summary` |
| `app/src/main/res/values-zh-rCN/strings.xml` | 新增 `about_entry_summary` |

应用名与包名通过 Gradle 属性注入（`resValue` / `applicationId`），未改动 `app/build.gradle.kts` 中的默认值逻辑本身。

## magisk/（后端）

未做功能性修改，仅随仓库携带源码。

## uapi/

未修改。
