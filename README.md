# Sailboat Vehicle Mod

Minecraft Forge 1.20.1 多人航海、国家、城镇、市场与建筑蓝图模组。

## 功能概览

- 多人帆船、自动航线、停靠与货运
- 国家 / 城镇 / 领地 / 外交 / 战争系统
- 市场、国库、银行、港口物流
- 建筑蓝图、脚手架工地、结构预览与辅助放置
- ModernUI 现代化界面接入

## 环境

- Minecraft: `1.20.1`
- Forge: `47.2.0`
- Java: `17`

## 依赖库列表

### 必需依赖

- Forge `47.2.0`
- GeckoLib `4.4.9`

### 运行时 / 打包依赖

- BlockUI `1.20.1-1.0.190-snapshot`
- SQLite JDBC `3.46.1.3`
- gdx-ai `1.8.2`
- webp-imageio `0.1.6`（JarJar 打包）

### 可选依赖

- ModernUI `3.12.x`
  - 客户端可选
  - 已接入市场、国家、城镇、港口、银行、贸易、船只信息、自动航线选择、路线命名、建筑结构选择等新 UI
- Vault
  - 经济桥接为可选集成，无硬依赖
- BlueMap
  - 地图展示为可选集成

## 开发说明

### 构建

```bash
./gradlew build
```

### 快速编译

```bash
./gradlew compileJava
```

### 运行客户端

```bash
./gradlew runClient
```

### ModernUI 本地开发

- 将 `ModernUI-Forge-1.20.1-3.12.0.1-universal.jar` 放到系统 `Downloads` 目录
- 运行时开发环境会自动同步到 `run/mods`

## 输出

- 构建产物位于 `build/libs/`
- `-reobf.jar` 为发布产物

## 许可证

MIT
