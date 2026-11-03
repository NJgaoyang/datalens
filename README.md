# DataLens

独立智能问数与动态图表平台，按 M00–M10 分模块交付。

- 系统元数据：MySQL 8.4；身份会话：Redis
- 分析数据源：外接 StarRocks，只读账号，显式元数据与主题权限范围
- 后端：Java 21 / Spring Boot；前端：Vue 3 / TypeScript / ECharts
- 当前代码阶段：M00–M09 业务开发与 M10 集成验收/生产准备。生产验收仍被真实 StarRocks、模型/业务题集、约定性能、安全与恢复演练阻断；不得直接上线。

启动和运行配置见 [deploy/README.md](deploy/README.md)，接口见 [contracts/models.md](contracts/models.md)，验收边界见 [docs/M07-acceptance.md](docs/M07-acceptance.md)。无 StarRocks、无模型密钥时支持零数据源启动。

真实 StarRocks 集群尚未提供：模拟 JDBC/元数据测试不等于真实集群接入验收。正式接入前必须验证目标版本、TLS、只读权限和元数据映射。

本仓库不包含生产数据库配置、模型密钥或业务数据。

M08 本地范围和验证边界见 [docs/M08-acceptance.md](docs/M08-acceptance.md)，接口见 [contracts/conversations.md](contracts/conversations.md)。

M09 范围与验证边界见 [docs/M09-acceptance.md](docs/M09-acceptance.md)，接口见 [contracts/exports.md](contracts/exports.md)。

M10 验收矩阵与证据见 [docs/M10-acceptance.md](docs/M10-acceptance.md)，生产准备与恢复流程见 [deploy/production-readiness.md](deploy/production-readiness.md)。

开发加固验收通过：代码提交 `61fedfbab3e2a46a7a4e1c863e16be75e8451a8e`，后端404单元+106真实MySQL/Redis集成、前端69单元+83浏览器测试，以及空库/升级/故障恢复/新数据卷备份还原均通过。新增容量限制不删除数据；生产验收仍受上述真实联调、业务、性能与运维条件阻断。完整证据见M10验收文档。
