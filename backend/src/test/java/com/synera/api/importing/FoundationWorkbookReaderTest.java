package com.synera.api.importing;

import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

class FoundationWorkbookReaderTest {
    private final FoundationWorkbookReader reader=new FoundationWorkbookReader();
    @Test void allDownloadableTemplatesHaveSourceHeadersAndExampleRows()throws Exception {
        for(ImportKind kind:ImportKind.values()) {
            byte[] content=new ClassPathResource("import-templates/"+kind.name().toLowerCase(Locale.ROOT)+".xlsx").getContentAsByteArray();
            try(var workbook=WorkbookFactory.create(new java.io.ByteArrayInputStream(content))) {
                for(String name:kind.sheets) {
                    var sheet=workbook.getSheet(name);assertThat(sheet).isNotNull();
                    var headers=FoundationWorkbookReader.HEADERS.get(name);
                    for(int i=0;i<headers.size();i++)assertThat(sheet.getRow(0).getCell(i).getStringCellValue()).isEqualTo(headers.get(i));
                    assertThat(sheet.getRow(1)).isNotNull();
                    if(name.equals("outlets"))assertThat(sheet.getRow(1).getCell(8).getStringCellValue()).isEqualTo("0901 234 567");
                }
            }
            var parsed=reader.read(kind,new MockMultipartFile("file","template.xlsx","application/octet-stream",content));
            assertThat(parsed.rows()).hasSize(kind.sheets.size());
            assertThat(parsed.rows().stream().filter(row->row.sheet().equals("users")).allMatch(row->row.data().getOrDefault("password","").isBlank())).isTrue();
        }
    }
    @Test void csvHandlesBomQuotesCommasAndMultilineFields() {
        var file=new MockMultipartFile("file","source.csv","text/csv",("\uFEFFsku_code,name\r\nSKU-1,\"Tên, có dấu\nDòng hai\"\r\n").getBytes(StandardCharsets.UTF_8));
        var parsed=reader.read(ImportKind.PRODUCTS,file);
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().getFirst().data()).containsEntry("sku_code","SKU-1").containsEntry("name","Tên, có dấu\nDòng hai");
    }
    @Test void invalidCsvAndRepeatedHeadersAreRejected() {
        assertThatThrownBy(()->FoundationWorkbookReader.csv("sku_code,name\nA,\"broken")).hasMessageContaining("dấu nháy");
        assertThatThrownBy(()->reader.readRows("products",List.of(List.of("sku_code","sku_code"),List.of("A","B")))).hasMessageContaining("trùng tên");
    }
    @Test void normalizesCodesPhonesAndFindsFuzzyOutletDuplicates() {
        assertThat(ImportNormalization.code("  mã sku 01 ")).isEqualTo("MASKU01");
        assertThat(ImportNormalization.phone("0901 234 567")).isEqualTo("+84901234567");
        assertThat(ImportNormalization.phone("84 901 234 567")).isEqualTo("+84901234567");
        assertThatThrownBy(()->ImportNormalization.phone("901234567")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ImportNormalization.similarOutlet("Cửa hàng A","12 Đường B","Cua hang A","12 Duong B")).isTrue();
        assertThat(ImportNormalization.similarOutlet("Cửa hàng A","12 Đường B","Cửa hàng C","12 Đường B")).isFalse();
        assertThatThrownBy(()->ImportNormalization.integer("1.5","Số lượng",0)).isInstanceOf(IllegalArgumentException.class);
    }
}
