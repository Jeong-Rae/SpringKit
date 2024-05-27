package com.gihub.jeongrae.springkit.domain.member.repository;

import com.gihub.jeongrae.springkit.domain.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {
}
