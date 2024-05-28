package com.gihub.jeongrae.springkit.domain.member.domain;

import com.gihub.jeongrae.springkit.global.util.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@Getter @ToString(exclude = {"password"})
@Table
@Entity
public class Member extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Column(name = "email", unique = true) @NotNull
    private String email;

    @Column(name = "password") @NotNull
    private String password;

    @Builder
    public Member(Long id, String email, String password) {
        this.id = id;
        this.email = email;
        this.password = password;
    }

    public Member() {
    }
}
