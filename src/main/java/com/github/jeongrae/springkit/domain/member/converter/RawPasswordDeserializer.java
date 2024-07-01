package com.github.jeongrae.springkit.domain.member.converter;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.jeongrae.springkit.domain.member.vo.RawPassword;

import java.io.IOException;

public class RawPasswordDeserializer extends JsonDeserializer<RawPassword> {
    @Override
    public RawPassword deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
        JsonNode node = p.getCodec().readTree(p);
        String password = node.asText();
        return new RawPassword(password);
    }
}
