package com.github.jeongrae.springkit.domain.member.dto;

import com.github.jeongrae.springkit.domain.member.domain.Member;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

@Getter
public class MemberDetails implements UserDetails {
    private final Member member;

    public MemberDetails(Member member) {
        this.member = member;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return null;
    }

    @Override
    public String getPassword() {
        return member.getEncodedPassword().password();
    }

    @Override
    public String getUsername() {
        return member.getId().toString();
    }
}
