package com.wyc.demo03.service.impl;

import com.wyc.demo03.entity.CheckinCode;
import com.wyc.demo03.mapper.CheckinCodeMapper;
import com.wyc.demo03.service.CheckinCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CheckinCodeServiceImpl implements CheckinCodeService {

    private final CheckinCodeMapper checkinCodeMapper;
    private final Clock clock;

    /** 加密强度随机源：4 位数字只有 1 万种组合，绝不能用 Math.random。 */
    private final SecureRandom random = new SecureRandom();

    @Override
    public String issue(Long reservationId) {
        CheckinCode existing = checkinCodeMapper.findByReservationId(reservationId);
        if (existing != null) {
            return existing.getCode();
        }
        String code = String.format("%04d", random.nextInt(10000));

        CheckinCode entity = new CheckinCode();
        entity.setReservationId(reservationId);
        entity.setCode(code);
        entity.setIssuedAt(LocalDateTime.now(clock));
        checkinCodeMapper.insert(entity);
        return code;
    }

    @Override
    public void revoke(Long reservationId) {
        checkinCodeMapper.revoke(reservationId, LocalDateTime.now(clock));
    }

    @Override
    public boolean verify(Long reservationId, String code) {
        if (code == null) {
            return false;
        }
        CheckinCode existing = checkinCodeMapper.findByReservationId(reservationId);
        return existing != null
                && existing.isActive()
                && existing.getCode().equals(code.trim());
    }

    @Override
    public CheckinCode findByReservationId(Long reservationId) {
        return checkinCodeMapper.findByReservationId(reservationId);
    }
}
