package com.wyc.demo03.service.impl;

import com.wyc.demo03.entity.DeviceCommand;
import com.wyc.demo03.mapper.DeviceCommandMapper;
import com.wyc.demo03.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeviceCommandServiceImpl implements DeviceCommandService {

    private final DeviceCommandMapper deviceCommandMapper;
    private final Clock clock;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceCommand issue(Long deviceId, Long reservationId, DeviceCommand.Type type,
                               String payload, int ttlSeconds) {
        LocalDateTime now = LocalDateTime.now(clock);
        DeviceCommand command = new DeviceCommand();
        command.setMsgId(UUID.randomUUID().toString());
        command.setDeviceId(deviceId);
        command.setReservationId(reservationId);
        command.setCmdType(type.name());
        command.setPayload(payload);
        command.setStatus(DeviceCommand.Status.PENDING.name());
        command.setIssuedAt(now.withNano(0));
        // 同样截到秒：DATETIME 无小数位，四舍五入会让有效期比预期晚 1 秒
        command.setExpiresAt(now.plusSeconds(ttlSeconds).withNano(0));
        command.setRetryCount(0);
        deviceCommandMapper.insert(command);
        return command;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markSent(String msgId) {
        return deviceCommandMapper.markSent(msgId) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean ack(String msgId, DeviceCommand.Status status, String resultCode) {
        return deviceCommandMapper.ack(msgId, status.name(), resultCode,
                LocalDateTime.now(clock).withNano(0)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int expireOverdue(LocalDateTime now) {
        return deviceCommandMapper.expireOverdue(now);
    }

    @Override
    public DeviceCommand findByMsgId(String msgId) {
        return deviceCommandMapper.findByMsgId(msgId);
    }
}
