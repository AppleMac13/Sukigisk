# Sukigisk

SukiSU 前端 + Magisk 后端的嫁接项目。

复用 SukiSU 的管理器界面，底层真正干活的是 Magisk。

## 声明

**非官方移植项目。** 与 Magisk 团队、topjohnwu 个人、SukiSU 团队、ShirkNeko 个人无任何关系。

## 目录结构

| 目录 | 说明 |
|---|---|
| `manager/` | Android 管理器前端（SukiSU-Ultra 衍生，Kotlin + Jetpack Compose） |
| `magisk/` | Magisk 后端（daemon、`magisk` 可执行文件、boot 修补逻辑） |
| `uapi/` | 前后端共用的内核接口头文件 |

前后端的桥接在 `manager/app/src/main/cpp/magisk_bridge.cc`。

## 构建

前置：JDK 21、Android SDK（compileSdk 37 / build-tools 37.0.0）、Android NDK、CMake 3.18+。

```bash
cd manager
./gradlew assembleDebug \
  -PKSU_NAME=Sukigisk \
  -PKSU_PACKAGE_NAME=com.appstor678.sukigk
```

版本号默认取 `git describe --tags`。可用 Gradle 属性覆盖：

```bash
-PKSU_VERSION_NAME=4.1.5 -PKSU_VERSION_CODE=37187
```

后端：

```bash
cd magisk
python3 build.py
```

## 许可证

GNU General Public License v3.0，全文见 [LICENSE](LICENSE)。

上游项目：

- **Magisk** — https://github.com/topjohnwu/Magisk （GPL-3.0）
- **SukiSU-Ultra** — https://github.com/SukiSU-Ultra/SukiSU-Ultra （GPL-3.0）

本项目对上述源码的修改见 [MODIFICATIONS.md](MODIFICATIONS.md)。按 GPL-3.0 第 5 条，修改过的文件在 `MODIFICATIONS.md` 中逐一列明。

## 第三方二进制依赖

`manager/app/libs/material-icons-custom.jar` 是构建期依赖库，随仓库提供以避免拉取私有制品。除此之外仓库内不含任何预编译产物；`*.so` 由 `manager/app/src/main/cpp/` 下的源码在构建时生成。
