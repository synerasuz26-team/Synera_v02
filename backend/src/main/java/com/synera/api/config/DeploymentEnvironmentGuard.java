package com.synera.api.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Fails before opening the DB, migrating, or binding the HTTP port. Never prints values. */
public class DeploymentEnvironmentGuard implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        validate(env);
    }
    public static void validate(Environment env) {
        String target=env.getProperty("APP_ENV", "dev");
        boolean production="production".equals(target);
        if(Arrays.asList(env.getActiveProfiles()).contains("production")&&!production)
            fail("Production profile requires APP_ENV=production.");
        if(!production&&!"staging".equals(target))return;
        if(!target.equals(env.getProperty("DB_ENVIRONMENT")))fail("APP_ENV and DB_ENVIRONMENT must match.");
        String url=env.getProperty("DB_URL", "");
        if(!url.startsWith("jdbc:postgresql://"))fail("DB_URL must be a PostgreSQL JDBC URL.");
        URI database;
        try { database=URI.create(url.substring(5)); } catch(Exception e){throw new IllegalStateException("Invalid DB_URL.");}
        if(database.getUserInfo()!=null||database.getHost()==null)fail("Use DB_USER/DB_PASSWORD separately from DB_URL.");
        if(env.containsProperty("RAILWAY_PROJECT_ID")&&!database.getHost().endsWith(".railway.internal"))
            fail("Railway staging/production requires the private database hostname.");
        if(!production)return;
        if(env.getProperty("SEED_DEMO",Boolean.class,false))fail("SEED_DEMO is forbidden in production.");
        if(env.containsProperty("app.task"))fail("Database CLI seed/reset tasks are forbidden in production; migrations run at startup.");
        String secret=env.getProperty("JWT_SECRET",env.getProperty("AUTH_JWT_SECRET",""));
        if(secret.getBytes(StandardCharsets.UTF_8).length<32||secret.contains("<")||secret.toLowerCase().contains("change-me"))
            fail("Set a unique production JWT_SECRET of at least 32 bytes.");
        String origins=env.getProperty("CORS_ORIGINS",env.getProperty("APP_CORS_ALLOWED_ORIGINS",""));
        if(origins.isBlank())fail("Explicit HTTPS CORS_ORIGINS are required.");
        for(String origin:origins.split(",",-1)){
            try {
                URI uri=URI.create(origin.trim());
                if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().isEmpty()||origin.contains("*"))
                    fail("CORS_ORIGINS must contain exact HTTPS origins without paths or wildcards.");
            }catch(IllegalArgumentException e){fail("Invalid CORS_ORIGINS.");}
        }
        for(String key:java.util.List.of("DB_USER","DB_PASSWORD"))
            if(env.getProperty(key,"").isBlank())fail("Missing production variable: "+key);
        if(env.getProperty("FEATURE_PHOTOS",Boolean.class,false))
            for(String key:java.util.List.of("S3_REGION","S3_BUCKET","S3_ACCESS_KEY_ID","S3_SECRET_ACCESS_KEY"))
                if(env.getProperty(key,"").isBlank())fail("FEATURE_PHOTOS requires production variable: "+key);
        if(!env.getProperty("app.http.require-https",Boolean.class,true))fail("HTTPS enforcement cannot be disabled in production.");
        if(env.getProperty("spring.flyway.enabled",Boolean.class,true)==false||env.getProperty("spring.flyway.clean-disabled",Boolean.class,true)==false)
            fail("Production requires Flyway migration and forbids Flyway clean.");
    }
    private static void fail(String message){throw new IllegalStateException(message);}
}
