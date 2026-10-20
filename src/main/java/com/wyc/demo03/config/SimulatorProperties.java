package com.wyc.demo03.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 设备模拟器配置（{@code app.simulator.*}）。
 *
 * <p>硬件未到货期间用它顶替 STM32：订阅同样的主题、校验同样的 HMAC、回同样的 ack。
 * 真机到货后模拟器**不删**，转为故障与恢复的回归工具（计划 6.4）。
 */
@Data
@ConfigurationProperties(prefix = "app.simulator")
public class SimulatorProperties {

    /** 是否启动模拟器。 */
    private boolean enabled = false;

    /** 模拟哪台设备（对应 t_device.device_code）。 */
    private String deviceCode = "ROOM-A-01";

    /** 该设备的 HMAC 密钥，必须与库里一致，否则所有指令都会验签失败。 */
    private String secret = "demo-secret-room-a-please-change";

    /**
     * 故障注入：随机丢弃 ack 的概率（0 = 不丢）。
     *
     * <p>用来验证"签到成功但开锁失败不回滚"这条硬要求。
     */
    private double failRate = 0.0;

    /** 屏幕输出目录（相对工作目录），便于截图留证。 */
    private String screenDir = "target/simulator";
}
