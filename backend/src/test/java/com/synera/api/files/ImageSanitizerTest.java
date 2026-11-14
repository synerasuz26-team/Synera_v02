package com.synera.api.files;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;

class ImageSanitizerTest {
    @Test void rejectsOversizedDimensionsBeforeDecodingPixels()throws Exception{
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16385,1,BufferedImage.TYPE_INT_RGB),"png",output);
        assertThatThrownBy(()->ImageSanitizer.sanitize(output.toByteArray(),"image/png",1000,256)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Kích thước");
    }
    @Test void rejectsFakeImageAndMimeMismatch()throws Exception{
        assertThatThrownBy(()->ImageSanitizer.sanitize("not an image".getBytes(),"image/png",1000,256)).isInstanceOf(IllegalArgumentException.class);
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",output);
        assertThatThrownBy(()->ImageSanitizer.sanitize(output.toByteArray(),"image/jpeg",1000,256)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void boundsBothVariantsAndReencodesAsJpeg()throws Exception{
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(1200,600,BufferedImage.TYPE_INT_ARGB),"png",output);
        var result=ImageSanitizer.sanitize(output.toByteArray(),"image/png",800,200);
        var image=ImageIO.read(new ByteArrayInputStream(result.image()));var thumbnail=ImageIO.read(new ByteArrayInputStream(result.thumbnail()));
        assertThat(image.getWidth()).isEqualTo(800);assertThat(image.getHeight()).isEqualTo(400);assertThat(thumbnail.getWidth()).isEqualTo(200);
        assertThat(result.image()[0]).isEqualTo((byte)0xff);assertThat(result.image()[1]).isEqualTo((byte)0xd8);
    }
    @Test void capsPrimaryImageAt1280PixelsAnd200Kilobytes()throws Exception{
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2000,1000,BufferedImage.TYPE_INT_RGB),"png",output);
        var result=ImageSanitizer.sanitize(output.toByteArray(),"image/png",2048,512);
        var image=ImageIO.read(new ByteArrayInputStream(result.image()));
        assertThat(Math.max(image.getWidth(),image.getHeight())).isLessThanOrEqualTo(1280);
        assertThat(result.image().length).isLessThanOrEqualTo(200_000);
    }
}
