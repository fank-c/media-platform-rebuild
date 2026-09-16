package com.calles.platform.audit.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.repository.AuditDetailRepository;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import com.calles.platform.audit.exception.AuditException;
import com.calles.platform.audit.infrastructure.persistence.entity.AuditTaskPO;
import com.calles.platform.audit.infrastructure.persistence.mapper.AuditTaskMapper;
import com.calles.platform.audit.interfaces.http.dto.AuditRequests;
import com.calles.platform.audit.interfaces.http.dto.AuditResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 平台管理端人工审核与工单管理应用服务 (AuditManualReviewApplicationService)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：承接管理员/运营人员对机审疑似可疑 (SUSPICIOUS) 挂起工单的人工复审用例编排；</li>
 *   <li><b>协作对象</b>：
 *     <ul>
 *       <li>{@link AuditTaskRepository}：负责审核聚合根实体的状态读写；</li>
 *       <li>{@link AuditDetailRepository}：负责多维度机审判定证据与敏感词明细读取；</li>
 *       <li>{@link AuditTaskMapper}：负责管理端多条件复合分页查询；</li>
 *       <li>{@link AuditCallbackService}：人工终审完结后驱动向内容微服务发起结果对齐回调。</li>
 *     </ul>
 *   </li>
 *   <li><b>不应承担的工作</b>：不直接参与前端 HTTP 协议序列化，不直接操作底层文件或视频元数据。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditManualReviewApplicationService {

    /** 审核任务领域仓储。 */
    private final AuditTaskRepository auditTaskRepository;

    /** 审核判定明细仓储。 */
    private final AuditDetailRepository auditDetailRepository;

    /** 审核主任务持久化访问 Mapper。 */
    private final AuditTaskMapper auditTaskMapper;

    /** 下游内容微服务结果回调服务。 */
    private final AuditCallbackService callbackService;

    /**
     * 查询指定审核任务全景视图（含主任务快照与多维度判定证据明细）。
     *
     * @param taskId 审核任务全局物理主键 ID (UUID 32位)
     * @return 包含任务全量字段及证据维度的全景响应 DTO
     * @throws AuditException 当指定任务不存在时抛出 404 NOT_FOUND
     */
    public AuditResponses.TaskDetail getTaskDetail(String taskId) {
        // 步骤 1：检索任务聚合根并执行存在性判空保护
        AuditTask task = auditTaskRepository.findById(taskId)
                .orElseThrow(() -> new AuditException(HttpStatus.NOT_FOUND, "未找到指定的审核任务: " + taskId));

        // 步骤 2：加载关联的多维度判定证据明细列表
        List<AuditDetail> details = auditDetailRepository.findByTaskId(taskId);

        // 步骤 3：组装为防腐传输 DTO
        return AuditResponses.TaskDetail.of(task, details);
    }

    /**
     * 平台管理端复合条件分页检索审核工单列表。
     *
     * @param query 多条件过滤请求体 (支持按阶段、结论、风险级别、业务主键过滤)
     * @return 分页工单响应
     */
    public AuditResponses.TaskPage listTasks(AuditRequests.TaskQuery query) {
        // 步骤 1：规范化页码与分页大小保护（默认第 1 页，每页 20 条，上限 100 条）
        int pageNum = query != null ? query.getPage() : 1;
        int pageSize = query != null ? query.getSize() : 20;

        // 步骤 2：动态装配 MyBatis-Plus 条件构造器
        LambdaQueryWrapper<AuditTaskPO> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            if (query.stage() != null && !query.stage().isBlank()) {
                wrapper.eq(AuditTaskPO::getStage, query.stage().trim().toUpperCase());
            }
            if (query.result() != null && !query.result().isBlank()) {
                wrapper.eq(AuditTaskPO::getResult, query.result().trim().toUpperCase());
            }
            if (query.reviewLevel() != null && !query.reviewLevel().isBlank()) {
                wrapper.eq(AuditTaskPO::getReviewLevel, query.reviewLevel().trim().toUpperCase());
            }
            if (query.bizId() != null && !query.bizId().isBlank()) {
                wrapper.eq(AuditTaskPO::getBizId, query.bizId().trim());
            }
        }
        // 默认按创建时间倒序呈现最新提审记录
        wrapper.orderByDesc(AuditTaskPO::getCreatedAt);

        // 步骤 3：执行数据库物理分页检索
        Page<AuditTaskPO> pageParam = new Page<>(pageNum, pageSize);
        Page<AuditTaskPO> resultPage = auditTaskMapper.selectPage(pageParam, wrapper);

        // 步骤 4：PO 实体流转换为防腐列表项 DTO
        List<AuditResponses.TaskItem> records = resultPage.getRecords().stream()
                .map(po -> AuditResponses.TaskItem.fromDomain(po.toDomain()))
                .toList();

        return new AuditResponses.TaskPage(records, resultPage.getTotal(), pageNum, pageSize);
    }

    /**
     * 执行管理员人工复审裁决（通过或驳回），并在终审后联动回调内容微服务。
     *
     * <p><b>事务与一致性说明</b>：
     * 本地事务管理聚合根状态变更与落库，事务提交后执行远程 Feign 回调通知内容服务推进发布或驳回门禁。
     * </p>
     *
     * @param taskId 待人工审核的任务 ID
     * @param request 包含操作类型与原因的请求体
     * @param operatorId 终审操作管理员标识
     * @return 裁决更新后的审核任务全景详情 DTO
     * @throws AuditException 当任务不存在、状态非 MANUAL_PENDING 或驳回无原因时抛出
     */
    @Transactional
    public AuditResponses.TaskDetail reviewTask(String taskId, AuditRequests.ManualReview request, String operatorId) {
        // 步骤 1：定位目标审核任务
        AuditTask task = auditTaskRepository.findById(taskId)
                .orElseThrow(() -> new AuditException(HttpStatus.NOT_FOUND, "未找到指定的审核任务: " + taskId));

        // 步骤 2：状态机约束校验：仅 MANUAL_PENDING 状态的工单才允许人工审批
        if (task.getStage() != AuditStage.MANUAL_PENDING) {
            throw new AuditException(HttpStatus.BAD_REQUEST,
                    "仅处于人工复审中(MANUAL_PENDING)的工单允许裁决，当前状态为: " + task.getStage());
        }

        // 步骤 3：依据审核指令驱动聚合根内部状态机跃迁
        String action = request.action().trim().toUpperCase();
        if ("APPROVE".equals(action)) {
            task.approveByManual(operatorId);
            log.info("管理员 [{}] 人工复审通过了审核任务 [{}], videoId=[{}]", operatorId, task.getTaskNo(), task.getBizId());
        } else if ("REJECT".equals(action)) {
            String reason = request.reason();
            if (reason == null || reason.isBlank()) {
                throw new AuditException(HttpStatus.BAD_REQUEST, "人工驳回时必须填写驳回原因说明");
            }
            task.rejectByManual(operatorId, reason);
            log.info("管理员 [{}] 人工复审驳回了审核任务 [{}], videoId=[{}], 原因=[{}]",
                    operatorId, task.getTaskNo(), task.getBizId(), reason);
        } else {
            throw new AuditException(HttpStatus.BAD_REQUEST, "不支持的审核操作类型: " + action);
        }

        // 步骤 4：持久化更新聚合根状态
        auditTaskRepository.updateById(task);

        // 步骤 5：人工复审终结后，驱动下游内容微服务回调通知，对齐内容发布生命周期
        callbackService.callbackContentService(task);

        // 步骤 6：查询最新证据明细并组装出参 DTO 返回
        List<AuditDetail> details = auditDetailRepository.findByTaskId(taskId);
        return AuditResponses.TaskDetail.of(task, details);
    }
}
