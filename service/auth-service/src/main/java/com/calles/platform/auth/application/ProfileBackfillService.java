package com.calles.platform.auth.application;
import com.calles.platform.auth.infrastructure.messaging.AccountCreatedEventFactory;
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxRecord;

import com.calles.platform.auth.domain.account.AccountRole;
import com.calles.platform.auth.domain.account.AccountStatus;
import com.calles.platform.auth.domain.account.AuthAccount;
import com.calles.platform.auth.infrastructure.persistence.ProfileBackfillCandidate;
import com.calles.platform.auth.infrastructure.persistence.ProfileBackfillMapper;
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新认证库普通账号资料补齐服务，只扫描 auth-service 自有表，不读取旧单体或 user 表。
 */
@Service
public class ProfileBackfillService {

    /** 资料补齐专用 Mapper，只负责候选筛选和进度登记。 */
    private final ProfileBackfillMapper profileBackfillMapper;
    /** 创建与实时注册一致的账号事件。 */
    private final AccountCreatedEventFactory eventFactory;
    /** Outbox 写入仓储。 */
    private final AuthOutboxRepository outboxRepository;

    /** 创建补齐服务。 */
    public ProfileBackfillService(ProfileBackfillMapper profileBackfillMapper, AccountCreatedEventFactory eventFactory,
            AuthOutboxRepository outboxRepository) {
        this.profileBackfillMapper = profileBackfillMapper;
        this.eventFactory = eventFactory;
        this.outboxRepository = outboxRepository;
    }

    /**
     * 查询一批尚未生成 v1 补齐事件的有效普通账号，按创建时间和 ID 稳定推进。
     */
    public List<AuthAccount> findCandidates(int batchSize) {
        // Mapper 只返回事件生成所需字段，应用层再组装为受限的账户快照。
        return profileBackfillMapper.findCandidates(batchSize).stream().map(this::candidate).toList();
    }

    /**
     * 在同一事务生成 Outbox 与补齐进度；唯一键确保并发任务只有一个实例取得补齐资格。
     *
     * @param account 待补齐的认证账号快照
     * @return true 表示已生成事件，false 表示已有进度或输掉并发竞争
     */
    @Transactional
    public boolean enqueue(AuthAccount account) {
        AuthOutboxRecord event = eventFactory.create(account);
        // 先以唯一进度键取得补齐资格；并发实例只有一个能继续写入 Outbox。
        int inserted = profileBackfillMapper.tryClaimProgress(account.getId(), event.eventId());
        if (inserted == 0) {
            return false;
        }
        // 进度和 Outbox 位于同一事务，后续写入失败会一起回滚，避免永久漏发。
        outboxRepository.insert(event);
        return true;
    }

    /** 构造仅包含事件生成所需字段的认证账户快照。 */
    private AuthAccount candidate(ProfileBackfillCandidate candidate) {
        AuthAccount account = new AuthAccount();
        account.setId(candidate.accountId());
        account.setRole(AccountRole.USER);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(candidate.createdAt().toLocalDateTime());
        return account;
    }
}
