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

编辑 conf/application.env：`DATALENS_DB_USERNAME=root`、`DATALENS_DB_PASSWORD` 为本机 MySQL 的实际密码。默认连接 127.0.0.1:3306。首次启动时 JDBC 可用 CREATE 权限自动创建 datalens 数据库；Flyway 自动创建表（V1–V12）；不需手工导入 SQL。若 MySQL 不允许自动建库，可提前执行 `CREATE DATABASE datalens CHARACTER SET utf8mb4;`。

务必修改 `DATALENS_BOOTSTRAP_PASSWORD`，首次启动会创建管理员 `admin`。账号创建后将这两个初始化变量清空，再重启；不会重置或删除用户。

访问：**http://服务器IP:8088**。可通过 `DATALENS_PORT` 修改端口，`DATALENS_BIND_ADDRESS=0.0.0.0` 表示监听所有网卡。按需开放 Linux 防火墙，不要放开 3306 到公网。

## StarRocks（可后续接入）

通过 `DATALENS_SOURCE_ALLOWED_ENDPOINTS='StarRocks_FE_IP:9030'` 允许访问指定外部 FE（端口以实际配置为准），设置 `DATALENS_SOURCE_KEY` 为 `openssl rand -base64 32` 生成且备份的固定密钥。重启 DataLens，在后台配置只读 StarRocks 账号、库、表和业务主题。**不要复用 MySQL root 账号作为 StarRocks 账号**。

## 安全和故障处理

直接 HTTP 对局域网测试可用，但会以明文传送账号密码。**不建议公网使用 HTTP 登录**；可直接给 Spring Boot 配置 PKCS#12 HTTPS 证书（见 conf 文件注释，无需 Nginx），并设置 `DATALENS_COOKIE_SECURE=true`。实际公网访问必须有可信证书和访问控制。

关闭请用 `bin/stop.sh`，不要直接删 PID。日志路径为 `logs/datalens.log`，运行目录为 `run/`。修改 `conf/application.env` 后需要 `bin/restart.sh`。备份 MySQL 和 `DATALENS_SOURCE_KEY`；旧密钥遗失将无法恢复已存加密数据源凭据。

此包用于部署和联调，**不代表真实 StarRocks、AI 模型、生产容量及安全验收已完成**。
