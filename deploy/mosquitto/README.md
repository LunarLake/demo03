# 门禁 MQTT 通道部署说明

> 对应计划 6.1–6.4 与 P5-T05。
> 本文覆盖三种运行形态：**无 Broker 演示**、**本地 Broker**、**接真设备**。

## 一、三种运行形态

| 形态 | 配置 | 说明 |
|---|---|---|
| **A. 默认（推荐起步）** | `app.door.mode=log` | 不做任何 MQTT，签到成功直接回"门禁已开锁"，日志里每条都标 `[模拟门禁]`。用来跑业务与答辩演示，不涉及网络 |
| **B. MQTT 全链路（无需 Broker）** | `SPRING_PROFILES_ACTIVE=local,mqtt-demo` | 走进程内总线，后端与 `DeviceSimulator` 在同一进程里互通。**信封、HMAC、过期、去重、绑定全部真实执行**，能验证协议是否正确 |
| **C. 真实 Broker** | `app.door.mode=mqtt` + `app.mqtt.mode=paho` | 连 Mosquitto。硬件到货后用它接 STM32 |

形态 B 是交付物里最重要的一种：它证明"协议跑得通"，而不只是"代码编译得过"。

```bash
# 形态 B（必须同时激活 local，否则数据库密码配置不会加载）
./mvnw spring-boot:run -Dspring-boot.run.profiles=local,mqtt-demo
# 然后在页面上签到，控制台会打印：
#   [LOCK] PULSE 3000ms  reason=FIRST_CHECKIN
#   [SCREEN] ...
# 屏幕文本同时写到 target/simulator/screen.txt，可直接截图
```

## 二、起一个本地 Mosquitto

```bash
cd <项目根>

# 1) 生成密码文件（用户名 backend / room-a / room-b）
docker run --rm -v "$PWD/deploy/mosquitto:/cfg" eclipse-mosquitto:2 \
  sh -c 'touch /cfg/passwd && \
         mosquitto_passwd -b /cfg/passwd backend change-me && \
         mosquitto_passwd -b /cfg/passwd room-a room-a-secret && \
         mosquitto_passwd -b /cfg/passwd room-b room-b-secret'

# 2) 启动
docker run -d --name mosquitto -p 1883:1883 \
  -v "$PWD/deploy/mosquitto/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro" \
  -v "$PWD/deploy/mosquitto/acl:/mosquitto/config/acl:ro" \
  -v "$PWD/deploy/mosquitto/passwd:/mosquitto/config/passwd:ro" \
  eclipse-mosquitto:2

# 3) 冒烟（应当能通）
docker run --rm --network host eclipse-mosquitto:2 \
  mosquitto_pub -h 127.0.0.1 -p 1883 -u backend -P change-me \
    -t room/ROOM-A-01/cmd -m '{"v":1}' -q 1
```

## 三、把后端接到 Broker

```bash
export MQTT_MODE=paho
export MQTT_BROKER_URL=tcp://127.0.0.1:1883
export MQTT_USERNAME=backend
export MQTT_PASSWORD=change-me
export DOOR_MODE=mqtt
export SIMULATOR_ENABLED=true     # 没有真设备时用模拟器顶替
./mvnw spring-boot:run
```

连不上 Broker 时应用**照常启动**：门禁显示离线、开门被拒并提示使用机械释放杆，
但预约、签到、申诉全部正常。这是刻意的——门禁故障不该让整个系统不可用。

## 四、主题与安全约定

| 主题 | 方向 | QoS | Retained |
|---|---|---|---|
| `room/{deviceCode}/snapshot` | 服务端 → 设备 | 1 | 是 |
| `room/{deviceCode}/cmd` | 服务端 → 设备 | 1 | 否 |
| `room/{deviceCode}/ack` | 设备 → 服务端 | 1 | 否 |
| `room/{deviceCode}/heartbeat` | 设备 → 服务端 | 0 | 否 |
| `room/{deviceCode}/event` | 设备 → 服务端 | 1 | 否 |

三条不能破的规矩：

1. **设备只能读写自己的 `room/{code}/#`**。给设备配 `room/#` 或 `#`，一台被攻破就是整层楼的门。
2. **`cmd` 绝不 retained**。保留消息会在设备重连时被补发，等于把几分钟前的开锁指令重放一次。
3. **服务端对 `ack` 也要验 HMAC**。ACL 只管到主题级，Broker 配置失误或账号泄露时，
   不验签就意味着别人发一句 `resultCode=OK` 就能让服务端记录"门已开"。

## 五、设备侧要实现的五步校验

顺序不能改（先验签再判过期，避免用过期时间探测密钥）：

1. `deviceCode` 与自己一致
2. `hmac` 校验通过
3. `expiresAt > now`（旧指令不得触发开锁）
4. `msgId` 不在最近 16 条去重表内（QoS1 允许重复投递）
5. `reservationId` 与当前屏幕显示的预约一致（防止跨预约误开）

任一步失败都要回 `ack`，`resultCode` 取 `ERR_DEVICE / ERR_HMAC / ERR_EXPIRED / ERR_DUP / ERR_BIND`，
让服务端能把指令结算为 `FAILED` 并留下可查的记录。

## 六、信封格式

```json
{
  "v": 1,
  "msgId": "0f3c8f0a-...",
  "type": "UNLOCK",
  "deviceCode": "ROOM-A-01",
  "reservationId": 42,
  "issuedAt": "2026-10-21T10:03:12",
  "expiresAt": "2026-10-21T10:03:42",
  "payload": "reservation=42;room=1;user=7;reason=FIRST_CHECKIN;pulseMs=3000",
  "hmac": "b1e4..."
}
```

`hmac = HMAC-SHA256(secret, "v|msgId|type|deviceCode|reservationId|issuedAt|expiresAt|payload")`

**payload 是扁平 `k=v;k=v` 字符串，不是 JSON 对象**（对计划 6.2 的契约澄清）：
canonical 串必须两端逐字节一致，而 JSON 对象的键序、空格、数字格式在不同实现里都不一样。
STM32 侧按 `;` 和 `=` 切分即可，不必写一个"和 Java 完全一致"的 JSON 序列化器。
