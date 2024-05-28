package com.gihub.jeongrae.springkit.domain.member.service;

import com.gihub.jeongrae.springkit.domain.member.dto.MemberDTO;
import com.gihub.jeongrae.springkit.domain.member.dto.RegisterRequest;

public interface MemberService {
    MemberDTO createMember(RegisterRequest request);
}
