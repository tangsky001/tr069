package com.tr069.acs.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.queue.config.QueueProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集指令的批次清理。
 */
class RpcTaskQueueTest {

    /**
     * 新一轮采集开始时，上一轮没发完的采集指令要丢掉，但工单指令必须留着——
     * 清理是为了别让旧采集指令被重复下发、也别把设备队列占满，
     * 不能顺手把业务下发一起清了。
     */
    @Test
    void 清理旧批次采集指令但保留工单指令() {
        String sn = "37300148C4AA410D6";
        ObjectMapper mapper = new ObjectMapper();

        RpcTaskQueue.RpcTask staleCollect = task(308L, sn, "GetParameterNames", "20261020021251-708acaae");
        RpcTaskQueue.RpcTask currentCollect = task(413L, sn, "GetParameterNames", "20261020022552-708acaae");
        RpcTaskQueue.RpcTask worklist = task(501L, sn, "SetParameterValues", null);

        @SuppressWarnings("unchecked")
        ListOperations<String, String> ops = Mockito.mock(ListOperations.class);
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(redis.opsForList()).thenReturn(ops);
        Mockito.when(ops.range(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(),
                        ArgumentMatchers.anyLong()))
                .thenReturn(List.of(json(mapper, staleCollect), json(mapper, currentCollect),
                        json(mapper, worklist)));
        Mockito.when(ops.remove(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(),
                        ArgumentMatchers.any()))
                .thenReturn(1L);

        QueueProperties.Rpc rpcProps = Mockito.mock(QueueProperties.Rpc.class);
        Mockito.when(rpcProps.getQueueTtlSeconds()).thenReturn(3600L);
        QueueProperties properties = Mockito.mock(QueueProperties.class);
        Mockito.when(properties.getRpc()).thenReturn(rpcProps);

        RpcTaskQueue queue = new RpcTaskQueue(redis, mapper, properties);
        int dropped = queue.dropStale(sn, "20261020022552-708acaae");

        assertThat(dropped).as("只丢上一轮那一批").isEqualTo(1);

        // 按值单条删除，不能 DEL 整个 key 再重写保留的：清理期间若有新指令入队、
        // 或另一跳正在取指令，重写会连带把它们抹掉，指令就凭空丢了
        Mockito.verify(ops).remove(ArgumentMatchers.anyString(), ArgumentMatchers.eq(0L),
                ArgumentMatchers.eq(json(mapper, staleCollect)));
        Mockito.verify(redis, Mockito.never()).delete(ArgumentMatchers.anyString());
        Mockito.verify(ops, Mockito.never()).rightPushAll(ArgumentMatchers.anyString(),
                ArgumentMatchers.<String>any());
    }

    /**
     * 队列里全是本轮指令时不该动队列，避免每次采集都白白重写一遍 Redis key。
     */
    @Test
    void 没有旧批次指令时不重写队列() {
        String sn = "37300148C4AA410D6";
        ObjectMapper mapper = new ObjectMapper();
        RpcTaskQueue.RpcTask current = task(413L, sn, "GetParameterNames", "20261020022552-708acaae");

        @SuppressWarnings("unchecked")
        ListOperations<String, String> ops = Mockito.mock(ListOperations.class);
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(redis.opsForList()).thenReturn(ops);
        Mockito.when(ops.range(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(),
                        ArgumentMatchers.anyLong()))
                .thenReturn(List.of(json(mapper, current)));

        QueueProperties.Rpc rpcProps = Mockito.mock(QueueProperties.Rpc.class);
        Mockito.when(rpcProps.getQueueTtlSeconds()).thenReturn(3600L);
        QueueProperties properties = Mockito.mock(QueueProperties.class);
        Mockito.when(properties.getRpc()).thenReturn(rpcProps);

        RpcTaskQueue queue = new RpcTaskQueue(redis, mapper, properties);
        assertThat(queue.dropStale(sn, "20261020022552-708acaae")).isZero();
        Mockito.verify(redis, Mockito.never()).delete(ArgumentMatchers.anyString());
    }

    private static RpcTaskQueue.RpcTask task(long id, String sn, String rpcName, String batch) {
        RpcTaskQueue.RpcTask t = new RpcTaskQueue.RpcTask();
        t.setTaskId(id);
        t.setSn(sn);
        t.setRpcName(rpcName);
        t.setArgsJson("{}");
        t.setBatch(batch);
        return t;
    }

    private static String json(ObjectMapper mapper, RpcTaskQueue.RpcTask task) {
        try {
            return mapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
