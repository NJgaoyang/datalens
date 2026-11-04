# DataLens 直接运行版：单 JAR + tar.gz，无 Nginx、Docker、Redis

当前 Linux 原生部署改为“一键打包、解压后配置并启动”的模式，不再依赖 systemd 或外部静态 Web 服务器。

## 在打包机器执行

要求 Java 21 JDK、Maven 3.9.x、Node.js 22.12+ 或 24.x、npm、tar：

```bash
git clone -b dev20261104 https://github.com/NJgaoyang/datalens.git
cd datalens
bash package.sh
# 也可以用 sh package.sh（脚本会自动切换到 Bash）
```

产物：`dist/datalens-0.1.0-linux.tar.gz`，包含前端静态资源、Spring Boot 可执行 JAR、配置模板、启动/关闭/状态/日志脚本。不把 MySQL 真实密码或数据源密钥打入包中。

## 在 Linux 服务器执行

运行机器只需要 Java 21、MySQL 8（本机 127.0.0.1:3306）和可用磁盘：

```bash
tar -xzf datalens-0.1.0-linux.tar.gz
cd datalens
vi conf/application.env
bin/start.sh
```

浏览器打开 `http://服务器IP:8088`。配置 `DATALENS_PORT` 可修改端口；`bin/stop.sh`、`bin/restart.sh`、`bin/status.sh`、`bin/logs.sh` 可操作服务。无需 Nginx；Spring Boot 同一个端口直接提供 Vue 及 API。更具体说明在 tar 包内的 `README.md`。

此全新项目的 tar 包配置默认连接本机 MySQL `127.0.0.1:3306`（用户 `root`、密码 `123456`），首次系统登录使用 `admin / admin@123456`。这些是**公开且不安全的首次安装默认值**，仅供隔离环境初始化，请在联网运行前更改。

JDBC 打开 `createDatabaseIfNotExist=true`，首次使用有 CREATE 权限的账号建立空 `datalens` 数据库，Flyway 初始化当前项目结构。**不兼容旧项目数据库及接口，不会迁移旧数据，也不会自动清理已有库**；如果 `datalens` 已存旧表，必须先另行准备一个空库（可修改 URL 的数据库名），而不是自动清空已有内容。首次登录后修改管理员密码，清空两项 bootstrap 配置并重启；之后不会重置现有账号。

外接 StarRocks 使用单独只读账号，需先填写 `DATALENS_SOURCE_ALLOWED_ENDPOINTS` （数值 IP:端口）和独立永久 `DATALENS_SOURCE_KEY`。没有外部 StarRocks 时也能启动并登录。

直接公网 HTTP 登录不安全；生产对外需内置 TLS 证书、设置 Secure Cookie，或严格限制在可信内网。真实 StarRocks/模型性能/恢复等生产验收仍需完成。
