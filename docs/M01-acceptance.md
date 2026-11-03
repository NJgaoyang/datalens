# M01 用户、登录与权限基础

依赖 M00，范围为身份和管理员开户，不实现图表、主题或 SQL。技术版本沿用实际远端基线：Java 21、Spring Boot 4.0.9、Spring AI 2.0.2 BOM、MyBatis-Plus 3.5.18；没有改变锁定选型。

## 实现
- V2 Flyway 创建账号与审计表，保留 V1 不变。账号只存 BCrypt(cost 12) 哈希。
- Redis 服务端会话，30 分钟不活动过期，HttpOnly/SameSite=Lax Cookie，默认 Secure。只有 localhost HTTP Compose 显式关闭 Secure。
- 每次已登录请求从 MySQL 检查 enabled/auth_version；禁用、改密、重置密码递增 auth_version，所有旧会话立即在下一次访问时拒绝，即使重启/重新启用账号也不能恢复。
- 登录更换会话与 CSRF；登录也要求 CSRF。未声明业务路由继续 denyAll。前端不保存密码或会话令牌。
- 管理员可创建两个角色的用户、分页查看、启停和重置密码；普通用户直接调用管理 API 仍拒绝。禁止自我禁用，数据库串行保护至少一个有效超管。
- 已知账号连续五次失败锁定 15 分钟。失败事件和计数持久化，认证错误统一。登录在获取数据库连接前限制单进程两路并发及同账号单路执行；Redis 全局每分钟 120 次、每用户名每分钟 10 次，覆盖不存在/锁定账号。Nginx 每来源每分钟 10 次（突发 5），不信任客户端转发地址。HTML 响应禁止被 iframe 嵌入。
- 审计记录真实操作者、动作、资源、结果和 requestId，不记录原始请求/密码/会话。

## 初始账号
设置 DATALENS_BOOTSTRAP_USERNAME 和 DATALENS_BOOTSTRAP_PASSWORD（唯一强密码），应用仅在 sys_user 为空时创建首个 SUPER_ADMIN。没有默认密码或自动打印凭据。成功后从部署环境移除这两个变量。再次启动不会修改现有账号。未设置时系统可启动但不能登录；这是明确的未配置状态。

## 验证
`mvn -B -ntp -f server/pom.xml verify -Pintegration` 在一次性 MySQL 8.4 + Redis 上运行真实 HTTP Cookie 测试：登录/会话轮换、CSRF、退出后 Cookie 重放、普通用户越权、伪造 userId、禁用及重新启用、所有会话密码失效、管理员重置、分页、重复用户名、审计主体、持久化登录锁定。FoundationIT 继续验证迁移重复执行、真实健康检查和默认拒绝。

前端执行 `npm test`、`npm run build`、`npm run test:e2e`。Compose CI 验证空库和同卷重启。测试结果应以对应提交 CI 为准，写入代码不等于验收通过。

## 后续约束
M02–M10 的资源授权仍需独立实现，不能把 M01 系统角色当作图表/查询权限。结果返回前需再次调用权限服务。Redis 中过期身份可保留至 TTL，但 MySQL auth_version 决定其无效，缓存不能覆盖 MySQL 的禁用/撤销事实。运行中的业务请求需在提交/交付边界自行复验身份及资源权限。
