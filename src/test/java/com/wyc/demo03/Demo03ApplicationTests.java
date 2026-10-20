package com.wyc.demo03;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class Demo03ApplicationTests {

    // 上下文冒烟测试不得启动真实定时清理、修改 local 数据库。
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.wyc.demo03.task.ReservationScheduler reservationScheduler;

    @Test
    void contextLoads() {
    }

}
