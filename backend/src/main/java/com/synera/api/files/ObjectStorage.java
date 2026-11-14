package com.synera.api.files;

import java.time.Duration;
import java.util.Map;

public interface ObjectStorage {
    UploadUrl uploadUrl(String key,String contentType,long size,Duration expiry);
    String downloadUrl(String key,Duration expiry);
    byte[] read(String key,long expectedSize,int maxBytes);
    void write(String key,byte[] bytes,String contentType);
    record UploadUrl(String url,Map<String,String> headers){}
}
