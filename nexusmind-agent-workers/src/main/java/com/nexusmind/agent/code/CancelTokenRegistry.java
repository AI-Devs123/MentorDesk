package com.nexusmind.agent.code;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务取消令牌注册表（CodeAgent 切片1：内存实现）。
 *
 * <p>承载 Week3 的 ABORT 信号在 CodeAgent 侧的落地：Supervisor / Gateway 下发取消时置位，
 * 编排器在文件迭代边界读取。选用<b>内存</b>实现是为了切片1 单体自洽、不引入 Redis 依赖；
 * 后续多实例部署时替换为 Redis 实现（同接口即可，如 {@code RedisCancelTokenRegistry}），
 * 并确保键空间与 Week3 的 {@code nexusmind:task:cancel:{taskId}} 对齐。
 */
@Component
public class CancelTokenRegistry {

    private final ConcurrentHashMap<String, Boolean> tokens = new ConcurrentHashMap<>();

    public void markCancelled(String key) {
        tokens.put(key, Boolean.TRUE);
    }

    public boolean isCancelled(String key) {
        return Boolean.TRUE.equals(tokens.get(key));
    }

    public void clear(String key) {
        tokens.remove(key);
    }
}
