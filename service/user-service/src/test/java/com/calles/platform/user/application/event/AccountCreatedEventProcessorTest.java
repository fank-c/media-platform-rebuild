package com.calles.platform.user.application.event;
import com.calles.platform.common.core.event.EventEnvelope;
import com.calles.platform.user.application.profile.UserProfileApplicationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.user.infrastructure.persistence.UserConsumedEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 账号创建事件契约、幂等和初始化协调测试。 */
@ExtendWith(MockitoExtension.class)
class AccountCreatedEventProcessorTest {

    private static final String EVENT_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String ACCOUNT_ID = "0123456789abcdef0123456789abcdef";

    @Mock private UserConsumedEventMapper consumedEventMapper;
    @Mock private UserProfileApplicationService profileService;
    private AccountCreatedEventProcessor processor;

    /** 创建只依赖应用输入和持久化协作对象的处理器。 */
    @BeforeEach
    void setUp() {
        processor = new AccountCreatedEventProcessor(consumedEventMapper, profileService);
    }

    /** 首次事件登记、初始化并记录最终结果。 */
    @Test
    void validEventInitializesProfileOnce() {
        when(consumedEventMapper.insertIfAbsent("user-profile-account-created-v1", EVENT_ID,
                "auth.account.created", 1, ACCOUNT_ID, "PROCESSING")).thenReturn(1);
        when(profileService.initializeIfPhysicallyAbsent(ACCOUNT_ID)).thenReturn("CREATED");

        assertEquals(AccountCreatedEventProcessor.ProcessResult.CREATED, processor.process(validEvent()));
        verify(consumedEventMapper).updateOutcome("user-profile-account-created-v1", EVENT_ID, "CREATED");
    }

    /** 相同 eventId 再次投递时不得再次初始化资料。 */
    @Test
    void duplicateEventIsIgnored() {
        when(consumedEventMapper.insertIfAbsent("user-profile-account-created-v1", EVENT_ID,
                "auth.account.created", 1, ACCOUNT_ID, "PROCESSING")).thenReturn(0);
        assertEquals(AccountCreatedEventProcessor.ProcessResult.DUPLICATE, processor.process(validEvent()));
        verify(profileService, never()).initializeIfPhysicallyAbsent(ACCOUNT_ID);
    }

    /** 创建已经由入站 Decoder 校验过的最小应用层输入。 */
    private EventEnvelope<AccountCreatedPayloadV1> validEvent() {
        return new EventEnvelope<>(EVENT_ID, "auth.account.created", 1, null, null, ACCOUNT_ID, null,
                new AccountCreatedPayloadV1(ACCOUNT_ID, "user", null));
    }
}
