# DataLens · Linux 单包部署（无 Nginx / Docker / Redis）

本发行包将 Vue 静态前端和 Spring Boot API 放在同一个 JAR 内，直接使用服务器 IP + 端口访问。MySQL 8 保存元数据、图表、问数会话、登录 Session 和登录限流计数；StarRocks 是后续添加的外接只读分析数据源。

## 目录

```text
datalens/
├── bin/
│   ├── start.sh
│   ├── stop.sh
│   ├── restart.sh
│   ├── status.sh
│   └── logs.sh
├── conf/
│   └── application.env
├── lib/
│   └── datalens-server.jar   # 已包含编译后的 Vue 页面
├── logs/
└── run/
```

## 服务器要求

Linux + Java 21 运行环境 + 已安装可连接的 MySQL 8；建议安装 curl 以供启动就绪检查。**无需 Node.js、Maven、Nginx、Docker 或 Redis**。只启动一个 DataLens 实例，不支持多个实例共享执行租约。

## 运行

```bash
tar -xzf datalens-0.1.0-linux.tar.gz
cd datalens
chmod 600 conf/application.env
vi conf/application.env
bin/start.sh
bin/status.sh
bin/logs.sh
bin/stop.sh
bin/restart.sh
```

首次安装的配置文件已有以下默认值（**这是公开的测试默认口令，不可用作公网/正式生产密码**）：

- MySQL：`127.0.0.1:3306`，用户名 `root`，密码 `123456`。
- 系统管理员：`admin`，初始密码 `admin@123456`。

仅面向**新建空的** `datalens` 元数据库。MySQL root 需能创建数据库：JDBC `createDatabaseIfNotExist=true` 首次建库，Flyway 初始化当前项目所需表，不需要导入旧版本数据。不能将本包视作历史版本的升级包；**不会自动删除已有数据库**。若名称已被旧项目使用，请先手工规划使用独立的新数据库/实例，不要直接覆盖旧数据。

管理员只在用户表为空时初始化，重复启动不会恢复或重置管理员密码。首次成功登录后请立即修改登录密码，修改 MySQL 密码及配置，清空 `DATALENS_BOOTSTRAP_USERNAME`、`DATALENS_BOOTSTRAP_PASSWORD` 并重启。

访问：**http://服务器IP:8088**。可通过 `DATALENS_PORT` 修改端口，`DATALENS_BIND_ADDRESS=0.0.0.0` 表示监听所有网卡。按需开放 Linux 防火墙，不要放开 3306 到公网。

## StarRocks（可后续接入）

通过 `DATALENS_SOURCE_ALLOWED_ENDPOINTS='StarRocks_FE_IP:9030'` 允许访问指定外部 FE（端口以实际配置为准），设置 `DATALENS_SOURCE_KEY` 为 `openssl rand -base64 32` 生成且备份的固定密钥。重启 DataLens，在后台配置只读 StarRocks 账号、库、表和业务主题。**不要复用 MySQL root 账号作为 StarRocks 账号**。

## 安全和故障处理

直接 HTTP 对局域网测试可用，但会以明文传送账号密码。**不建议公网使用 HTTP 登录**；可直接给 Spring Boot 配置 PKCS#12 HTTPS 证书（见 conf 文件注释，无需 Nginx），并设置 `DATALENS_COOKIE_SECURE=true`。实际公网访问必须有可信证书和访问控制。

关闭请用 `bin/stop.sh`，不要直接删 PID。日志路径为 `logs/datalens.log`，运行目录为 `run/`。修改 `conf/application.env` 后需要 `bin/restart.sh`。备份 MySQL 和 `DATALENS_SOURCE_KEY`；旧密钥遗失将无法恢复已存加密数据源凭据。

此包用于部署和联调，**不代表真实 StarRocks、AI 模型、生产容量及安全验收已完成**。
