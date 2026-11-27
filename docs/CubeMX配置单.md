# CubeMX 配置单 · 门禁终端固件

> 适用：智慧研讨室门禁终端（ROOM-A-01 / ROOM-B-01）  
> 目标芯片：**STM32F103ZET6**（LQFP144，512KB Flash / 64KB RAM）  
> 开发板：正点原子 ZET6 系列（精英版 / 战舰版），板载 USB 串口与 8266 模块插座  
> 参照模板：`D:/code/AIoT/AIOT_Device_STM32F103_WIFI`  
> 硬件依据：`D:/精英STM32F103  资料/精英V2 硬件参考手册_V1.0.pdf`（§1.2.2 IO 分配总表、§2.1.17/19/20/22）  
> 编写日期：2026-11-26（v4，按精英 V2 手册校正引脚，硬件到货后定稿）

## 0. 开工前

| 项         | 值                                                           |
| --------- | ----------------------------------------------------------- |
| CubeMX 版本 | 6.10 及以上（F1 固件包 V1.8.5）                                     |
| 工具链       | MDK-ARM V5（Keil 5.27）                                       |
| 新建工程      | `File → New Project → 搜索 STM32F103ZE` → 选 **STM32F103ZETx** |
| 工程名       | `DoorDevice`（路径不要带中文）                                       |

**为什么 ZET6 比 C8T6 省事**：正点原子这块板上，USART1（PA9/PA10）已经接到 CH340 转 USB，**printf 直接看，不用另买 USB-TTL**；USART3（PB10/PB11）引到了 8266 模块插座，**模块插上去就行，不用飞线**。模板工程正是这么用的，所以引脚规划可以整体沿用。

另外 512KB Flash / 64KB RAM 比 C8T6 宽裕十倍，屏幕整屏缓冲、去重表、日志缓冲都不用再抠。

**到货四件套的落位**（v4 按手册核对）：

| 到货件                     | 接法                                   | 备注                                          |
| ----------------------- | ------------------------------------ | ------------------------------------------- |
| STM32F103ZET6 精英 V2     | 主板                                   | 板载 CH340 / 蜂鸣器 / LED / 24C02 / ATK-MODULE 座 |
| 有源蜂鸣器                   | **优先用板载 PB8**；外接件留作备用                | 板载 BEEP 已带 Q1 扩流 + R33 下拉，复位不会误响            |
| ESP8266 模块              | 插 ATK-MODULE 座 U3，USART3 = PB10/PB11 | 无 RST/EN 引出；供电用板上 3.3V                      |
| SSD1306 128×64（4 针 I2C） | 3.3V/GND/SCL→PB6/SDA→PB7             | **不要插 P4 并口座**；地址 0x3C                      |

## 1. 时钟树（Clock Configuration 页）

你截图里的配置**主链路完全正确**，只有两个被忽略的分支超了规格。对照表：

| 节点                    | 截图现状            | 应为                | 结论              |
| --------------------- | --------------- | ----------------- | --------------- |
| Input frequency (HSE) | 8 MHz           | 8 MHz             | ✅               |
| PLL Source Mux        | HSE             | HSE               | ✅               |
| PLLMul                | ×9              | ×9                | ✅ → 72 MHz      |
| System Clock Mux      | PLLCLK → 72     | 同                 | ✅               |
| AHB Prescaler         | /1 → 72         | /1                | ✅               |
| APB1 Prescaler        | /2 → 36         | /2                | ✅ 上限就是 36，不能 /1 |
| APB2 Prescaler        | /1 → 72         | /1                | ✅               |
| **ADC Prescaler**     | **/2 → 36 MHz** | **/6 → 12 MHz**   | ❌ 需改            |
| **USB Prescaler**     | **/1 → 72 MHz** | **/1.5 → 48 MHz** | ❌ 需改            |
| LSE                   | 32.768 kHz 已开   | 建议关               | ⚠️ 见下           |
| CSS                   | 已勾选             | 保留                | ✅               |

**两处超频为什么现在不报错**：ADC 和 USB 在本项目里都是 Disable 状态，CubeMX 就不会去校验它们的时钟，所以 36 MHz、72 MHz 都显示黑色而不是红色，程序也能跑。但只要哪天你点开 ADC 采一次电压，36 MHz 超过 14 MHz 上限，采回来的就是垃圾数据，而且不会报错——这种坑最难查。改回去只有两次点击：ADC 选 `/6`，USB 选 `/1.5`。

**LSE 建议关掉**：截图里 LSE 是开的，RTC 时钟源当前指向 LSI（40 kHz）。本项目时间基准走"服务端授时 + SysTick"，不需要 RTC。留着 LSE 的代价是：万一那颗 32.768k 晶振不起振，`HAL_RCC_OscConfig()` 会等满 5 秒然后调用 `Error_Handler()`，现象是**上电就停在那儿不动**，非常容易被误判成程序跑飞、堆栈溢出。省一个故障点更划算。将来若想做"断电不丢时间"，再把 LSE + RTC 开回来，同时装 CR1220 电池。

**CSS 保留**：HSE 突然失效时自动切到 HSI 并进 NMI，比直接死机好。

## 2. Pinout & Configuration 逐项配置

按左侧树从上往下勾，不要跳。

### 2.1 System Core

| 位置       | 配置项                    | 值                             | 说明                                 |
| -------- | ---------------------- | ----------------------------- | ---------------------------------- |
| **RCC**  | High Speed Clock (HSE) | **Crystal/Ceramic Resonator** | 板载 8M 晶振                           |
|          | Low Speed Clock (LSE)  | **Disable**                   | 见第 1 节                             |
| **SYS**  | Debug                  | **Serial Wire**               | 省 PA15/PB3/PB4                     |
|          | Timebase Source        | **SysTick**                   | 不加 RTOS                            |
| **IWDG** | Activated              | **先不勾**                       | 第 6 节，联调稳定后再开                      |
|          | Prescaler              | 64                            | LSI 40kHz / 64 = 625Hz             |
|          | Down-counter reload    | 1250                          | 约 2 秒（LSI 实际 30–60kHz，真值 1.3–2.6s） |

### 2.2 Connectivity

| 位置         | 配置项                  | 值                                       |
| ---------- | -------------------- | --------------------------------------- |
| **USART1** | Mode                 | **Asynchronous**                        |
|            | Baud Rate            | 115200 Bits/s                           |
|            | Word / Parity / Stop | 8 / None / 1                            |
|            | **引脚**               | PA9 = TX，PA10 = RX（板上接 CH340，printf 通道） |
| **USART3** | Mode                 | **Asynchronous**                        |
|            | Baud Rate            | 115200 Bits/s（与 8266 模组一致，**别改**）       |
|            | 其余同上                 | 8 / None / 1 / 收发                       |
|            | **引脚**               | PB10 = TX，PB11 = RX（板上接 8266 插座）        |

**USART3 的 DMA**（`DMA Settings` → Add）：

| 请求            | 通道                 | Direction            | Mode       | Priority |
| ------------- | ------------------ | -------------------- | ---------- | -------- |
| USART3_RX     | **DMA1 Channel 3** | Peripheral To Memory | **Normal** | Low      |
| USART3_TX（可选） | **DMA1 Channel 2** | Memory To Peripheral | **Normal** | Low      |

> RX 必须是 **Normal** 不能用 Circular：`HAL_UARTEx_ReceiveToIdle_DMA` 靠"重新启动接收"划定缓冲边界，Circular 下长度统计会错。这一点和模板一致。
>
> TX 用 DMA 的原因见第 5 节：MQTT 报文含 `0x00`，模板的 `STM32_Device_Send(char*)` 走 `strlen`，会在第一个 NUL 截断。换成按长度发之后，开 DMA 可以不阻塞主循环（单条 500 字节在 115200 下要 43 ms）。先用阻塞 `HAL_UART_Transmit()` 也能跑通，DMA 是优化项。

### 2.3 其他外设

| 外设                            | 动作                                     |
| ----------------------------- | -------------------------------------- |
| ADC1 / ADC2 / ADC3            | **全部 Disable**                         |
| SPI / CAN / USB / SDIO / FSMC | **全部 Disable**                         |
| RTC                           | **Disable**（时间走服务端授时 + SysTick）        |
| TIM                           | **全部 Disable**（蜂鸣器是有源的，只要高低电平，不需要 PWM） |

### 2.4 板载 LED（正点原子板自带，不用接线）

| 引脚      | 用途   | 设置                            | User Label |
| ------- | ---- | ----------------------------- | ---------- |
| **PB5** | LED0 | GPIO_Output，**初始 High（灭）**，推挽 | `LED0`     |
| **PE5** | LED1 | GPIO_Output，**初始 High（灭）**，推挽 | `LED1`     |

这两行**直接照抄模板的 `.ioc` 即可**（模板里就是 PE5、PB5 两个 GPIO 输出、PinState=SET）。用法建议：LED0 做 1 秒心跳，LED1 做网络状态（连上 Broker 常亮）。

> **更正上一版的说法**：我在 v1 里猜"PB5 可能是 8266 的 RST"，查了模板 `.ioc` 后确认不是——`PE5` 和 `PB5` 都是输出且初始高电平，正好对应这块板的 LED0/LED1（低电平点亮）。所以 PB5 **必须留给 LED0**，不能拿来接蜂鸣器。

### 2.5 外接器件（新增）

统一参数：`Output Push Pull` / `No pull-up and no pull-down` / `Low` 速度。

| 引脚      | 用途            | GPIO output level | User Label | 说明                                                              |
| ------- | ------------- | ----------------- | ---------- | --------------------------------------------------------------- |
| **PB0** | 12V 电控锁       | **Low**           | `LOCK`     | 上电即闭合；手册标注为 `LCD_BL`，**只在没插 TFTLCD 时可用**                        |
| **PB8** | **板载有源蜂鸣器**   | **Low**           | `BUZZ`     | 手册 §2.1.20：BEEP 接 PB8，Q1 扩流、R33 下拉，**高电平响**；板子已经带蜂鸣器，外接的那个可以先不用 |
| **PB1** | 外接蜂鸣器（备用）     | **Low**           | `BUZZ_EXT` | 仅当坚持用外接模块时才配；手册标注 `T_SCK`，与 TFTLCD 触摸屏复用，没插屏可用                  |
| —       | 8266 RST / EN | —                 | —          | **不分配 GPIO**，见下方说明                                              |



> **PB12/PB13 不能用**：查手册 §1.2.2 确认，PB12 = `F_CS`（板载 25Q128 片选），PB13 = `SPI2_SCK`（25Q128 + WIRELESS 接口），PB14/PB15 同理被 SPI2 占用。v3 把它们当"可选 RST/EN"是错的，已从配置中删除。
>
> **8266 复位**：精英 V2 的 ATK-MODULE 接口(U3)只引出 `GBC_TX/GBC_RX`（PB11/PB10）、`GBC_KEY`（PA4）、`GBC_LED`（PA15），**没有 RST/EN 脚**。模块自己的复位电路处理即可；不要飞线到 PB12/PB13。PA15 与 JTAG 的 JTDI 复用，保持 SWD 调试方式，别当普通 IO。

### 2.6 GPIO 输入（本地按键）

| 引脚              | 配置         | 值                                                                    |
| --------------- | ---------- | -------------------------------------------------------------------- |
| **PA0**（KEY_UP） | GPIO_Input | **Pull-down** + **External Interrupt Mode with Rising edge trigger** |
|                 | User Label | `KEY`                                                                |
|                 | NVIC       | 勾选 `EXTI line0 interrupt`，抢占优先级 3                                    |

> **v3 这里配反了**：手册 §2.1.17 明确 KEY_UP（PA0）是**高电平触发**，按下接 3.3V。所以要内部下拉 + **上升沿**，不是上拉 + 下降沿。板上另外两个键 KEY0/KEY1 在 PE4/PE3，才是低电平触发（内部上拉）；本项目用 PA0 即可。

短按 1 秒显示 IP 与信号强度，长按 5 秒清 WiFi 配置。**不是开门键**——开门只能由服务端指令触发，本地键任何情况下都不得直接驱动锁。

### 2.7 引脚总表（配置完对一遍）

| 引脚              | 信号               | 本项目用途                                   | 模板里的用法            |
| --------------- | ---------------- | --------------------------------------- | ----------------- |
| PA9 / PA10      | USART1_TX/RX     | printf（板载 CH340，P3 跳线帽需接通）              | 同                 |
| PB10 / PB11     | USART3_TX/RX     | 8266（ATK-MODULE 座 U3，`GBC_RX`/`GBC_TX`） | 同                 |
| PB6 / PB7       | **I2C1_SCL/SDA** | SSD1306 屏幕（与板载 24C02 共总线）               | 未使用（新增）           |
| PB0             | GPIO_Output      | 电控锁（未插 LCD 时可用）                         | 未使用（新增）           |
| **PB8**         | GPIO_Output      | **板载有源蜂鸣器 BEEP**（高电平响）                  | 未使用（新增）           |
| PB1             | GPIO_Output      | 外接蜂鸣器备用（未插 LCD 时可用）                     | 未使用（新增）           |
| PB5 / PE5       | GPIO_Output      | **板载 LED0 / LED1**                      | 同（保持）             |
| ~~PB12 / PB13~~ | ~~GPIO_Output~~  | **不可用**：25Q128 片选 / SPI2_SCK            | 25Q128 + WIRELESS |
| PA0             | GPIO_EXTI0       | 本地按键 KEY_UP（**上升沿**）                    | 未使用（新增）           |
| PA13 / PA14     | SYS_JTMS/JTCK    | SWD 下载调试                                | 同                 |
| PD0 / PD1       | OSC_IN/OUT       | 8M 晶振                                   | 同                 |

**I2C1 参数**：`I2C → Fast Mode，400000 Hz`，Duty cycle 16:9。在图上点 PB6 → `I2C1_SCL`、PB7 → `I2C1_SDA` 即可自动启用。手册确认 PB6/PB7 同时接板载 24C02（地址 0x50），属于共总线，正常。

> 正点原子板载的 24C02 EEPROM 通常也挂在 I2C1（PB6/PB7，地址 **0x50**）。所以上电扫描 I2C 会看到两个地址：`0x3C`（OLED）和 `0x50`（EEPROM）——**这是正常的，不是接错**。两者地址不冲突，可以共存；两组 4.7k 上拉并联后约 2.4k，400kHz 下仍合规，不用拆。

## 2.8 屏幕（SSD1306 128×64）专项说明

对照你给的资料包 `D:/Edge_download/IIC-OLED显示屏资料(SSD1306)/`：

| 项          | 值                                         |
| ---------- | ----------------------------------------- |
| 面板 / 驱动 IC | UG-2864HSWEG01 / **SSD1306**              |
| 分辨率        | 128 × 64（8 页 × 128 列）                     |
| 接口         | I2C，4 针：VCC / GND / SCL / SDA             |
| 7 位从机地址    | **0x3C**（例程里写的 `0x78` 是含读写位的 8 位形式，同一个东西） |
| 供电         | **3.3V**（接板上 3.3V，别接 5V）                  |
| 接线         | SCL → PB6，SDA → PB7，GND → 板 GND           |

> **别插板上的 P4 "OLED/摄像头接口"**：手册 §2.1.19 说明 P4 是**并行**摄像头接口，SCL/SDA 走 PD3/PG13、数据走 PC0~7，不是 I2C，和 4 针 I2C 屏不是一回事。你手上的 SSD1306 是 4 针模块，必须飞线到 PB6/PB7（顺便和 24C02 共总线）。

**CubeMX 侧不用改**：例程里 `I2C_ClockSpeed = 400000`、`I2C_DutyCycle_2`（16:9）、`GPIO_Mode_AF_OD` 开漏，与本单 2.2 节的 I2C1 配置逐项一致。接上就能通信。

**例程是标准外设库（SPL）写的，移植到 HAL 只改最底下三个函数**，上层 `OLED_Init / OLED_SetPos / OLED_CLS / OLED_ShowStr / OLED_ShowCN / OLED_DrawBMP` 和三张字库表原样搬：

```c
/* 命令：控制字节 0x00 */
HAL_I2C_Mem_Write(&hi2c1, 0x3C << 1, 0x00, I2C_MEMADD_SIZE_8BIT, cmd, 1, 10);
/* 数据：控制字节 0x40，可一次发 len 个字节 */
HAL_I2C_Mem_Write(&hi2c1, 0x3C << 1, 0x40, I2C_MEMADD_SIZE_8BIT, buf, len, 100);
```

**三处照抄例程会出问题，必须改：**

| # | 例程写法                                                 | 问题                                                                  | 改法                                                             |
| - | ---------------------------------------------------- | ------------------------------------------------------------------- | -------------------------------------------------------------- |
| 1 | 每写 1 字节就 START/STOP 一次（`I2C_WriteByte`）              | 写满一屏 1024 字节 = **1024 次 I2C 传输**，400kHz 下要 100ms 以上，每秒刷新的倒计时会肉眼可见地卡 | SSD1306 支持连续写：一次事务发 `0x40` + N 个数据，地址自动递增。按页批量，一屏 8 次传输，约 25ms |
| 2 | `while(I2C_GetFlagStatus(I2C1, I2C_FLAG_BUSY));` 无超时 | SDA 被从机拉死时**永久卡死**，配合 IWDG 就是"不停重启、屏幕永远不亮"                          | 加计数超时；超时后做总线恢复（SCL 手动打 9 个脉冲再补 STOP）                           |
| 3 | `unsigned char F16x16[]` 没有 `const`                  | 十几 KB 字库被拷进 RAM，白占 1/6 内存                                           | 改成 `const unsigned char F16x16[]` 放 Flash，ZET6 有 512KB，随便放     |

**中文字库**：资料包里 `codetab.h` 已经带 `F16x16` 表和配套取字软件（`128x64取字软件/`）。本项目的固定文案就那几个——`研讨室A`、`研讨室B`、`签到码`、`剩余`、`空闲`、`已签到`、`设备离线`、`请输码`——一次性取完加进表里，不要运行时拼字。

**排版预算**：`F8X16`（8×16）一行 16 字 × 4 行；`F6x8`（6×8）一行 21 字 × 8 行。签到码建议单独占一行并用 8×16，远看才认得出。

## 3. NVIC（System Core → NVIC）

Priority Group 保持默认 `4 bits for pre-emption priority`。

| 中断                             | 使能 | 抢占优先级 | 说明                  |
| ------------------------------ | -- | ----- | ------------------- |
| USART3 global interrupt        | ✅  | 1     | 8266 收数（IDLE + DMA） |
| DMA1 channel3 global interrupt | ✅  | 1     | 与 USART3 同级         |
| DMA1 channel2（若启用 TX DMA）      | ✅  | 2     | 只用来清标志              |
| EXTI line0 interrupt           | ✅  | 3     | 按键，最低，防抖动占 CPU      |
| SysTick                        | ✅  | 15    | HAL 时基，默认别动         |
| USART1                         | ❌  | —     | printf 用阻塞发送，不需要中断  |

> **不要在 USART3 中断里做验签或刷屏**：HMAC 算一条 0.2 ms，屏刷一次 25 ms，会把串口接收拖垮导致丢包。中断里只做"标记有新数据 + 重启 DMA"，处理全放主循环。

## 4. Project Manager

### 4.1 Project 页

| 项                          | 值                                     |
| -------------------------- | ------------------------------------- |
| Toolchain / IDE            | **MDK-ARM**，Min Version **V5.27**     |
| Application Structure      | **Advanced**（生成 Core/Inc、Core/Src 分层） |
| Generate Under Root        | ✅                                     |
| Do not generate the main() | ❌ 不勾                                  |

### 4.2 Code Generator 页

| 项                                                                    | 值                                               |
| -------------------------------------------------------------------- | ----------------------------------------------- |
| Copy only the necessary library files                                | ✅                                               |
| Generate peripheral initialization as a pair of .c/.h per peripheral | ✅（生成 gpio.c / usart.c / i2c.c / iwdg.c，与模板结构一致） |
| Keep User Code when re-generating                                    | ✅（**必须**）                                       |
| Delete previously generated files when not re-generated              | ✅                                               |

### 4.3 堆栈

| 项                  | 值          | 原因                                     |
| ------------------ | ---------- | -------------------------------------- |
| Minimum Heap Size  | **0x400**  | 不用 cJSON，几乎不申请堆；留 1KB 只是保险             |
| Minimum Stack Size | **0x1000** | 4KB。printf 带格式串时栈深，ZET6 有 64KB RAM，不必省 |

## 5. 与模板工程的 5 处关键差异（很重要）

模板是"TCP 透传 + JSON"的物联网课程工程，本项目是"TCP 上跑 MQTT"。能直接复用的是 `ATK_MW8266_detect()`、`WIFI_Connect()`、`tcpserver_connect()`、`set_8266_at_mode()` 四个函数——把 `tcpserver_connect("192.168.x.x","1883")` 的 IP 指向跑 Mosquitto 的笔记本、端口改 **1883**，这条 TCP 就是 MQTT 通道。下面五处必须改：


| # | 模板做法 | 本项目改成 | 原因 |
|---|---|---|---|
| 1 | `STM32_Device_Send(char *json)` | `HAL_UART_Transmit(&huart3, buf, len, 100)` 或 TX DMA 按长度发 | **MQTT 的 CONNECT 报文第 3 字节就是 `0x00`**（协议名长度 `0x0004`），`strlen` 到此截断，发出去是半包，Broker 静默断开、不报错 |
| 2 | 回调里 `strstr` 找 `{` `}` 判定一条 JSON | 按 **MQTT 固定头 + 剩余长度**切包 | 透传模式收到的是裸字节，`strstr` 遇二进制会误判；一包还可能分两次到达 |
| 3 | `while(1){ HAL_Delay(5000); 上报; }` | tick 比较的非阻塞主循环 | 开锁要 3 s 脉冲、心跳 30 s、倒计时 1 s 刷新，`HAL_Delay` 一挂全停 |
| 4 | `ATK_MW8266_detect()` 失败就 `while(1);` | 屏幕提示 `NO WIFI` + 退避重试 | 现场断网不能变成死机 |
| 5 | cJSON 解析 | 固定 9 个键的取值函数 | 不是为了省空间（512KB 很宽裕），而是 cJSON 重新序列化**不保证**与 Java 的 canonical 串逐字节一致，验签会莫名失败 |

> `tcpserver_connect()` 成功返回时模块已进入**透传模式**，此时 AT 指令不再被解析。要发 AT 指令（如查信号强度）必须先 `set_8266_at_mode()` 退出透传，查完再进——**别在心跳周期里来回切**，容易把 TCP 搞断。

## 6. 生成之后：按顺序验证这 8 项

编译通过（0 error）后**一项项过，不要跳**：

| # | 验证 | 通过标准 | 失败先看 |
|---|---|---|---|
| 1 | 编译 | 0 error / 0 warning | Keil 里是否加了 `ATK_MW8266.lib` |
| 2 | printf | 板载 USB 串口（115200）能看到字 | `_write` 重定向、`Use MicroLIB` |
| 3 | 8266 握手 | `ATK_MW8266_detect() == 1` | 模块是否插反、插座供电 |
| 4 | 联网 | `WIFI_Connect()` 返回 1 并打印 IP | 2.4G 频段、密码、AP 隔离 |
| 5 | 屏幕 | 上电显示 `HELLO` 与固件版本 | 先扫 I2C：有 `0x3C` 说明屏在线（`0x50` 是板载 EEPROM，正常）；再查上拉与接线 |
| 6 | 锁 | PB0 置高 500 ms，听继电器/看锁舌 | 逻辑电平 MOSFET、12V 是否独立供电 |
| 7 | 蜂鸣器 | **PB8** 置高 100 ms，板载蜂鸣器响一声 | 板载已带，无需外接；若用外接模块才查 PB1 与触发极性 |
| 8 | 按键 | 按下 KEY_UP 进 EXTI 回调，串口打印 | **PA0 内部下拉 + 上升沿**，别按 v3 的上拉/下降沿查 |

**8 项全过再开 IWDG**（2.1 节把 Activated 勾上）。否则前 7 项调试时会被反复复位，误判成"程序跑飞"。

## 7. 硬件侧三个必查项

| 项 | 检查点 |
|---|---|
| 锁的驱动管 | 若用 MOSFET，**必须是逻辑电平型**（AO3400 / SI2302 / IRLZ44N）。IRF540N 的 Vgs(th) 最高 4V，3.3V 栅压推不开，会发热且锁不动 |
| 锁的续流 | 12V 电磁锁是感性负载，线圈两端必须并 **1N4007**（或 1N5819），否则关断瞬间反峰击穿 MOS |
| 供电 | 8266 直接用**板上 3.3V** 即可（手册 §1.2.2：USB 供电时 3.3V 排针最大 500 mA，外接电源可达 1 A，8266 发射瞬间峰值要留余量）。但 **12V 锁必须独立电源**，只与主板共地，不要让锁电流走板子的地线 |
| P3 跳线 | 板载 CH340 与 USART1 之间靠 P3 跳线帽连接；printf 没输出先检查 P3 的 RXD/TXD 两个帽是否在位 |
| 8266 插槽 | ATK-MODULE 座 U3 只给串口和 GBC_KEY/LED，RST/EN 由模块自身电路处理；注意 GBC_LED 与 JTDI 共用 PA15，继续用 SWD 不要切 JTAG |

## 8. 配置完成后应生成的文件

```
DoorDevice/
├── DoorDevice.ioc
├── Core/
│   ├── Inc/   main.h  gpio.h  usart.h  i2c.h  iwdg.h  dma.h  stm32f1xx_it.h
│   └── Src/   main.c  gpio.c  usart.c  i2c.c  iwdg.c  dma.c  stm32f1xx_it.c  stm32f1xx_hal_msp.c
├── Drivers/   CMSIS + STM32F1xx_HAL_Driver
└── MDK-ARM/   DoorDevice.uvprojx
```

业务代码**不要**写进上面任何文件（除了在 `main.c` 两处 `USER CODE` 块里各加一行），全部新建在 `App/` 目录：`app_time.c`、`app_json.c`、`app_hmac.c`、`app_mqtt.c`、`app_proto.c`、`app_display.c`、`app_lock.c`、`app_main.c`。这样以后改配置重新生成，业务代码一行都不会丢。
