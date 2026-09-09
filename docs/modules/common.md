# 公共模块 · common-core / common-web

公共模块提供各服务共用的响应、事件外壳，以及 Servlet 请求的身份和追踪上下文。它没有独立业务 HTTP 接口，不管理账号、用户资料或文件。`common-core` 不依赖具体业务；`common-web` 供 Servlet 服务引入，当前 WebFlux 网关不使用这套 Servlet 过滤器。

## Part 1：统一数据格式

### 接口响应外壳

业务接口可使用 `ApiResponse<T>` 返回 `code`、`message`、`data`。例如 `ApiResponse.ok(业务结果)` 构造业务成功外壳，默认 `code=200`、`message=ok`，具体结果放在 `data`；无业务数据时为 `null`。

这只是数据结构，不会自动设置 HTTP 状态，也不是所有异常的统一处理器。文件接口用它包裹 `201` 上传成功或 `202` 确认受理时，外壳 `code` 仍可能是 `200`。调用方要结合真实 HTTP 状态和业务数据判断结果，不能看到外壳 `200` 就认定异步工作已完成。`204` 删除成功则没有响应正文。

使用入口：[ApiResponse](../../common/common-core/src/main/java/com/calles/platform/common/core/ApiResponse.java)。错误到 HTTP 的映射仍由各服务的异常处理代码负责。

### 事件公共外壳

`EventEnvelope<T>` 承载事件 ID、类型、版本、发生时间、生产者、业务主体 ID、追踪 ID 和具体载荷。生产者先构造本服务的载荷，再装入信封；消费者按自己支持的事件版本解码，而不是共享对方的领域实体。

例如认证服务发布账号创建事件，用户服务使用自己的载荷类型读取账号 ID，再决定是否建档。公共信封本身不验证账号 ID、不自动发布消息、不自动去重，也不保证 traceId 或载荷必填。相关校验和幂等事务分别在事件生产、接收及应用处理代码中实现。

使用入口：[EventEnvelope](../../common/common-core/src/main/java/com/calles/platform/common/core/event/EventEnvelope.java)。实际例子见[认证模块](auth.md)与[用户模块](user.md)。

## Part 2：请求上下文

### 给请求关联追踪标识

引入 `common-web` 的 Servlet 服务会自动注册追踪过滤器。请求到来时，它接受符合 `[A-Za-z0-9._-]{1,64}` 的 `X-Trace-Id`，缺失或不合法就生成 UUID；随后写入当前线程的日志上下文 MDC，并在响应中返回 `X-Trace-Id`。

业务代码可以用这个标识关联一次请求产生的日志，注册事件也可取出它继续携带。请求结束时在 `finally` 清理当前线程中的追踪标识，避免线程复用把下一次请求串到一起。这个过滤器不自动为任意远程调用加 Header，也不自动为所有异步线程传播上下文；跨线程或消息传播由实际调用点处理。

使用入口：[TraceIdFilter](../../common/common-web/src/main/java/com/calles/platform/common/web/filter/TraceIdFilter.java)。它不是完整的分布式追踪采样或导出系统。

### 在业务代码中读取当前身份

身份过滤器读取网关注入的 `X-User-Id`、`X-User-Role`、`X-User-Type`、`X-Session-Id`。有非空用户 ID 时创建 `UserInfo`，放入当前线程的 `UserContext`；缺少 ID 时不创建身份，也不在公共层直接拒绝请求，让健康检查等无身份入口继续处理。

业务服务随后通过自己的访问策略要求登录、普通用户或管理员。完成或抛出异常后，过滤器在 `finally` 清理身份上下文。这里的上下文是请求线程内数据，不是持久登录会话，也不会自动跨异步任务传递。

该过滤器不验证 JWT 或 Header 的来源。用户和文件服务之所以能使用这些身份，前提是请求已经由可信网关校验，且实际环境阻止外部绕过网关直连；本模块本身不能证明这个前提成立。

使用入口：[UserContextFilter](../../common/common-web/src/main/java/com/calles/platform/common/web/filter/UserContextFilter.java)、[Servlet 自动装配](../../common/common-web/src/main/java/com/calles/platform/common/web/config/CommonWebAutoConfiguration.java)。

## 验证方式与当前结果

应验证响应外壳与真实 HTTP 状态的区别、事件在服务间的兼容解码、合法与非法追踪 ID、缺失身份、异常退出后上下文清理，以及后续请求不继承前一请求的身份。

本次（2026-09-09）实际阅读公共类型、过滤器及自动装配，并核对认证、用户和文件模块的相关使用方式，执行文档链接与格式检查。当前未发现两个公共模块自己的 `src/test` 测试文件。未执行编译、测试或 Servlet/WebFlux 启动验证，不把自动配置类存在当作真实应用已经加载成功。
