package com.calles.platform.user.application.profile;
import com.calles.platform.common.core.event.EventEnvelope;
import com.calles.platform.user.infrastructure.persistence.mapper.profile.UserConsumedEventMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号创建事件处理器，负责已校验事件的消费幂等和资料初始化单事务执行。
 *
 * <p>JSON 协议校验由 interfaces 层入站适配器完成；本用例不依赖 RabbitMQ、JSON 或认证服务的
 * 事件载荷对象；它只依赖 common-core 信封和本服务本地载荷。</p>
 */
@Service
public class AccountCreatedEventProcessor {

    private static final String CONSUMER = "user-profile-account-created-v1";
    /** 消费幂等 Mapper。 */
    private final UserConsumedEventMapper consumedEventMapper;
    /** 资料应用服务，复用统一初始化规则。 */
    private final UserProfileApplicationService profileService;
    /**
     * 创建事件处理器。
     *
     * @param consumedEventMapper 消费幂等持久化入口
     * @param profileService 用户资料初始化用例
     */
    public AccountCreatedEventProcessor(UserConsumedEventMapper consumedEventMapper,
            UserProfileApplicationService profileService) {
        this.consumedEventMapper = consumedEventMapper;
        this.profileService = profileService;
    }

    /**
     * 在同一数据库事务内登记已校验事件并按账户事实初始化资料。
     *
     * <p>协议错误应在调用本用例前由消息适配器拒绝；数据库和资料初始化失败继续向上抛出，
     * 由监听容器按既有有限重试策略处理。</p>
     *
     * @param event 已完成协议校验的账号创建事件信封
     * @return 资料初始化或重复消费结果
     */
    @Transactional
    public ProcessResult process(EventEnvelope<AccountCreatedPayloadV1> event) {
        Objects.requireNonNull(event, "账号创建事件不能为空");
        AccountCreatedPayloadV1 payload = Objects.requireNonNull(event.payload(), "账号创建事件载荷不能为空");
        // 幂等登记与资料初始化必须同事务，避免只记录事件而没有创建资料。
        int inserted = consumedEventMapper.insertIfAbsent(CONSUMER, event.eventId(), event.eventType(),
                event.version(), payload.accountId(), "PROCESSING");
        if (inserted == 0) {
            return ProcessResult.DUPLICATE;
        }
        String outcome = profileService.initializeIfPhysicallyAbsent(payload.accountId());
        consumedEventMapper.updateOutcome(CONSUMER, event.eventId(), outcome);
        return ProcessResult.fromOutcome(outcome);
    }

    /** 处理结果在事务提交后由消费者转换为低基数指标。 */
    public enum ProcessResult {
        /** 首次消费并创建默认资料。 */
        CREATED,
        /** 首次消费但资料已正常存在。 */
        ALREADY_EXISTS,
        /** 首次消费但资料处于停用状态。 */
        SKIPPED_DISABLED,
        /** 首次消费但存在逻辑删除墓碑。 */
        SKIPPED_DELETED,
        /** eventId 已完成或正在由既有幂等记录代表。 */
        DUPLICATE;

        /** 将资料初始化的固定结果转换为消息处理结果，未知值视为实现错误并回滚事务。 */
        private static ProcessResult fromOutcome(String outcome) {
            try {
                return ProcessResult.valueOf(outcome);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("未知资料初始化结果", exception);
            }
        }
    }
}
