package com.synera.api.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;

/** Never serializes exception messages/stack traces or arbitrary MDC/header data. */
public class SafeJsonLogLayout extends LayoutBase<ILoggingEvent> {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static String redact(String value){
        if(value==null)return "";
        return value
            .replaceAll("(?i)Bearer\\s+[^\\s,;]+","Bearer [REDACTED]")
            .replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+","[TOKEN]")
            .replaceAll("(?i)((?:password|passwd|token|secret|authorization|access[_-]?key)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}]+","$1[REDACTED]")
            .replaceAll("(?<![A-Za-z0-9])\\+?\\d(?:[ .-]?\\d){9,14}(?![A-Za-z0-9])","[PHONE]");
    }
    @Override public String doLayout(ILoggingEvent event){
        var row=new LinkedHashMap<String,Object>();
        row.put("timestamp",Instant.ofEpochMilli(event.getTimeStamp()).toString());
        row.put("level",event.getLevel().toString());row.put("logger",event.getLoggerName());
        row.put("message",redact(event.getFormattedMessage()));
        String id=event.getMDCPropertyMap().get("requestId");if(id!=null)row.put("requestId",id);
        if(event.getThrowableProxy()!=null)row.put("exceptionType",event.getThrowableProxy().getClassName());
        try{return JSON.writeValueAsString(row)+System.lineSeparator();}catch(Exception e){return "{\"level\":\"ERROR\",\"message\":\"log_serialization_failed\"}\n";}
    }
}
