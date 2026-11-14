package com.synera.api.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class HttpBoundaryFilter extends OncePerRequestFilter {
    private final boolean https,limits;
    private final int loginLimit,writeLimit,jsonLimit;
    private final long bodyLimit;
    private final Clock clock;
    private final HttpTraffic traffic;
    private final Map<String,Bucket> buckets=new HashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public HttpBoundaryFilter(Environment env,HttpTraffic traffic){this(env,traffic,Clock.systemUTC());}
    HttpBoundaryFilter(Environment env,HttpTraffic traffic,Clock clock){
        this.clock=clock;this.traffic=traffic;
        boolean production="production".equals(env.getProperty("APP_ENV"));
        https=env.getProperty("app.http.require-https",Boolean.class,production);
        limits=env.getProperty("app.http.rate-limit-enabled",Boolean.class,production);
        loginLimit=env.getProperty("app.http.login-per-minute",Integer.class,10);
        writeLimit=env.getProperty("app.http.writes-per-minute",Integer.class,120);
        jsonLimit=env.getProperty("app.http.max-json-bytes",Integer.class,262144);
        bodyLimit=env.getProperty("app.http.max-body-bytes",Long.class,6291456L);
        if(loginLimit<1||writeLimit<1||jsonLimit<1||bodyLimit<jsonLimit)throw new IllegalArgumentException("Invalid HTTP limits.");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String supplied=request.getHeader("X-Request-Id");
        String id=supplied!=null&&supplied.matches("[A-Za-z0-9_-]{8,80}")?supplied:UUID.randomUUID().toString();
        MDC.put("requestId",id);response.setHeader("X-Request-Id",id);
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Frame-Options","DENY");
        response.setHeader("Referrer-Policy","no-referrer");response.setHeader("Permissions-Policy","camera=(self), microphone=(), geolocation=(self)");
        response.setHeader("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'");
        if(request.isSecure())response.setHeader("Strict-Transport-Security","max-age=31536000");
        long started=System.nanoTime();boolean failed=false;
        try {
            String path=request.getRequestURI();
            boolean health=path.equals("/health")||path.equals("/actuator/health");
            if(https&&!health&&!request.isSecure()){reject(response,426,"HTTPS_REQUIRED");return;}
            boolean write=Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod())&&path.startsWith("/api/");
            if(write&&limits){
                boolean login=path.equals("/api/v1/auth/login")||path.equals("/api/auth/login");
                // Tomcat's trusted reverse-proxy configuration resolves remoteAddr. Never parse arbitrary headers here.
                if(!allow((login?"login:":"write:")+request.getRemoteAddr(),login?loginLimit:writeLimit)){
                    response.setHeader("Retry-After","60");reject(response,429,"RATE_LIMITED");return;
                }
            }
            if(write){
                if(request.getContentLengthLong()>bodyLimit){reject(response,413,"BODY_TOO_LARGE");return;}
                if(request.getContentType()!=null&&request.getContentType().toLowerCase(Locale.ROOT).contains("json")){
                    if(request.getContentLengthLong()>jsonLimit){reject(response,413,"BODY_TOO_LARGE");return;}
                    byte[] bytes=request.getInputStream().readNBytes(jsonLimit+1);
                    if(bytes.length>jsonLimit){reject(response,413,"BODY_TOO_LARGE");return;}
                    chain.doFilter(new BufferedRequest(request,bytes),response);return;
                }
            }
            chain.doFilter(request,response);
        }catch(ServletException|IOException|RuntimeException e){
            failed=true;
            LoggerFactory.getLogger(getClass()).error("http_exception method={} type={}",request.getMethod(),e.getClass().getSimpleName());
            throw e;
        }finally{
            if(request.getRequestURI().startsWith("/api/"))traffic.record(failed?500:response.getStatus());
            // Do not log URI/query/body/headers, user identifiers, IPs or exception messages.
            LoggerFactory.getLogger(getClass()).info("http_request method={} status={} durationMs={}",request.getMethod(),response.getStatus(),(System.nanoTime()-started)/1_000_000);
            MDC.remove("requestId");
        }
    }
    private synchronized boolean allow(String key,int limit){
        long minute=clock.millis()/60000;
        buckets.entrySet().removeIf(entry->entry.getValue().minute!=minute);
        Bucket bucket=buckets.get(key);
        if(bucket==null){if(buckets.size()>=4096)return false;bucket=new Bucket(minute);buckets.put(key,bucket);}
        return ++bucket.count<=limit;
    }
    private static class Bucket {final long minute;int count;Bucket(long minute){this.minute=minute;}}
    private static void reject(HttpServletResponse response,int code,String error)throws IOException{
        response.setStatus(code);response.setContentType("application/json");response.getWriter().write("{\"code\":\""+error+"\"}");
    }
    private static class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        BufferedRequest(HttpServletRequest request,byte[] bytes){super(request);this.bytes=bytes;}
        @Override public ServletInputStream getInputStream(){
            ByteArrayInputStream input=new ByteArrayInputStream(bytes);
            return new ServletInputStream(){
                public int read(){return input.read();}public int read(byte[] b,int off,int len){return input.read(b,off,len);}
                public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}
                public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous request body");}
            };
        }
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
    }
}
