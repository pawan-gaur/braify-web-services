package com.braify.feature.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns an uploaded document into simple, structure-preserving content the model can template:
 * Word (.docx) → basic HTML (headings, paragraphs, bold, lists, tables); PDF → text per page;
 * HTML → the body markup; plain text → as is. Dependency-free for .docx (zip + XML), PDFBox
 * (already on the classpath via openhtmltopdf) for PDF.
 *
 * <p>Throws {@link IllegalArgumentException} (→ 400) for unsupported, unreadable or empty files.
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    /** Consecutive list-item lines, wrapped into one {@code <ul>}. */
    private static final java.util.regex.Pattern LIST_RUN = java.util.regex.Pattern.compile("(?:<li>[^\n]*</li>\n)+");
    /** Cap on the uncompressed word/document.xml we read from a .docx (zip-bomb guard). */
    private static final long MAX_DOCX_XML_BYTES = 30L * 1024 * 1024;

    /** What came out of the file. {@code format} is HTML or TEXT. */
    public record Extracted(String type, String format, String content) {}

    public Extracted extract(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("The uploaded file is empty.");
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);

        Extracted out;
        if (name.endsWith(".docx"))                              out = new Extracted("DOCX", "HTML", docx(bytes));
        else if (name.endsWith(".pdf"))                          out = new Extracted("PDF", "TEXT", pdf(bytes));
        else if (name.endsWith(".html") || name.endsWith(".htm")) out = new Extracted("HTML", "HTML", html(bytes));
        else if (name.endsWith(".txt") || name.endsWith(".md"))  out = new Extracted("TEXT", "TEXT", new String(bytes, StandardCharsets.UTF_8));
        else if (name.endsWith(".doc"))
            throw new IllegalArgumentException("Old Word .doc files aren't supported. Save the file as .docx and upload it again.");
        else
            throw new IllegalArgumentException("Unsupported file type. Upload a .docx, .pdf, .html or .txt file.");

        if (out.content().isBlank() || stripTags(out.content()).isBlank()) {
            throw new IllegalArgumentException("PDF".equals(out.type())
                    ? "No text found in this PDF. Scanned PDFs (images of pages) aren't supported yet — upload the original Word file or a text-based PDF."
                    : "No text found in this document.");
        }
        return out;
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    private String pdf(byte[] bytes) {
        try (PDDocument doc = PDDocument.load(bytes)) {
            if (doc.isEncrypted()) throw new IllegalArgumentException("This PDF is password-protected. Remove the password and upload it again.");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder sb = new StringBuilder();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                sb.append("--- Page ").append(p).append(" ---\n").append(stripper.getText(doc).trim()).append("\n\n");
            }
            return sb.toString().trim();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            log.warn("PDF text extraction failed: {}", e.getMessage());
            throw new IllegalArgumentException("This PDF could not be read. It may be damaged.");
        }
    }

    // ── HTML ─────────────────────────────────────────────────────────────────

    private String html(byte[] bytes) {
        org.jsoup.nodes.Document d = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8));
        d.select("script, iframe, noscript").remove();
        return d.body() != null ? d.body().html() : d.html();
    }

    // ── DOCX ─────────────────────────────────────────────────────────────────

    private String docx(byte[] bytes) {
        byte[] xml = readZipEntry(bytes, "word/document.xml");
        if (xml == null) throw new IllegalArgumentException("This doesn't look like a valid Word (.docx) file.");
        org.w3c.dom.Document doc;
        try {
            doc = secureBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            log.warn("DOCX parse failed: {}", e.getMessage());
            throw new IllegalArgumentException("This Word file could not be read. It may be damaged.");
        }
        NodeList bodies = doc.getElementsByTagNameNS(W, "body");
        if (bodies.getLength() == 0) return "";
        StringBuilder sb = new StringBuilder();
        appendBlocks((Element) bodies.item(0), sb);
        return LIST_RUN.matcher(sb).replaceAll(r -> java.util.regex.Matcher.quoteReplacement("<ul>\n" + r.group() + "</ul>\n"));
    }

    /** Paragraphs and tables, in document order. */
    private void appendBlocks(Element parent, StringBuilder sb) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element el) || !W.equals(el.getNamespaceURI())) continue;
            switch (el.getLocalName()) {
                case "p"   -> appendParagraph(el, sb);
                case "tbl" -> appendTable(el, sb);
                case "sdt" -> {   // content controls wrap ordinary blocks
                    Element content = child(el, "sdtContent");
                    if (content != null) appendBlocks(content, sb);
                }
                default -> { }
            }
        }
    }

    private void appendParagraph(Element p, StringBuilder sb) {
        String inner = runs(p);
        Element pPr = child(p, "pPr");
        String style = "";
        boolean listItem = false;
        if (pPr != null) {
            Element ps = child(pPr, "pStyle");
            if (ps != null) style = ps.getAttributeNS(W, "val").toLowerCase(Locale.ROOT);
            listItem = child(pPr, "numPr") != null;
        }
        if (inner.isBlank()) { sb.append("<p>&nbsp;</p>\n"); return; }
        String tag = style.equals("title") ? "h1"
                : style.matches("heading1|heading 1") ? "h1"
                : style.matches("heading2|heading 2") ? "h2"
                : style.matches("heading[3-9]|heading [3-9]") ? "h3"
                : listItem ? "li" : "p";
        sb.append('<').append(tag).append('>').append(inner).append("</").append(tag).append(">\n");
    }

    /** Text runs with bold/italic/underline, tabs and line breaks. Hyperlink and field runs included. */
    private String runs(Element p) {
        StringBuilder sb = new StringBuilder();
        NodeList rs = p.getElementsByTagNameNS(W, "r");
        for (int i = 0; i < rs.getLength(); i++) {
            Element r = (Element) rs.item(i);
            StringBuilder text = new StringBuilder();
            for (Node n = r.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element el) || !W.equals(el.getNamespaceURI())) continue;
                switch (el.getLocalName()) {
                    case "t"       -> text.append(escape(el.getTextContent()));
                    case "tab"     -> text.append("&emsp;");
                    case "br", "cr" -> text.append("<br/>");
                    default -> { }
                }
            }
            if (text.length() == 0) continue;
            Element rPr = child(r, "rPr");
            String t = text.toString();
            if (rPr != null) {
                if (on(child(rPr, "u")))  t = "<u>" + t + "</u>";
                if (on(child(rPr, "i")))  t = "<em>" + t + "</em>";
                if (on(child(rPr, "b")))  t = "<strong>" + t + "</strong>";
            }
            sb.append(t);
        }
        return sb.toString();
    }

    private void appendTable(Element tbl, StringBuilder sb) {
        sb.append("<table>\n");
        for (Node r = tbl.getFirstChild(); r != null; r = r.getNextSibling()) {
            if (!(r instanceof Element tr) || !"tr".equals(tr.getLocalName())) continue;
            sb.append("<tr>");
            for (Node c = tr.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (!(c instanceof Element tc) || !"tc".equals(tc.getLocalName())) continue;
                StringBuilder cell = new StringBuilder();
                appendBlocks(tc, cell);
                sb.append("<td>").append(cell.toString().trim()).append("</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("</table>\n");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A w:b / w:i / w:u toggle counts as on unless its val says off. */
    private static boolean on(Element toggle) {
        if (toggle == null) return false;
        String v = toggle.getAttributeNS(W, "val");
        return v.isEmpty() || !(v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("none"));
    }

    private static Element child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && W.equals(el.getNamespaceURI()) && localName.equals(el.getLocalName())) return el;
        }
        return null;
    }

    private static byte[] readZipEntry(byte[] zip, String entryName) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (entryName.equals(e.getName())) return readCapped(zis);
            }
            return null;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_DOCX_XML_BYTES) throw new IllegalArgumentException("This Word file is too large to process.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** XML parser with DOCTYPE / external entities disabled (XXE-safe). */
    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);   // failures surface as exceptions, not stderr noise
        return b;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").trim();
    }
}
