package com.calles.platform.recommend.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.recommend.domain.model.event.EventConsumedRecord;
import com.calles.platform.recommend.infrastructure.persistence.entity.EventConsumedRecordPO;
import com.calles.platform.recommend.infrastructure.persistence.mapper.EventConsumedRecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * EventConsumedRecordRepositoryImpl 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventConsumedRecordRepositoryImpl 仓储实现测试")
class EventConsumedRecordRepositoryImplTest {

    @Mock
    private EventConsumedRecordMapper mapper;

    @InjectMocks
    private EventConsumedRecordRepositoryImpl repository;

    @Test
    @DisplayName("首次保存消费记录：成功插入返回 true")
    void shouldReturnTrueWhenInsertSucceeds() {
        EventConsumedRecord record = EventConsumedRecord.createForVideo(
                "evt_001", "interaction.video-action", "user_100", "vid_200", "LIKE", "ACTIVE"
        );
        when(mapper.insert(any(EventConsumedRecordPO.class))).thenReturn(1);

        boolean result = repository.saveIfAbsent(record);

        assertThat(result).isTrue();
        verify(mapper).insert(any(EventConsumedRecordPO.class));
    }

    @Test
    @DisplayName("主键唯一键冲突：捕获 DuplicateKeyException 并返回 false")
    void shouldReturnFalseWhenDuplicateKeyExceptionOccurs() {
        EventConsumedRecord record = EventConsumedRecord.createForVideo(
                "evt_001", "interaction.video-action", "user_100", "vid_200", "LIKE", "ACTIVE"
        );
        when(mapper.insert(any(EventConsumedRecordPO.class))).thenThrow(new DuplicateKeyException("Duplicate entry"));

        boolean result = repository.saveIfAbsent(record);

        assertThat(result).isFalse();
        verify(mapper).insert(any(EventConsumedRecordPO.class));
    }

    @Test
    @DisplayName("入参为空时直接返回 false 且不查数据库")
    void shouldReturnFalseWhenRecordIsNull() {
        boolean result = repository.saveIfAbsent(null);

        assertThat(result).isFalse();
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("按事件ID检索记录：查询成功并正确映射为领域模型 (含作者互动字段)")
    void shouldFindRecordByEventId() {
        EventConsumedRecordPO po = new EventConsumedRecordPO();
        po.setEventId("evt_001");
        po.setEventType("interaction.author-action");
        po.setUserId("user_100");
        po.setVid(null);
        po.setAuthorId("author_200");
        po.setAction("FOLLOW");
        po.setState("ACTIVE");
        po.setConsumedAt(LocalDateTime.now());

        when(mapper.selectById("evt_001")).thenReturn(po);

        Optional<EventConsumedRecord> result = repository.findByEventId("evt_001");

        assertThat(result).isPresent();
        assertThat(result.get().getEventId()).isEqualTo("evt_001");
        assertThat(result.get().getEventType()).isEqualTo("interaction.author-action");
        assertThat(result.get().getUserId()).isEqualTo("user_100");
        assertThat(result.get().getVid()).isNull();
        assertThat(result.get().getAuthorId()).isEqualTo("author_200");
        assertThat(result.get().getAction()).isEqualTo("FOLLOW");
        assertThat(result.get().getState()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("首次保存作者消费记录：正确映射 authorId 和 state 并成功插入")
    void shouldSaveAuthorActionRecord() {
        EventConsumedRecord record = EventConsumedRecord.createForAuthor(
                "evt_author_001", "interaction.author-action", "user_100", "author_200", "FOLLOW", "ACTIVE"
        );
        when(mapper.insert(any(EventConsumedRecordPO.class))).thenReturn(1);

        boolean result = repository.saveIfAbsent(record);

        assertThat(result).isTrue();
        ArgumentCaptor<EventConsumedRecordPO> poCaptor = ArgumentCaptor.forClass(EventConsumedRecordPO.class);
        verify(mapper).insert(poCaptor.capture());
        EventConsumedRecordPO po = poCaptor.getValue();
        assertThat(po.getEventId()).isEqualTo("evt_author_001");
        assertThat(po.getEventType()).isEqualTo("interaction.author-action");
        assertThat(po.getAuthorId()).isEqualTo("author_200");
        assertThat(po.getVid()).isNull();
        assertThat(po.getAction()).isEqualTo("FOLLOW");
        assertThat(po.getState()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("按时间批量清理过期记录：调用 delete 对应行数")
    @SuppressWarnings("unchecked")
    void shouldDeleteBeforeConsumedAt() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        when(mapper.delete(any(LambdaQueryWrapper.class))).thenReturn(15);

        int deleted = repository.deleteBeforeConsumedAt(cutoff);

        assertThat(deleted).isEqualTo(15);
        verify(mapper).delete(any(LambdaQueryWrapper.class));
    }
}
