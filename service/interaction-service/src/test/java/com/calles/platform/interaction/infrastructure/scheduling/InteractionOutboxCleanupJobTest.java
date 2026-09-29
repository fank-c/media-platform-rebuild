package com.calles.platform.interaction.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.calles.platform.interaction.application.outbox.InteractionOutboxCleanupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证清理异常止于独立调度边界。 */
@ExtendWith(MockitoExtension.class)
class InteractionOutboxCleanupJobTest {

    @Mock
    private InteractionOutboxCleanupService service;

    @Test
    void shouldContainFailureAndAllowNextRun() {
        doThrow(new IllegalStateException("db unavailable")).doReturn(0).when(service).cleanup();
        InteractionOutboxCleanupJob job = new InteractionOutboxCleanupJob(service);

        assertThatCode(job::cleanupPublished).doesNotThrowAnyException();
        assertThatCode(job::cleanupPublished).doesNotThrowAnyException();
        verify(service, org.mockito.Mockito.times(2)).cleanup();
    }
}
