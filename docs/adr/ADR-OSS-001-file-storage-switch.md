# ADR-OSS-001: 文件服务对象存储引入阿里云 OSS 并支持策略模式扩展

## 状态
已采纳并实施 (Accepted)

## 背景
在视频创作用例中，创作者发布视频后触发审核流水线。平台使用阿里云内容安全（Green）机审引擎对视频与封面进行合规审查。
在原有实现中，对象存储采用 MinIO，`file-service` 生成的下载链接指向自建 MinIO 服务器。由于自建服务器出口带宽受到 3 Mbps 物理限制，阿里云审核服务在公网拉取音视频源文件时发生严重的下载超时与连接阻塞，导致机审任务频繁失败或被迫超时降级。

为彻底解决出口网络带宽瓶颈，平台决定将新文件及默认存储策略切换为阿里云 OSS，同时在架构上沉淀策略模式（Strategy Pattern），保留 MinIO 策略作为可插拔/备选实现（如本地离线开发或私有化部署场景）。

## 决策
1. **架构模式：策略模式（Strategy Pattern / 方案 A）**：
   - 采用 `StorageFactory` 作为统一的策略路由中枢，按 `StorageType`（`ALIYUN_OSS` / `MINIO`）动态路由至具体的 `ObjectStorageClient` 适配器；
   - 默认存储策略设置为阿里云 OSS（`file.storage.type: ${FILE_STORAGE_TYPE:ALIYUN_OSS}`），新文件默认使用 ALIYUN_OSS 进行上传与持久化；
   - 完整保留 `MinioObjectStorageClient` 与 `MinioStorageConfiguration` 实现，并使用 `@ConditionalOnProperty(prefix = "file.storage.minio", name = "endpoint")` 实现按需条件注入；
   - 统一引入 `com.aliyun.oss:aliyun-sdk-oss:3.17.4`，同时在依赖中保留 `io.minio:minio`。
2. **端口隔离与防腐设计**：
   - 保留领域层与应用层的 `ObjectStorageClient` 抽象端口，业务用例（普通上传、V1 直传、确认归档、清理与删除）不直接依赖任何第三方云 SDK；
   - 新增 `AliyunOssObjectStorageClient`，将 OSS SDK 的操作（`putObject`, `getObjectMetadata`, `getObject`, `deleteObject`, `generatePresignedUrl`, `copyObject`）封装在基础设施层，并将 `OSSException` 精准映射为 `ObjectStorageException` 受控分类（`NOT_FOUND`, `CONTENT_MISMATCH`, `UNAVAILABLE`, `UNKNOWN_RESULT`）。
3. **端点物理隔离**：
   - OSS 配置中明确区分内部管理端点（`endpoint`，可配置内网 VPC 地址以加速通信）与外部预签名端点（`presignEndpoint`，必须为公网可解析 HTTPS 域名）；
   - 杜绝生成签名后通过字符串粗暴替换 Host 破坏签名摘要的脆弱做法。
4. **按策略差异化配置校验**：
   - `FileStorageProperties.validate()` 根据当前激活的 `file.storage.type` 进行差异化校验；激活 ALIYUN_OSS 时仅校验 OSS 相关必填参数与前缀互斥，解除对 MinIO 凭据的硬性绑定，避免在纯 OSS 部署下因未配 MinIO 报错。
5. **安全与权限边界**：
   - 存储 Bucket 严格保持私有，禁止任何匿名公开读取；
   - 阿里云审核服务与客户端均通过短期有效（TTL）的 HTTPS 预签名 GET 链接读取资源；
   - `audit-service` 绝不接触或持有 OSS / MinIO AccessKey / SecretKey。
6. **数据库兼容性**：
   - `file_asset` 表的 CHECK 约束 `ck_file_storage` 保持为 `storage_type IN ('MINIO', 'ALIYUN_OSS')`，确保历史数据与双策略兼容。

## 后果与影响
- **收益**：
  - 阿里云内容安全服务可直接通过阿里云骨干网络以极高速率拉取待审视频与图片，彻底消除 3 Mbps 服务器出口带宽瓶颈；
  - 架构具备多存储扩展能力，未来可平滑迁移历史 MinIO 对象或接入其他对象存储；
  - 本地离线开发依然可通过切换 `FILE_STORAGE_TYPE=MINIO` 零成本启动调试。
- **代价与权衡**：
  - 代码库中需维护两套存储适配器与配置类，但通过条件注解（Conditional Beans）实现解耦与按需加载，无额外运行时内存浪费。
