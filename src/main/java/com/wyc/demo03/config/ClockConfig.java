package com.wyc.demo03.config;

import com.wyc.demo03.domain.SlotSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * 时间与时段规则的装配。
 *
 * <p><b>为什么要有 {@link Clock} Bean</b>：签到截止、结束提醒、超时释放、到期结束这些
 * 判定全部依赖"现在几点"。如果代码里直接写 {@code LocalDateTime.now()}，就只能靠
 * 改系统时间或等到真实时刻才能测试边界（比如"开始后 4:59 能签、5:00 不能签"）。
 * 注入 {@link Clock} 后，测试可以把它固定到任意时刻，把 24 小时的收敛过程压缩成毫秒。
 */
@Configuration
@EnableConfigurationProperties({SlotProperties.class, DoorProperties.class,
        MqttProperties.class, SimulatorProperties.class})
public class ClockConfig {

    /**
     * 系统时钟，默认时区。测试用 {@code Clock.fixed(...)} 覆盖。
     *
     * <p><b>{@code app.clock.offset-minutes} 是干什么的</b>：房间可预约时段是
     * 08:00–22:00，晚上做演示时"今天已经收盘"，签到窗口根本不存在。
     * 这个偏移把"应用里的现在"整体挪一段，不用改系统时间（改系统时间会
     * 影响 MySQL 时间戳、日志、文件时间，还可能被 Windows 自动同步改回去）。
     *
     * <p>默认 0，即真实时间；只有明确传参时才偏移，所以不影响正常使用。
     * 演示完去掉参数重启即可恢复。
     */
    @Bean
    public Clock clock(@Value("${app.clock.offset-minutes:0}") long offsetMinutes) {
        Clock base = Clock.systemDefaultZone();
        if (offsetMinutes == 0) {
            return base;
        }
        return Clock.offset(base, Duration.ofMinutes(offsetMinutes));
    }

    /** 时间单元规则，由 {@code app.slot.*} 构造，全局唯一。 */
    @Bean
    public SlotSchema slotSchema(SlotProperties properties) {
        return properties.toSchema();
    }
}
