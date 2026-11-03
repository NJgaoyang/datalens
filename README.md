# DataLens

独立智能问数与动态图表平台，按 M00–M10 分模块交付。

- 系统元数据：MySQL 8.4；身份会话：Redis
- 分析数据源：外接 StarRocks，只读账号，显式元数据与主题权限范围
- 后端：Java 21 / Spring Boot；前端：Vue 3 / TypeScript / ECharts
- 当前代码阶段：M08 智能问数与多轮对话；本地单元验证通过，远端 CI/集成验证待完成。M07 后端、前端、浏览器与容器 CI 验收通过。真实提供商、真实业务题集和 StarRocks 验收仍待完成；M09–M10 尚未交付。

启动和运行配置见 [deploy/README.md](deploy/README.md)，接口见 [contracts/models.md](contracts/models.md)，验收边界见 [docs/M07-acceptance.md](docs/M07-acceptance.md)。无 StarRocks、无模型密钥时支持零数据源启动。

真实 StarRocks 集群尚未提供：模拟 JDBC/元数据测试不等于真实集群接入验收。正式接入前必须验证目标版本、TLS、只读权限和元数据映射。

本仓库不包含生产数据库配置、模型密钥或业务数据。

M08 本地范围和验证边界见 [docs/M08-acceptance.md](docs/M08-acceptance.md)，接口见 [contracts/conversations.md](contracts/conversations.md)。
