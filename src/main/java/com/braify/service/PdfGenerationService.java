package com.braify.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import com.braify.model.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfGenerationService {

    private final PlaceholderService placeholderService;

    public byte[] generate(Template template, Map<String, Object> data) throws Exception {
        // 1. Replace placeholders in HTML
        String html = placeholderService.replacePlaceholders(template.getHtmlContent(), data);

        // 2. Build the full XHTML document
        String fullHtml = buildHtmlDocument(html, template);

        log.debug("Generating PDF for template: {}", template.getId());

        // 3. Convert to PDF via OpenHTMLtoPDF
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useSVGDrawer(new BatikSVGDrawer());
            builder.withHtmlContent(fullHtml, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        }
    }

    private String buildHtmlDocument(String bodyHtml, Template template) {
        String css = template.getCssContent() != null ? template.getCssContent() : "";
        String pageSize = template.getPageSize() != null ? template.getPageSize() : "A4";
        String orientation = "landscape".equalsIgnoreCase(template.getOrientation())
                ? " landscape" : "";

        int mt = template.getMarginTop() != null ? template.getMarginTop() : 20;
        int mb = template.getMarginBottom() != null ? template.getMarginBottom() : 20;
        int ml = template.getMarginLeft() != null ? template.getMarginLeft() : 15;
        int mr = template.getMarginRight() != null ? template.getMarginRight() : 15;

        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8"/>
                <style>
                @page {
                  size: %s%s;
                  margin: %dmm %dmm %dmm %dmm;
                }
                * { box-sizing: border-box; }
                body {
                  font-family: Arial, Helvetica, sans-serif;
                  font-size: 12px;
                  margin: 0;
                  padding: 0;
                }
                table { border-collapse: collapse; width: 100%%; }
                th, td { border: 1px solid #ddd; padding: 6px 10px; }
                th { background-color: #f2f2f2; font-weight: bold; }
                img { max-width: 100%%; }
                .dynamic-field { color: inherit; }
                %s
                </style>
                </head>
                <body>
                %s
                </body>
                </html>
                """.formatted(pageSize, orientation, mt, mr, mb, ml, css, bodyHtml);
    }
}
