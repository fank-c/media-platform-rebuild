package com.calles.platform.content.application.task;

import com.calles.platform.content.domain.model.task.TaskStatus;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.task.VideoTask;
import com.calles.platform.content.domain.repository.VideoTaskRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频异步处理任务超时巡检与重试补偿器 (VideoTaskTimeoutScheduler)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：长耗时异步任务的容错、自愈与死锁预防；</li>
 *   <li><b>协作对象</b>：协同 {@link VideoTaskRepository} 扫描处于 RUNNING 且超时未汇报的僵死任务，联动 {@link PublishGatekeeper} 兜底阻断；</li>
 *   <li><b>自愈策略</b>：未超最大重试阈值时重置为 PENDING 进行重新调度；超限后置为 FAILED 并终止流水线。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTaskTimeoutScheduler {

    /** 任务仓储。 */
    private final VideoTaskRepository videoTaskRepository;

    /** 门禁决策器。 */
    private final PublishGatekeeper publishGatekeeper;

    /** 判定超时的分钟数阈值（默认 15 分钟）。 */
    @Value("${content.task.timeout-minutes:15}")
    private int timeoutMinutes = 15;

    /**
     * 定时调度入口：定时扫描执行中的超时僵死任务并执行补偿自愈。
     *
     * <p><b>执行频率</b>：默认每 60 秒轮询执行一次（可由 {@code content.task.timeout-check-interval-ms} 配置覆盖）；<br>
     * <b>异常隔离</b>：顶级捕获巡检期间的任何运行时异常，避免单次异常导致 Spring 定时调度器中断停止。</p>
     */
    @Scheduled(fixedDelayString = "${content.task.timeout-check-interval-ms:60000}")
    public void scheduleTimeoutInspection() {
        try {
            // 步骤 1：执行超时巡检与自愈补偿
            int recovered = inspectAndRecoverTimeouts();
            // 步骤 2：若存在补偿记录，输出统计日志
            if (recovered > 0) {
                log.info("本轮任务超时巡检完成，成功补偿自愈 [{}] 个异常任务", recovered);
            }
        } catch (Exception e) {
            // 步骤 3：异常隔离与报警记录
            log.error("执行视频任务超时巡检补偿发生异常", e);
        }
    }

    /**
     * 执行单次超时巡检与恢复处理（支持单元测试直接调用）。
     *
     * <p><b>补偿逻辑</b>：
     * 1. 检索所有处于 {@link TaskStatus#RUNNING} 且 {@code started_at <= now - timeoutMinutes} 的僵死任务；<br>
     * 2. 若任务未超出最大重试上限，自增重试计数并重置为 {@link TaskStatus#PENDING} 重新派发；<br>
     * 3. 若任务已达重试上限，直接标记为 {@link TaskStatus#FAILED}；<br>
     * 4. 若超限失败的任务属于阻断性审核任务 (AUDIT)，立即联动门禁驳回视频并终止全流水线。
     * </p>
     *
     * @return 本轮触发重试或置失败的异常任务总数
     */
    @Transactional
    public int inspectAndRecoverTimeouts() {
        // 步骤 1：依据配置的超时分钟数计算回溯时间戳阈值
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(timeoutMinutes);

        // 步骤 2：基于数据库联合索引查询满足超时条件的 RUNNING 任务列表
        List<VideoTask> timeoutTasks = videoTaskRepository.findTimeoutTasks(TaskStatus.RUNNING, threshold);
        if (timeoutTasks.isEmpty()) {
            return 0;
        }

        // 步骤 3：逐一判定各任务是否仍具备重试资格并执行状态跃迁
        int count = 0;
        for (VideoTask task : timeoutTasks) {
            if (task.canRetry()) {
                // 步骤 3.1：仍可重试：递增 retryCount 并重置为 PENDING
                task.markForRetry();
                videoTaskRepository.updateById(task);
                log.warn("任务 [{}] (视频: {}, 类型: {}) 执行超时，触发自动重试第 [{}] 次",
                        task.getId(), task.getVideoId(), task.getTaskType(), task.getRetryCount());
            } else {
                // 步骤 3.2：超出重试限制：置为 FAILED 终态
                task.fail("任务执行超时且已达最大重试上限 (" + task.getMaxRetries() + "次)");
                videoTaskRepository.updateById(task);
                log.error("任务 [{}] (视频: {}, 类型: {}) 执行严重超时且超出重试限制，标记为 FAILED",
                        task.getId(), task.getVideoId(), task.getTaskType());

                // 步骤 3.3：若阻断性审核任务超时失败，联动门禁彻底驳回流水线
                if (task.getTaskType() == TaskType.AUDIT) {
                    publishGatekeeper.rejectAndCancelPipeline(task.getVideoId(), "审核任务超时失败");
                }
            }
            count++;
        }
        return count;
    }
}
