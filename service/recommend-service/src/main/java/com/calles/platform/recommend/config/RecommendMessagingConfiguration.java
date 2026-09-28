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
 *   <li><b>视频生命周期清退通道 (Video Lifecycle)</b>：订阅 {@code content.video.offlined} 与 {@code content.video.banned}，
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
     * <p>契约注意事项 (REC-01)：当前内容服务发送的路由键为 {@code content.video.offline}（未带 d 后缀），
     * 推荐侧历史绑定为 {@code content.video.offlined}，在两端契约完全对齐前需留意绑定兼容性。</p>
     */
    public static final String VIDEO_OFFLINED_ROUTING_KEY = "content.video.offlined";

    /**
     * 视频封禁领域事件路由键。
     * <p>发布方：{@code content-service} 管理端治理下线时发出；后续规划补齐 {@code content.video.unbanned} 解封契约 (REC-05)。</p>
     */
    public static final String VIDEO_BANNED_ROUTING_KEY = "content.video.banned";

    /**
     * 推荐微服务互动视频行为专属消费队列名称。
     * <p>下游设计目标：用于异步监听互动行为，驱动热度召回通道与更新用户即时画像快照。</p>
     */
    public static final String INTERACTION_VIDEO_ACTION_QUEUE = "recommend-service.interaction-video-action.v1";

    /**
     * 互动视频行为领域事件路由键。
     * <p>发布方：{@code interaction-service} 事务性发件箱 Outbox。
     * 载荷涵盖点赞 (LIKE)、收藏 (STAR)、分享 (SHARE)、合格观看 (WATCH_VIEW_QUALIFIED) 与完播 (WATCH_COMPLETED)。
     * <br><b>协同指明</b>：推荐侧已落地幂等消费者 {@code InteractionVideoActionConsumer}
     * （基于消费防重表 {@code recommend_event_consumed} 记录幂等消费，落反馈流水并加权推进用户画像模型）；
     * 互动服务侧发件箱投递开关 {@code interaction.outbox.dispatch-enabled} 可在多服务联调与切流演练时按需开启。</p>
     */
    public static final String INTERACTION_VIDEO_ACTION_ROUTING_KEY = "interaction.video-action";

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
     * 声明互动视频行为持久化消费队列。
     *
     * @return 持久化队列实例
     */
    @Bean
    public Queue recommendInteractionVideoActionQueue() {
        return new Queue(INTERACTION_VIDEO_ACTION_QUEUE, true);
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
     * 绑定视频下架事件至生命周期消费队列 (RoutingKey: content.video.offlined)。
     *
     * @param recommendVideoLifecycleQueue 生命周期消费队列
     * @param recommendMediaEventsExchange 领域事件交换机
     * @return 绑定实例
     */
    @Bean
    public Binding recommendVideoOfflinedBinding(Queue recommendVideoLifecycleQueue, TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendVideoLifecycleQueue)
                .to(recommendMediaEventsExchange)
                .with(VIDEO_OFFLINED_ROUTING_KEY);
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
     * 绑定互动行为事件至互动行为消费队列 (RoutingKey: interaction.video-action)。
     *
     * @param recommendInteractionVideoActionQueue 互动行为消费队列
     * @param recommendMediaEventsExchange 领域事件交换机
     * @return 绑定实例
     */
    @Bean
    public Binding recommendInteractionVideoActionBinding(
            Queue recommendInteractionVideoActionQueue,
            TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendInteractionVideoActionQueue)
                .to(recommendMediaEventsExchange)
                .with(INTERACTION_VIDEO_ACTION_ROUTING_KEY);
    }
}
