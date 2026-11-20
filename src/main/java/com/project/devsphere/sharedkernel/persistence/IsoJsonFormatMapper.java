package com.project.devsphere.sharedkernel.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;

/** JSONB instants use ISO 8601 strings, matching database calendar validation. */
public final class IsoJsonFormatMapper implements FormatMapper {
    private final FormatMapper delegate = new JacksonJsonFormatMapper(new ObjectMapper()
            .findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    @Override
    public <T> T fromString(CharSequence value, JavaType<T> javaType, WrapperOptions options) {
        return delegate.fromString(value,javaType,options);
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType, WrapperOptions options) {
        return delegate.toString(value,javaType,options);
    }
}
