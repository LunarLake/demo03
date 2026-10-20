package com.wyc.demo03.mqtt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 信封与 HMAC。
 *
 * <p>这是设备侧唯一的可信边界：只要 canonical 串在两端的拼法有一点点不同，
 * 所有指令都会被判为篡改；反过来，只要 canonical 漏掉某个字段，
 * 攻击者就能改那个字段而不被发现。
 */
class MqttEnvelopeTest {

    private static final String SECRET = "test-secret";

    private MqttEnvelope sample() {
        MqttEnvelope e = new MqttEnvelope();
        e.setV(1);
        e.setMsgId("0f3c-aaaa");
        e.setType("UNLOCK");
        e.setDeviceCode("ROOM-A-01");
        e.setReservationId(42L);
        e.setIssuedAt("2026-10-21T10:03:12");
        e.setExpiresAt("2026-10-21T10:03:42");
        e.setPayload("reservation=42;reason=FIRST_CHECKIN");
        return e;
    }

    @Test
    @DisplayName("canonical 用 | 拼接，空值参与计算为空串")
    void canonicalFormat() {
        MqttEnvelope e = sample();
        assertEquals("1|0f3c-aaaa|UNLOCK|ROOM-A-01|42|2026-10-21T10:03:12|2026-10-21T10:03:42"
                        + "|reservation=42;reason=FIRST_CHECKIN",
                e.canonical());

        // 没有 reservationId 的指令（如 CLEAR）也必须能算出稳定的 canonical
        e.setReservationId(null);
        assertTrue(e.canonical().contains("|ROOM-A-01||"));
    }

    @Test
    @DisplayName("签名可自校验，换密钥即失败")
    void signAndVerify() {
        MqttEnvelope e = sample();
        e.setHmac(e.sign(SECRET));

        assertTrue(e.verify(SECRET));
        assertFalse(e.verify("other-secret"));
        assertFalse(e.verify(null));
        assertFalse(sample().verify(SECRET), "没有 hmac 字段时应验签失败");
    }

    @Test
    @DisplayName("★ 任一字段被改都会验签失败")
    void tamperAnyFieldIsDetected() {
        MqttEnvelope e = sample();
        e.setHmac(e.sign(SECRET));
        String original = e.getHmac();
        assertTrue(e.verify(SECRET));

        // reservationId 被改：最常见的攻击——把别人预约的开锁指令改成自己的
        MqttEnvelope tampered = sample();
        tampered.setHmac(original);
        tampered.setReservationId(43L);
        assertFalse(tampered.verify(SECRET), "改预约号必须被发现");

        // payload 被改（例如把 pulseMs 改成 60000 一直开着锁）
        MqttEnvelope payloadTampered = sample();
        payloadTampered.setHmac(original);
        payloadTampered.setPayload("reservation=42;reason=FIRST_CHECKIN;pulseMs=60000");
        assertFalse(payloadTampered.verify(SECRET), "改载荷必须被发现");

        // expiresAt 被延长
        MqttEnvelope expiryTampered = sample();
        expiryTampered.setHmac(original);
        expiryTampered.setExpiresAt("2099-01-01T00:00:00");
        assertFalse(expiryTampered.verify(SECRET), "改有效期必须被发现");
    }

    @Test
    @DisplayName("签名大小写不敏感（不同实现可能输出大写十六进制）")
    void verifyIsCaseInsensitive() {
        MqttEnvelope e = sample();
        e.setHmac(e.sign(SECRET).toUpperCase());
        assertTrue(e.verify(SECRET));
    }

    @Test
    @DisplayName("不同内容产生不同签名")
    void signatureDependsOnContent() {
        MqttEnvelope a = sample();
        MqttEnvelope b = sample();
        b.setMsgId("0f3c-bbbb");

        assertNotEquals(a.sign(SECRET), b.sign(SECRET));
    }
}
