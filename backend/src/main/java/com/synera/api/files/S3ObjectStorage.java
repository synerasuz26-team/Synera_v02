package com.synera.api.files;

import com.synera.api.auth.ApiProblem;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

@Component
public class S3ObjectStorage implements ObjectStorage,AutoCloseable {
    private final Environment env;private S3Client s3;private S3Presigner signer;private String bucket;
    public S3ObjectStorage(Environment env){this.env=env;}
    private synchronized void initialize(){
        if(s3!=null)return;
        bucket=env.getProperty("S3_BUCKET","");String region=env.getProperty("S3_REGION",""),endpoint=env.getProperty("S3_ENDPOINT","");
        if(bucket.isBlank()||region.isBlank())throw new ApiProblem("STORAGE_NOT_CONFIGURED","Kho ảnh chưa được cấu hình.",HttpStatus.SERVICE_UNAVAILABLE);
        var config=S3Configuration.builder().pathStyleAccessEnabled(env.getProperty("S3_PATH_STYLE",Boolean.class,false)).build();
        var credentials=DefaultCredentialsProvider.builder().build();
        String key=env.getProperty("S3_ACCESS_KEY_ID",""),secret=env.getProperty("S3_SECRET_ACCESS_KEY","");
        AwsCredentialsProvider provider=key.isBlank()?credentials:StaticCredentialsProvider.create(AwsBasicCredentials.create(key,secret));
        var client=S3Client.builder().region(Region.of(region)).credentialsProvider(provider).serviceConfiguration(config)
            .requestChecksumCalculation(software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(software.amazon.awssdk.core.checksums.ResponseChecksumValidation.WHEN_REQUIRED)
            .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(15)));
        var presigner=S3Presigner.builder().region(Region.of(region)).credentialsProvider(provider).serviceConfiguration(config);
        if(!endpoint.isBlank()){
            URI uri=URI.create(endpoint);
            if(!"https".equals(uri.getScheme())&&!("http".equals(uri.getScheme())&&java.util.List.of("127.0.0.1","localhost").contains(uri.getHost())))throw new IllegalArgumentException("S3 endpoint phải dùng HTTPS, trừ kiểm thử localhost.");
            client.endpointOverride(uri);presigner.endpointOverride(uri);
        }
        signer=presigner.build();s3=client.build();
    }
    public UploadUrl uploadUrl(String key,String type,long size,Duration expiry){
        initialize();
        var request=PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).contentLength(size).build();
        var signed=signer.presignPutObject(p->p.signatureDuration(expiry).putObjectRequest(request));
        var headers=new java.util.LinkedHashMap<String,String>();
        signed.signedHeaders().forEach((name,values)->{if(!name.equalsIgnoreCase("host")&&!name.equalsIgnoreCase("content-length"))headers.put(name,String.join(",",values));});
        return new UploadUrl(signed.url().toString(),headers);
    }
    public String downloadUrl(String key,Duration expiry){initialize();return signer.presignGetObject(p->p.signatureDuration(expiry).getObjectRequest(r->r.bucket(bucket).key(key))).url().toString();}
    public byte[] read(String key,long expected,int max){
        initialize();
        try {
            var head=s3.headObject(r->r.bucket(bucket).key(key));
            if(head.contentLength()!=expected||expected>max)throw new IllegalArgumentException("Dung lượng tệp không khớp khai báo hoặc vượt giới hạn.");
            try(var stream=s3.getObject(r->r.bucket(bucket).key(key))){
                byte[] bytes=stream.readNBytes(max+1);
                if(bytes.length!=expected||bytes.length>max)throw new IllegalArgumentException("Dung lượng ảnh không hợp lệ.");
                return bytes;
            }
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new ApiProblem("STORAGE_READ_FAILED","Không thể đọc ảnh đã tải lên. Hãy thử lại.",HttpStatus.BAD_GATEWAY);}
    }
    public void write(String key,byte[] bytes,String type){initialize();try{s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).build(),RequestBody.fromBytes(bytes));}catch(software.amazon.awssdk.core.exception.SdkException e){throw new ApiProblem("STORAGE_WRITE_FAILED","Kho ảnh tạm thời không nhận được ảnh. Hãy thử lại.",HttpStatus.BAD_GATEWAY);}}
    @Override public synchronized void close(){if(s3!=null)s3.close();if(signer!=null)signer.close();}
}
