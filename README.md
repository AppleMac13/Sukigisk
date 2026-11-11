# Sukigisk
Sukigisk是SukiSU和Magisk的嫁接产物，目标是在不损失功能与可用性的前提下，替代 Magisk

* 复用SukiSU 前端页面，因此兼容所有SukiSU 主题
* 底层后端还是Magisk，因此兼容所有Magisk模块
* 用好看的界面做你熟悉的事

注意：
非官方移植项目，与Magisk团队、topjohnwu个人、SukiSU团队、ShirkNeko个人无任何关系

## 目录结构

| 目录 | 说明 |
|---|---|
| `manager/` | Android 管理器前端（Kotlin + Jetpack Compose） |
| `magisk/` | Magisk 后端（daemon、`magisk` 可执行文件、boot 修补逻辑） |
| `uapi/` | 前后端共用的内核接口头文件 |

前后端桥接在 `manager/app/src/main/cpp/magisk_bridge.cc`。

## 构建

前置：JDK 21、Android SDK（compileSdk 37 / build-tools 37.0.0）、Android NDK、CMake 3.18 及以上。

```
cd manager
./gradlew assembleDebug -PKSU_NAME=Sukigisk -PKSU_PACKAGE_NAME=com.appstor678.sukigk
```

版本号默认取 `git describe --tags`，可用 Gradle 属性覆盖：

```
./gradlew assembleDebug -PKSU_VERSION_NAME=4.1.5 -PKSU_VERSION_CODE=37187
```

后端：

```
cd magisk
python3 build.py
```

## 许可证

GNU General Public License v3.0，全文见 [LICENSE](LICENSE)。

上游项目：

- Magisk — https://github.com/topjohnwu/Magisk （GPL-3.0）
- SukiSU-Ultra — https://github.com/SukiSU-Ultra/SukiSU-Ultra （GPL-3.0）

对上游源码的修改见 [MODIFICATIONS.md](MODIFICATIONS.md)。按 GPL-3.0 第 5 条，修改过的文件在其中逐一列明。

## 依赖说明

仓库内不含任何预编译产物或二进制依赖，全部依赖来自 Maven 仓库；`*.so` 由 `manager/app/src/main/cpp/` 下的源码在构建时生成。`gradle-wrapper.jar` 是 Gradle Wrapper 的标准引导文件，属构建工具链的一部分。
