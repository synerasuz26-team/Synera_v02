package com.synera.api.files;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;

/** Decode only bounded JPEG/PNG, strip metadata, then re-encode both public variants. */
public final class ImageSanitizer {
    private static final int MAX_OUTPUT_BYTES = 200_000;

    private ImageSanitizer(){}
    public static Images sanitize(byte[] bytes,String declared,int maxEdge,int thumbnailEdge){
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new IllegalArgumentException("Tệp không phải ảnh JPEG/PNG hợp lệ.");
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);String format=reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if(!(declared.equals("image/jpeg")&&java.util.List.of("jpeg","jpg").contains(format))&&!(declared.equals("image/png")&&format.equals("png")))throw new IllegalArgumentException("Nội dung ảnh không khớp định dạng khai báo.");
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1||h<1||w>16384||h>16384||(long)w*h>20_000_000)throw new IllegalArgumentException("Kích thước ảnh quá lớn (tối đa 20 triệu pixel).");
                BufferedImage source=reader.read(0);
                byte[] image=jpeg(source,Math.min(1280,maxEdge),true);
                return new Images(image,jpeg(source,Math.min(256,thumbnailEdge),false));
            }finally{reader.dispose();}
        }catch(IllegalArgumentException e){throw e;}catch(IOException|RuntimeException e){throw new IllegalArgumentException("Không thể xử lý ảnh.",e);}
    }
    private static byte[] jpeg(BufferedImage source,int edge,boolean enforceSize)throws IOException{
        double scale=Math.min(1d,(double)edge/Math.max(source.getWidth(),source.getHeight()));
        BufferedImage target=resize(source,scale);
        for(float quality:new float[]{0.70f,0.60f,0.50f,0.40f}){
            byte[] encoded=encode(target,quality);
            if(!enforceSize||encoded.length<=MAX_OUTPUT_BYTES)return encoded;
        }
        while(Math.max(target.getWidth(),target.getHeight())>480){
            target=resize(target,0.85d);
            byte[] encoded=encode(target,0.50f);
            if(encoded.length<=MAX_OUTPUT_BYTES)return encoded;
        }
        throw new IllegalArgumentException("Ảnh sau nén vượt quá 200 KB. Hãy chọn ảnh nhỏ hơn.");
    }

    private static BufferedImage resize(BufferedImage source,double scale){
        var target=new BufferedImage(Math.max(1,(int)(source.getWidth()*scale)),Math.max(1,(int)(source.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);
        var graphics=target.createGraphics();try{graphics.setColor(Color.WHITE);graphics.fillRect(0,0,target.getWidth(),target.getHeight());graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);graphics.drawImage(source,0,0,target.getWidth(),target.getHeight(),null);}finally{graphics.dispose();}
        return target;
    }

    private static byte[] encode(BufferedImage image,float quality)throws IOException{
        var writers=ImageIO.getImageWritersByFormatName("jpeg");
        if(!writers.hasNext())throw new IOException("JPEG encoder is unavailable.");
        var writer=writers.next();
        try(var output=new ByteArrayOutputStream();ImageOutputStream stream=ImageIO.createImageOutputStream(output)){
            writer.setOutput(stream);ImageWriteParam parameters=writer.getDefaultWriteParam();
            if(parameters.canWriteCompressed()){parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);parameters.setCompressionQuality(quality);}
            writer.write(null,new javax.imageio.IIOImage(image,null,null),parameters);stream.flush();return output.toByteArray();
        }finally{writer.dispose();}
    }
    public record Images(byte[] image,byte[] thumbnail){}
}
