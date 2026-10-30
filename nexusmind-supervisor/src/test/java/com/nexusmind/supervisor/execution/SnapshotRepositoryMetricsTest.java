package com.nexusmind.supervisor.execution;

import com.nexusmind.security.destructive.DestructiveOperationType;
import com.nexusmind.security.destructive.ExecutionSnapshot;
import com.nexusmind.security.destructive.RiskLevel;
import com.nexusmind.supervisor.metrics.DestructiveOperationMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切片 1：验证 SnapshotRepository 在保存快照时确实触发了 DestructiveOperationMetrics 埋点。
 * 用 Mock RedisTemplate + ReflectionTestUtils 注入依赖，不触真实 Redis。
 */
class SnapshotRepositoryMetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void saveSnapshotTriggersPendingMetric() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        DestructiveOperationMetrics metrics = mock(DestructiveOperationMetrics.class);

        SnapshotRepository repo = new SnapshotRepository();
        ReflectionTestUtils.setField(repo, "redisTemplate", redis);
        // 裸 ObjectMapper 无法序列化 ExecutionSnapshot 的 Instant 字段会抛异常，导致 recordPending 无法触达；
        // mock 掉序列化，让测试聚焦在指标挂接本身（生产环境用的是注册了 JavaTimeModule 的 Spring ObjectMapper）。
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        doReturn("{}").when(objectMapper).writeValueAsString(any());
        ReflectionTestUtils.setField(repo, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(repo, "destructiveMetrics", metrics);

        ExecutionSnapshot snap = new ExecutionSnapshot();
        snap.setOperationType(DestructiveOperationType.FILE_DELETE);
        snap.setRiskLevel(RiskLevel.HIGH);

        repo.saveSnapshot("exec-1", snap);

        verify(metrics).recordPending("FILE_DELETE", "HIGH");
    }
}
