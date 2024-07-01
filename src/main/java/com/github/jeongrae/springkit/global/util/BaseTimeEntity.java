package com.github.jeongrae.springkit.global.util;


import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public class BaseTimeEntity {
    @CreatedDate
    @DateTimeFormat(pattern = "yyyy-MM-dd/HH:mm:ss")
    @Column(name = "created_at", updatable = false, columnDefinition = "DATETIME(0)")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @DateTimeFormat(pattern = "yyyy-MM-dd/HH:mm:ss")
    @Column(name = "modified_at", columnDefinition = "DATETIME(0)")
    private LocalDateTime modifiedAt;
}