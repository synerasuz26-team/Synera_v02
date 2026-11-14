package com.synera.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class DeploymentEnvironmentGuardTest {
    static MockEnvironment production(){return new MockEnvironment().withProperty("APP_ENV","production").withProperty("DB_ENVIRONMENT","production")
        .withProperty("DB_URL","jdbc:postgresql://postgres.railway.internal:5432/synera").withProperty("RAILWAY_PROJECT_ID","test-project")
        .withProperty("DB_USER","test").withProperty("DB_PASSWORD","test-only").withProperty("JWT_SECRET","test-only-32-byte-secret-never-deploy-this")
        .withProperty("CORS_ORIGINS","https://frontend.example.invalid").withProperty("S3_BUCKET","test").withProperty("S3_REGION","auto");}
    @Test void acceptsPrivateProductionWithoutDemo(){assertThatCode(()->DeploymentEnvironmentGuard.validate(production())).doesNotThrowAnyException();}
    @Test void rejectsProductionMisconfigurationBeforeConnecting(){
        for(var pair:java.util.Map.of("SEED_DEMO","true","DB_ENVIRONMENT","dev","DB_URL","jdbc:postgresql://public.example.invalid/db","CORS_ORIGINS","*","JWT_SECRET","short","spring.flyway.enabled","false","app.http.require-https","false").entrySet())
            assertThatThrownBy(()->DeploymentEnvironmentGuard.validate(production().withProperty(pair.getKey(),pair.getValue()))).isInstanceOf(IllegalStateException.class);
        for(String origin:java.util.List.of("https://a.example.invalid/path","http://a.example.invalid","https://a.example.invalid,","https://*.example.invalid"))
            assertThatThrownBy(()->DeploymentEnvironmentGuard.validate(production().withProperty("CORS_ORIGINS",origin))).isInstanceOf(IllegalStateException.class);
    }
    @Test void keepsLocalDevelopmentAvailable(){assertThatCode(()->DeploymentEnvironmentGuard.validate(new MockEnvironment().withProperty("APP_ENV","dev"))).doesNotThrowAnyException();}
}
