package com.gihub.jeongrae.springkit.domain.member.domain;

import com.gihub.jeongrae.springkit.domain.member.converter.EncodedPasswordAttributeConverter;
import com.gihub.jeongrae.springkit.domain.member.vo.EncodedPassword;
import com.gihub.jeongrae.springkit.global.util.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Getter @ToString(exclude = {"password"})
@Table
@Entity
public class Member extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Column(name = "email", length = 64, unique = true) @NotNull
    private String email;

    @Column(name = "password", length = 32) @NotNull
    @Convert(converter = EncodedPasswordAttributeConverter.class)
    private EncodedPassword encodedPassword;

    @OneToMany(mappedBy = "member")
    private List<MemberOAuth> memberOAuths = new ArrayList<>();

    @Builder
    public Member(Long id, String email, EncodedPassword encodedPassword) {
        this.id = id;
        this.email = email;
        this.encodedPassword = encodedPassword;
    }

    public Member() {
    }
}
