package com.calles.platform.audit.config.aliyun;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云内容安全 2.0 (Aliyun Green 2022-03-02) 增强版配置属性。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核服务基础设施配置层，管理与阿里云内容安全服务通信的核心参数；</li>
 *   <li><b>协作对象</b>：供 {@link AliyunGreenClientConfiguration}、阿里云机审引擎及异步 Webhook 回调控制器消费；</li>
 *   <li><b>不应承担的工作</b>：不直接参与机审决策与 API 发起，仅作为结构化配置载体。</li>
 * </ul>
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "audit.aliyun")
public class AliyunGreenProperties {

    /**
     * 是否启用阿里云内容安全 2.0 增强版审查引擎。
     * <p>默认值为 false，关闭时自动回退到本地规则测试桩，保障脱网与测试无缝运行。</p>
     */
    private boolean enabled = false;

    /**
     * 阿里云 AccessKey ID（敏感认证凭据，优先从环境变量读取）。
     */
    private String accessKeyId;

    /**
     * 阿里云 AccessKey Secret（敏感密钥凭据，严禁硬编码或打印至明文日志）。
     */
    private String accessKeySecret;

    /**
     * 阿里云内容安全服务端点 Endpoint。
     * <p>默认取上海地域端点：green-cip.cn-shanghai.aliyuncs.com。</p>
     */
    private String endpoint = "green-cip.cn-shanghai.aliyuncs.com";

    /**
     * 阿里云账号主 UID（用于回调 SHA-256 签名校验防篡改）。
     */
    private String uid;

    /**
     * 异步机审结果回调通知公网 URL（仅云端生产部署时配置，本地内网为空则走主动轮询）。
     */
    private String callbackUrl;

    /**
     * 异步回调通知加签随机种子 Seed（用于防篡改哈希签名计算）。
     */
    private String callbackSeed;

    /**
     * 图像/封面审查服务代码 (ServiceCode)，默认 baselineCheck 通用基线检测。
     */
    private String imageService = "baselineCheck";

    /**
     * 视频流资产审查服务代码 (ServiceCode)，默认 video_detection 视频合规检测。
     */
    private String videoService = "video_detection";

    /**
     * 本地模式下视频主动轮询最大超时时间（秒），默认 15 秒。
     */
    private int pollTimeoutSeconds = 15;

    /**
     * 本地模式下视频主动轮询查询间隔（毫秒），默认 1500 毫秒。
     */
    private int pollIntervalMillis = 1500;

    /**
     * 云端回调模式下视频提交后的短时同步探测时限（秒），默认 3 秒（短视频快速出结果直接放行，超过则交由 Webhook 回调）。
     */
    private int shortProbeTimeoutSeconds = 3;
}
