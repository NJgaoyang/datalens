# DataLens 需求基线

> **2026-11-04 生效说明**：本文件下文的 M00–M10、Redis、Docker、Nginx 章节为早期开发历史记录，不再定义新分支的兼容边界。当前 `dev20261104` 以 [全新项目基线](greenfield-baseline-20261104.md) 和 [Chat2DB 参考重设计](redesign-20261104-chat2db.md) 为准：不兼容旧代码/旧数据库，空 MySQL 8 首次安装，Linux 原生单 JAR，MySQL JDBC Session，外接只读 StarRocks。不得自动清理已有库。

当前实现推进至 M09 业务功能与 M10 开发集成验收/生产准备。真实 StarRocks、模型、人工确认业务题集、约定性能、生产安全与恢复演练仍阻断上线；详见 M10-acceptance.md。测试通过不代表生产验收通过。

## 1. 已确认方向

DataLens 是独立 Web 智能 BI 系统，以模块化单体、分批验收推进。本文依据用户提供的开发方案整理；工程名采用 DataLens，原方案中的 smartbi 仅为占位名。模块是同一工程内的业务边界，不是微服务。

优先完成：登录 → 受控查询 StarRocks → 保存图表 → 重新打开并刷新 → 指定共享 → 撤销权限。自然语言问数在这一闭环之后接入。模型不可用不得影响已保存图表刷新。

## 2. 原始 M00 工程范围（历史基线）

本次只实现可持续开发的工程骨架：

- Vue 前端布局与构建、Spring Boot 应用与测试入口。
- 统一错误响应、分页约定、请求追踪 ID、环境配置与健康检查。
- MySQL 系统数据库、Redis 与 Flyway 迁移入口。
- Docker Compose、Nginx 和持续集成配置。
- 需求、架构、权限矩阵与 AI 工程规则。

M00 不实现登录、用户管理、图表、查询执行或模型问数接口；不得以占位数据或固定成功响应冒充已完成业务功能。模块名称和权限矩阵是后续实施约束，不表示这些能力已经上线。

### M00 验收清单

| 场景 | 验收标准 |
| --- | --- |
| 空库启动 | 使用全新 MySQL 数据卷，应用成功启动，Flyway 自动创建所需结构 |
| 重复启动 | 使用同一数据卷再次启动，迁移不重复执行、不重复初始化数据 |
| 可构建 | 前端类型检查及生产构建成功，后端测试通过 |
| 外部服务可选 | 未配置模型服务和 StarRocks，M00 仍可启动 |
| 错误与追踪 | 错误有稳定结构和追踪 ID，不向客户端暴露异常堆栈或秘密 |
| 部署链路 | Nginx 能提供前端并将 API 请求转发至后端 |

验收结果以实际执行记录为准。CI 配置、测试代码存在不等于运行成功；容器集成测试不等于真实 StarRocks 联调。

## 3. 固定技术边界

- 前端：Vue 3、TypeScript、Element Plus、ECharts。
- 后端：Java 21、Spring Boot、模块化单体；Spring AI 通过独立适配层接入。
- 系统元数据：MySQL 8.4、MyBatis-Plus；版本变更仅使用 Flyway。
- 分析执行：外部 StarRocks、独立 JDBC 连接池、受控只读查询。
- SQL 解析：JSQLParser + 自建规则 + 实际 StarRocks 方言测试；解析失败必须拒绝。
- 缓存与会话：MySQL 8 JDBC Session，无 Redis；持久化图表、授权、任务都保存在 MySQL。
- 部署：Linux 原生单 JAR + tar.gz / conf / bin，通过 IP:端口访问，无 Docker/Nginx。

不引入 Python 服务、微服务注册中心、Kafka、向量数据库或多 Agent 专家团。不集成旧项目；Chat2DB、Codata 仅作参考，复用代码需记录来源、提交、许可证和测试证据。

## 4. 后续模块与验收顺序

| 模块 | 依赖 | 核心交付及验收 |
| --- | --- | --- |
| M01 用户与权限 | M00 | SUPER_ADMIN / USER；管理员开户、登录退出、密码管理、禁用；服务端会话、安全 Cookie、CSRF；服务端确定身份，禁用及改密失效旧会话 |
| M02 数据源与元数据 | M01 | 管理员配置数据源和授权主题；只采集允许的表字段；隐藏凭据，限制连接地址，连接更新关闭旧池 |
| M03 安全查询 | M02 | SQL 完整解析、表字段函数及参数白名单、持久化任务、限流限时、数据库端取消、重启恢复、幂等及审计；结果返回时再次鉴权 |
| M04 动态图表 | M03 | 表格、指标卡、柱状图、折线图、饼图；Chart / ChartRevision；私有保存、预览发布、重新打开执行新查询、业务时区相对日期 |
| M05 共享管理 | M04 | 私有、指定用户、全员共享及撤权；超管管理、恢复、所有权转移；共享不能扩大自由查询权限 |
| M06 业务语义 | M02 | 一个真实主题的字段、指标、维度、关联、审核题集和发布版本；20–30 个审核业务问题，识别关联重复累计 |
| M07 模型适配 | M03、M06 | ModelGateway；QueryIntent / QueryPlan / ChartSpec 结构校验；超时预算、有限修复；模型工具只能走安全查询服务 |
| M08 多轮问数 | M04、M05、M07 | 语义检索、歧义澄清、安全查询、预览、追问和确认保存；会话按用户隔离，无数据不编造，复用图表服务 |
| M09 工作台与导出 | M05、M08 | 个人/共享资产、搜索、管理导航；列表不批量执行 SQL；导出独立授权，创建和下载时鉴权、短期文件 |
| M10 集成与生产验收 | 前述模块 | 业务闭环、隔离、安全、正确性、故障、升级恢复、约定性能；不少于 100 个业务评测案例，题集冻结后确定正确率目标 |

里程碑一为 M00–M05 动态图表平台，二为 M06–M08 智能问数平台，三为 M09–M10 可上线版本。各模块通过验收后再进入下一项；权限、审计和回归测试从 M01 起持续交付。

## 5. 不可弱化的产品与安全约束

- 未登录用户不能访问图表；用户不能通过修改请求 userId 改变操作身份。
- 共享仅允许查看已发布图表及受限筛选；导出不随查看权自动授予。
- 撤权、账号禁用或源数据策略撤销后，任务结果、缓存和下载仍需重新鉴权。
- 仅允许只读、已授权 SQL。未知语法、多语句、写入、DDL、越权对象及未允许的外部访问能力均拒绝；不能解析失败后关键词放行。
- 查询截断需明确提示，不得对截断样本重新求和并冒充完整总额。金额精度、大数值、空值及日期规则必须测试。
- 刷新失败显示失败及上次成功时间；历史缓存不能冒充实时结果。
- 模型、数据库凭据和会话令牌不得进入前端、提示词或普通日志。
- 测试使用临时容器、脱敏夹具或专门授权测试数据库，不对生产 StarRocks 写入。

## M08 local scope
Owner-isolated durable conversation/history, trusted published metric selection, clarification, safe chart preview, no-data/truncation/query-time display, exact previous-month follow-up using human-reviewed parameter/timezone contracts, and explicit idempotent private chart save. First release uses polling, not streaming or reasoning traces. Unsupported modifications clarify instead of rewriting SQL. Saved semantic charts refresh without a provider but fail closed after semantic withdrawal/publication changes. Actual provider, StarRocks and independent business-correctness gates remain separate.
