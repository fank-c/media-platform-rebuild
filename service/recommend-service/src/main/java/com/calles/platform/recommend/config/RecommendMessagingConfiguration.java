package com.calles.platform.recommend.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 推荐微服务 RabbitMQ 消息拓扑配置类 (RecommendMessagingConfiguration)。
 *
 * <p>本类负责声明推荐模块消费入站所需的队列、交换机与路由绑定关系。
 * 推荐模块作为多项领域事件的核心下游订阅方，涵盖以下四大消费管道：
 * <ol>
 *   <li><b>视频提审通道 (Video Submitted)</b>：订阅 {@code content.video.submitted}，
 *       异步执行高维特征向量提取并回调内容服务以解除发布门禁；</li>
 *   <li><b>视频发布准入通道 (Video Published)</b>：订阅 {@code content.video.published}，
 *       将过审上线的作品以强幂等方式准入推荐候选物料库 (recommend_candidate_video)；</li>
 *   <li><b>视频生命周期清退通道 (Video Lifecycle)</b>：订阅 {@code content.video.offline} 与 {@code content.video.banned}，
 *       实现合规清退与熔断下架，保障推荐流物料安全；</li>
 *   <li><b>互动行为反馈通道 (Interaction Video Action)</b>：订阅 {@code interaction.video-action}，
 *       消费点赞、收藏、分享、合格观看与完播行为，驱动热度召回通道与用户画像演进。</li>
 * </ol>
 * </p>
 */
@Configuration
public class RecommendMessagingConfiguration {

    /**
     * 推荐微服务提审事件专属消费队列名称。
     * <p>下游消费者：{@code VideoSubmittedConsumer}，负责在虚拟线程中异步计算向量特征并回调内容服务。</p>
     */
    public static final String VIDEO_SUBMITTED_QUEUE = "recommend-service.video-submitted.v1";

    /**
     * 平台全局媒体业务领域事件 Topic 交换机名称。
     * <p>所有微服务产生的业务领域事件统一发布到该交换机，按 Topic 规则路由。</p>
     */
    public static final String MEDIA_EVENTS_EXCHANGE = "media.platform.events";

    /**
     * 视频提审发布领域事件路由键。
     * <p>发布方：{@code content-service} 创作者工作台提审流水线。</p>
     */
    public static final String VIDEO_SUBMITTED_ROUTING_KEY = "content.video.submitted";

    /**
     * 推荐微服务正式发布入池专属消费队列名称。
     * <p>下游消费者：{@code VideoPublishedConsumer}，负责将新上线视频初始化至推荐候选池。</p>
     */
    public static final String VIDEO_PUBLISHED_QUEUE = "recommend-service.video-published.v1";

    /**
     * 视频公开发布领域事件路由键。
     * <p>发布方：{@code content-service} 发布门禁仲裁放行后发出。</p>
     */
    public static final String VIDEO_PUBLISHED_ROUTING_KEY = "content.video.published";

    /**
     * 推荐微服务视频生命周期清退专属消费队列名称。
     * <p>下游消费者：{@code VideoLifecycleConsumer}，负责在视频下架或封禁时同步变更候选池状态为 OFFLINE / BANNED。</p>
     */
    public static final String VIDEO_LIFECYCLE_QUEUE = "recommend-service.video-lifecycle.v1";

    /**
     * 视频下架领域事件路由键。
     * <p>发布方：{@code content-service} 创作者主动下架；推荐侧只绑定此唯一当前契约。</p>
     */
    public static final String VIDEO_OFFLINE_ROUTING_KEY = "content.video.offline";

    /**
     * 视频封禁领域事件路由键。
     * <p>发布方：{@code content-service} 管理端治理下线时发出；后续规划补齐 {@code content.video.unbanned} 解封契约 (REC-05)。</p>
     */
    public static final String VIDEO_BANNED_ROUTING_KEY = "content.video.banned";

    /**
     * 推荐微服务统一交互行为消费队列名称 (涵盖视频互动与作者关注两类交互事件)。
     * <p>下游由统一事件消费者 {@code InteractionEventConsumer} 监听并依据 eventType 进行业务分发。</p>
     */
    public static final String INTERACTION_ACTION_QUEUE = "recommend-service.interaction-action.v1";

    /**
     * 互动视频行为领域事件路由键 (由 interaction-service 事务性发件箱 Outbox 当前发出)。
     */
    public static final String INTERACTION_VIDEO_ACTION_ROUTING_KEY = "interaction.video-action";

    /**
     * 互动作者行为版本化领域事件路由键 (由 user-service 事务性发件箱 Outbox 发出)。
     */
    public static final String INTERACTION_AUTHOR_ACTION_V1_ROUTING_KEY = "interaction.author-action.v1";

    /**
     * 声明视频提审持久化消费队列。
     *
     * @return 持久化队列实例
     */
    @Bean
    public Queue recommendVideoSubmittedQueue() {
        return new Queue(VIDEO_SUBMITTED_QUEUE, true);
    }

    /**
     * 声明视频发布上线持久化消费队列。
     *
     * @return 持久化队列实例
     */
    @Bean
    public Queue recommendVideoPublishedQueue() {
        return new Queue(VIDEO_PUBLISHED_QUEUE, true);
    }

    /**
     * 声明视频生命周期清退与治理持久化消费队列。
     *
     * @return 持久化队列实例
     */
    @Bean
    public Queue recommendVideoLifecycleQueue() {
        return new Queue(VIDEO_LIFECYCLE_QUEUE, true);
    }

    /**
     * 声明推荐微服务统一交互行为持久化消费队列。
     *
     * @return 持久化队列实例
     */
    @Bean
    public Queue recommendInteractionActionQueue() {
        return new Queue(INTERACTION_ACTION_QUEUE, true);
    }

    /**
     * 声明平台统一领域事件 Topic 交换机。
     *
     * @return 业务交换机实例 (持久化、不自动删除)
     */
    @Bean
    public TopicExchange recommendMediaEventsExchange() {
        return new TopicExchange(MEDIA_EVENTS_EXCHANGE, true, false);
    }

    /**
     * 绑定视频提审队列至领域事件交换机 (RoutingKey: content.video.submitted)。
     *
     * @param recommendVideoSubmittedQueue 提审消费队列
     * @param recommendMediaEventsExchange 领域事件交换机
     * @return 绑定实例
     */
    @Bean
    public Binding recommendVideoSubmittedBinding(Queue recommendVideoSubmittedQueue, TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendVideoSubmittedQueue)
                .to(recommendMediaEventsExchange)
                .with(VIDEO_SUBMITTED_ROUTING_KEY);
    }

    /**
     * 绑定视频发布队列至领域事件交换机 (RoutingKey: content.video.published)。
     *
     * @param recommendVideoPublishedQueue 发布入池消费队列
     * @param recommendMediaEventsExchange 领域事件交换机
     * @return 绑定实例
     */
    @Bean
    public Binding recommendVideoPublishedBinding(Queue recommendVideoPublishedQueue, TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendVideoPublishedQueue)
                .to(recommendMediaEventsExchange)
                .with(VIDEO_PUBLISHED_ROUTING_KEY);
    }

    /**
     * 绑定视频下架事件至生命周期消费队列 (RoutingKey: content.video.offline)。
     *
     * @param recommendVideoLifecycleQueue 生命周期消费队列
     * @param recommendMediaEventsExchange 领域事件交换机
     * @return 绑定实例
     */
    @Bean
    public Binding recommendVideoOfflineBinding(Queue recommendVideoLifecycleQueue, TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendVideoLifecycleQueue)
                .to(recommendMediaEventsExchange)
                .with(VIDEO_OFFLINE_ROUTING_KEY);
    }

    /**
     * 绑定视频封禁事件至生命周期消费队列 (RoutingKey: content.video.banned)。
     *
     * @param recommendVideoLifecycleQueue 生命周期消费队列
     * @param recommendMediaEventsExchange 领域事件交换机
     * @return 绑定实例
     */
    @Bean
    public Binding recommendVideoBannedBinding(Queue recommendVideoLifecycleQueue, TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendVideoLifecycleQueue)
                .to(recommendMediaEventsExchange)
                .with(VIDEO_BANNED_ROUTING_KEY);
    }

    /**
     * 绑定当前视频交互契约至统一交互消费队列 (RoutingKey: interaction.video-action)。
     *
     * @param recommendInteractionActionQueue 统一交互消费队列
     * @param recommendMediaEventsExchange 领域事件交换机
     * @return 绑定实例
     */
    @Bean
    public Binding recommendInteractionVideoActionBinding(
            Queue recommendInteractionActionQueue,
            TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendInteractionActionQueue)
                .to(recommendMediaEventsExchange)
                .with(INTERACTION_VIDEO_ACTION_ROUTING_KEY);
    }

    /**
     * 绑定作者关注互动版本化事件至统一交互消费队列 (RoutingKey: interaction.author-action.v1)。
     *
     * @param recommendInteractionActionQueue 统一交互消费队列
     * @param recommendMediaEventsExchange 领域事件交换机
     * @return 绑定实例
     */
    @Bean
    public Binding recommendInteractionAuthorActionV1Binding(
            Queue recommendInteractionActionQueue,
            TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendInteractionActionQueue)
                .to(recommendMediaEventsExchange)
                .with(INTERACTION_AUTHOR_ACTION_V1_ROUTING_KEY);
    }
}
