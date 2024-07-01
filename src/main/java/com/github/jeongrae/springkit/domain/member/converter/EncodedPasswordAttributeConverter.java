package com.github.jeongrae.springkit.domain.member.converter;

import com.github.jeongrae.springkit.domain.member.vo.EncodedPassword;
import jakarta.persistence.AttributeConverter;

public class EncodedPasswordAttributeConverter implements AttributeConverter<EncodedPassword, String> {

    @Override
    public String convertToDatabaseColumn(EncodedPassword attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.password();
    }

    @Override
    public EncodedPassword convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return new EncodedPassword(dbData);
    }
}
