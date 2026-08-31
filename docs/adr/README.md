# 架构决策记录

ADR（Architecture Decision Record）用于记录已经作出的、会影响多个服务或后续迁移路径的
技术决策。它不是设计文档的重复，而是保留“为什么这样选”和“何时需要重新评估”。

下列变化必须新增 ADR：新建或合并服务、修改数据所有权、引入共享模块或基础设施、引入新的
同步服务调用、定义跨服务事件、变更 JWT 签名/声明策略、采用双写/切流方案，或升级核心框架。

命名格式为 `NNNN-简短-kebab-case.md`，例如 `0001-jwt-signing-strategy.md`。编号递增，
已接受的 ADR 不直接改写结论；决策被替代时新增 ADR 并链接旧记录。

请从 [模板](0000-template.md) 创建新记录。

当前已接受的基线决策：

- [0001 渐进式微服务架构基线](0001-incremental-microservice-baseline.md)
