# Sailboat Vehicle Mod

Minecraft Forge 1.20.1 模组，核心方向是航海运输、国家城镇治理、领地控制、市场贸易和蓝图建筑。

## 当前内容

- 多人帆船、停靠、货运、自动航线
- 国家 / 城镇 / 领地 / 外交 / 战争系统
- 市场、港口、银行、国库与物流联动
- 建筑蓝图、脚手架工地、结构预览、辅助放置

## 环境要求

- Minecraft: `1.20.1`
- Forge: `47.2.0`
- Java: `17`

## 依赖库

### 必需依赖

- Forge `47.2.0`
- GeckoLib `4.4.9`

### 项目依赖

- BlockUI `1.20.1-1.0.190-snapshot`
- gdx-ai `1.8.2`
- SQLite JDBC `3.46.1.3`
- webp-imageio `0.1.6`（JarJar 打包）

### 可选依赖

- Vault
  - 可选经济桥接，无硬依赖
- BlueMap
  - 可选地图展示集成

## 安装说明

### 玩家使用

1. 安装 Minecraft `1.20.1`
2. 安装 Forge `47.2.0`
3. 将本模组 jar 放入 `mods/`

## 开发命令

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

### 运行服务端

```bash
./gradlew runServer
```

### 数据生成

```bash
./gradlew runData
```

## 构建输出

- 产物目录: `build/libs/`
- 发布包: `*-reobf.jar`

## 当前进展

- 市场后端已开始接入 SQLite 方向，为后续真实浮动市场做准备

## License

MIT
