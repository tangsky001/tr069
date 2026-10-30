package com.tr069.acs.exec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.exec.mapper.DeviceTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 收尾「永远在下发」的僵尸任务。
 *
 * <p>下发是异步线程跑完才回写终态的，线程只活在进程里：服务一重启、或者执行
 * 到一半抛了未捕获的异常，库里的任务就永远停在 {@code RUNNING}。前端按状态
 * 显示进度，于是那台设备一年后还是「正在下发 WiFi 参数」——看着像设备卡住了，
 * 其实早就没人推进它。</p>
 *
 * <p>判定：{@code updated_at} 超过 {@link #STALE_MINUTES} 分钟还停在 RUNNING 的，
 * 一律按中断收尾。真正在跑的任务每写一批参数都会刷新 {@code updated_at}，
 * 不会被误伤；留 10 分钟也是给多实例部署留余地——别的节点可能正在跑。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleTaskRecovery implements ApplicationRunner {

    private final DeviceTaskMapper taskMapper;

    /** 超过这个时长没动静，就认定没有线程在推进它了。 */
    private static final long STALE_MINUTES = 10;

    @Override
    public void run(ApplicationArguments args) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.minusMinutes(STALE_MINUTES);
        List<DeviceTask> stale;
        try {
            stale = taskMapper.selectList(new LambdaQueryWrapper<DeviceTask>()
                    .eq(DeviceTask::getStatus, DeviceTask.STATUS_RUNNING)
                    .and(w -> w.lt(DeviceTask::getUpdatedAt, threshold)
                            .or()
                            .isNull(DeviceTask::getUpdatedAt)
                            .lt(DeviceTask::getCreatedAt, threshold)));
        } catch (Exception e) {
            // 表还没建好或者库不可用：启动流程不该因为这个失败
            log.warn("僵尸下发任务检查失败，跳过: {}", e.getMessage());
            return;
        }
        if (stale.isEmpty()) {
            return;
        }
        for (DeviceTask task : stale) {
            task.setStatus(DeviceTask.STATUS_FAILED);
            task.setErrorMsg("下发中断：超过 " + STALE_MINUTES + " 分钟没有进展（服务重启或设备长时间未响应），"
                    + "请确认设备在线后重新下发");
            task.setFinishedAt(now);
            task.setUpdatedAt(now);
            try {
                taskMapper.updateById(task);
                log.warn("僵尸下发任务已收尾: id={}, sn={}, category={}, 创建于={}",
                        task.getId(), task.getSn(), task.getCategory(), task.getCreatedAt());
            } catch (Exception e) {
                log.warn("僵尸下发任务收尾失败: id={}, err={}", task.getId(), e.getMessage());
            }
        }
    }
}
