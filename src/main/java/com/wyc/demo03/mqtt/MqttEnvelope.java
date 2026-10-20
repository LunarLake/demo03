package com.wyc.demo03.mqtt;

import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * MQTT 消息信封（计划 6.2）。
 *
 * <h3>为什么 payload 是扁平字符串而不是 JSON 对象</h3>
 * 计划里的示例把 {@code payload} 写成对象 {@code {"pulseMs":3000,...}}，但 HMAC 的
 * canonical 串必须**两端逐字节一致**。JSON 对象的键顺序、空格、数字格式在不同实现里都不一样，
 * STM32 侧写一个"和 Java 完全一致"的序列化器既费力又容易出错。
 *
 * <p>因此这里把 payload 定为扁平字符串（{@code pulseMs=3000;reason=FIRST_CHECKIN}）：
 * 直接参与 canonical 计算，设备端解析只需按 {@code ;} 和 {@code =} 切分。
 * 这是对计划 6.2 的**契约澄清**，不是随意改动 —— 原文档没定义 payload 的规范化方式。
 *
 * <h3>设备端校验顺序（必须与文档 3.2.3 对应）</h3>
 * <ol>
 *   <li>deviceCode 与自身一致</li>
 *   <li>HMAC 校验通过</li>
 *   <li>expiresAt 未过期（旧指令不得触发开锁）</li>
 *   <li>msgId 不在最近去重表内</li>
 *   <li>reservationId 与当前显示的预约一致</li>
 * </ol>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MqttEnvelope {

    /** 协议版本。字段结构变化时递增，设备据此拒绝不认识的版本。 */
    public static final int VERSION = 1;

    private Integer v;
    private String msgId;
    private String type;
    private String deviceCode;
    private Long reservationId;
    private String issuedAt;
    private String expiresAt;
    private String payload;
    private String hmac;

    /**
     * canonical 串：{@code v|msgId|type|deviceCode|reservationId|issuedAt|expiresAt|payload}
     *
     * <p>约定：空值一律参与计算为空串（例如 DISPLAY 指令没有 reservationId），
     * 这样"字段缺失"与"字段为空"在签名上等价，不会因序列化差异被误判为篡改。
     */
    public String canonical() {
        return String.join("|",
                v == null ? "" : String.valueOf(v),
                msgId == null ? "" : msgId,
                type == null ? "" : type,
                deviceCode == null ? "" : deviceCode,
                reservationId == null ? "" : String.valueOf(reservationId),
                issuedAt == null ? "" : issuedAt,
                expiresAt == null ? "" : expiresAt,
                payload == null ? "" : payload);
    }

    /** 用设备密钥计算 HMAC-SHA256（十六进制小写）。 */
    public String sign(String secret) {
        return hmac(secret, canonical());
    }

    /** 常数时间不可行（十六进制串比较），但足以防篡改；重放靠 msgId + expiresAt 拦住。 */
    public boolean verify(String secret) {
        if (hmac == null || secret == null) {
            return false;
        }
        return hmac.equalsIgnoreCase(hmac(secret, canonical()));
    }

    private static String hmac(String secret, String canonical) {
        HMac mac = new HMac(HmacAlgorithm.HmacSHA256, secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return mac.digestHex(canonical);
    }
}
