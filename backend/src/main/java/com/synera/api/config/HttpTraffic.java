package com.synera.api.config;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;

/** Fixed five-minute window. In-memory, per instance; no unbounded request history. */
@Component
public class HttpTraffic {
    private long window=System.currentTimeMillis()/300000,total,errors;
    public synchronized void record(int status){roll();total++;if(status>=500)errors++;}
    public synchronized Map<String,Object> snapshot(){roll();return Map.of("windowStartedAt",Instant.ofEpochMilli(window*300000).toString(),"requests",total,"errors5xx",errors,"errorRate",total==0?0d:(double)errors/total);}
    private void roll(){long now=System.currentTimeMillis()/300000;if(window!=now){window=now;total=0;errors=0;}}
}
