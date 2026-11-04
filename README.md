# DataLens

> **全新项目开发原则（dev20261104）**：不承担旧分支、旧页面、历史接口及历史 MySQL 表结构兼容；按**首次空库安装**设计，后续可直接整理或重建未发布的 schema/模块。绝不自动清库或修改其它部署数据。参见 [新项目基线](docs/greenfield-baseline-20261104.md)。

默认发行包配置：MySQL `127.0.0.1:3306` / `root` / `123456`；新空库首次登录 `admin` / `admin@123456`。**均为公开且不安全的初始化默认值；首次登录后必须修改，公网不可使用默认凭据或裸 HTTP。**

## dev20261104 重设计：问数优先工作区

已按确认的 **C 端智能问数设计图** 实现新版侧边栏、欢迎引导（空库也可见）、已授权会话记录、分析理解卡、查询结果图表/表格、建议追问和右侧可用数据。**展示数据来自真实授权接口，不会默认生成假销售业绩**。详情与自动化验收见 [D02 C 端页面验收](docs/D02-consumer-ask-acceptance.md)。


本分支基于 `dev20261104-linux-native` 创建，参考 Chat2DB 的工作区组织方式，保留企业内部多用户权限与原生部署。

**D01 第一批页面代码已实现**：紧凑主导航、问数首页、首问自动建会话、历史栏、结果页签与确认保存弹窗。默认访问 `/` 或登录成功后进入智能问数。实现范围、测试命令、截图位置及未完成事项见 [D01 工作区验收说明](docs/D01-workspace-acceptance.md)。当前提交是否通过自动化检查，以 GitHub Actions 对该提交的实际结果为准。

完整方向见 [Chat2DB 参考重设计方案](docs/redesign-20261104-chat2db.md)。**D02 自由查询生成和 D03 通用多轮修改尚未实现**；当前问数仍使用已发布业务口径。没有复制 Chat2DB 源码、改变业务 API 权限、清库或部署。

## 一键 tar 包部署：直接 IP:端口访问

本分支继承原生打包方式：执行 `bash package.sh`，生成 `dist/datalens-0.1.0-linux.tar.gz`。发行包包含单 JAR（内嵌 Vue 页面）、`conf/application.env` 和 `bin/start.sh` / `bin/stop.sh` / `bin/status.sh` / `bin/restart.sh`。目标 Linux 只需要 Java 21 + 本机 MySQL 8，无需 Nginx、Redis、Docker。使用 `http://服务器IP:8088` 访问，完整步骤见 [Linux 单包部署文档](deploy/native/README.md)。

- 系统元数据、登录 Session 与限流：MySQL 8（Spring Session JDBC，无 Redis）。
- 分析数据源：外接 StarRocks，只读账号，显式元数据与主题权限范围。
- 后端：Java 21 / Spring Boot；前端：Vue 3 / TypeScript / ECharts。
- 原实现为 M00–M09 业务功能与 M10 集成验收/生产准备。真实 StarRocks、模型与业务题集、性能、安全和恢复演练仍需独立验收，不能将 UI 自动化通过视为生产上线批准。

本分支运行与打包见 [deploy/native/README.md](deploy/native/README.md)；历史 Docker 配置见 [deploy/README.md](deploy/README.md)。无 StarRocks、无模型密钥时支持零数据源启动。模拟 JDBC/元数据测试不等于真实集群接入验收，正式接入前必须验证目标版本、TLS、只读权限和元数据映射。

本仓库不包含生产数据库配置、模型密钥或业务数据。

## 原模块验收与合同

模型、会话、导出合同分别见 [contracts/models.md](contracts/models.md)、[contracts/conversations.md](contracts/conversations.md)、[contracts/exports.md](contracts/exports.md)。

原模块验收记录见 [M07](docs/M07-acceptance.md)、[M08](docs/M08-acceptance.md)、[M09](docs/M09-acceptance.md)、[M10](docs/M10-acceptance.md)；生产准备与恢复流程见 [deploy/production-readiness.md](deploy/production-readiness.md)。这些是各自提交的历史证据，不替代当前重设计分支的验证。
