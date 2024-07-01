package com.github.jeongrae.springkit.domain.member.repository;

import com.github.jeongrae.springkit.domain.member.domain.Member;
import com.github.jeongrae.springkit.domain.member.domain.OAuthProviderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findMemberByEmail(String email);

    @Query("SELECT m " +
            "FROM Member m " +
            "JOIN m.memberOAuths mo " +
            "ON m.id = mo.member.id " +
            "WHERE mo.oauthId = :oauthId AND mo.oAuthProviderType = :providerType")
    Optional<Member> findMemberByOAuthIdAndProviderType(@Param("oauthId") String oauthId, @Param("providerType") OAuthProviderType providerType);
}
