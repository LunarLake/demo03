package com.wyc.demo03.config;

import com.wyc.demo03.domain.SlotSchema;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

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

    /** 系统时钟，默认时区。测试用 {@code Clock.fixed(...)} 覆盖。 */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /** 时间单元规则，由 {@code app.slot.*} 构造，全局唯一。 */
    @Bean
    public SlotSchema slotSchema(SlotProperties properties) {
        return properties.toSchema();
    }
}
