package com.calles.platform.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.auth.infrastructure.messaging.AccountCreatedEventFactory;
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxRecord;
import com.calles.platform.auth.domain.account.AccountRole;
import com.calles.platform.auth.domain.account.AccountStatus;
import com.calles.platform.auth.domain.account.AuthAccount;
import com.calles.platform.auth.infrastructure.persistence.ProfileBackfillCandidate;
import com.calles.platform.auth.infrastructure.persistence.ProfileBackfillMapper;
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxRepository;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ProfileBackfillService 对补齐候选和唯一进度竞争的单元测试。 */
@ExtendWith(MockitoExtension.class)
class ProfileBackfillServiceTest {

    /** 被测服务查询候选和登记进度所依赖的 Mapper。 */
    @Mock private ProfileBackfillMapper profileBackfillMapper;
    /** 被测服务生成补齐事件所依赖的工厂。 */
    @Mock private AccountCreatedEventFactory eventFactory;
    /** 被测服务写入待发布事件所依赖的 Outbox 仓储。 */
    @Mock private AuthOutboxRepository outboxRepository;
    /** 当前测试创建的补齐应用服务。 */
    private ProfileBackfillService service;

    /** 创建仅协调 Mapper、事件工厂和 Outbox 的补齐服务。 */
    @BeforeEach
    void setUp() {
        service = new ProfileBackfillService(profileBackfillMapper, eventFactory, outboxRepository);
    }

    /** 候选查询只能组装事件所需的有效普通账户快照。 */
    @Test
    void findCandidatesBuildsLimitedActiveUserSnapshots() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 5, 9, 30);
        when(profileBackfillMapper.findCandidates(10)).thenReturn(List.of(
                new ProfileBackfillCandidate("account-1", Timestamp.valueOf(createdAt))));

        List<AuthAccount> candidates = service.findCandidates(10);

        assertEquals(1, candidates.size());
        assertEquals("account-1", candidates.get(0).getId());
        assertEquals(createdAt, candidates.get(0).getCreatedAt());
        assertEquals(AccountRole.USER, candidates.get(0).getRole());
        assertEquals(AccountStatus.ACTIVE, candidates.get(0).getStatus());
    }

    /** 未取得唯一进度资格时不得向 Outbox 写入事件。 */
    @Test
    void enqueueSkipsOutboxWhenProgressAlreadyExists() {
        AuthAccount account = account();
        AuthOutboxRecord event = event();
        when(eventFactory.create(account)).thenReturn(event);
        when(profileBackfillMapper.tryClaimProgress("account-1", "event-1")).thenReturn(0);

        assertFalse(service.enqueue(account));

        verify(outboxRepository, never()).insert(event);
    }

    /** 取得唯一进度资格后，必须在同一应用事务中继续写入 Outbox。 */
    @Test
    void enqueueWritesOutboxAfterProgressClaim() {
        AuthAccount account = account();
        AuthOutboxRecord event = event();
        when(eventFactory.create(account)).thenReturn(event);
        when(profileBackfillMapper.tryClaimProgress("account-1", "event-1")).thenReturn(1);

        assertTrue(service.enqueue(account));

        verify(outboxRepository).insert(event);
    }

    /** 构造已持久化的最小认证账户。 */
    private AuthAccount account() {
        AuthAccount account = new AuthAccount();
        account.setId("account-1");
        account.setCreatedAt(LocalDateTime.of(2026, 9, 5, 9, 30));
        return account;
    }

    /** 构造补齐流程所需的最小 Outbox 事件。 */
    private AuthOutboxRecord event() {
        return new AuthOutboxRecord("event-1", "account-1", "auth.account.created", 1,
                "{}", "trace-1", Timestamp.valueOf("2026-09-05 09:30:00").toInstant());
    }
}
