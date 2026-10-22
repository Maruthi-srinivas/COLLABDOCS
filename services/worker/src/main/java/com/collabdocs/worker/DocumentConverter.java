package com.collabdocs.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.springframework.stereotype.Component;

@Component
public class DocumentConverter {
    private final ObjectMapper mapper;

    public DocumentConverter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] pdf(String html, String header, String footer, boolean pageNumbers) throws Exception {
        String document = wrap(html, header, footer, pageNumbers);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(xml(document), null);
        builder.toStream(out);
        builder.run();
        return out.toByteArray();
    }

    public byte[] html(String body) {
        return wrap(body, "", "", false).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] text(String accepted) {
        String value = accepted == null ? "" : accepted.replace("\n", "\n\n");
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] docx(String html, String header, String footer, boolean pageNumbers, Map<String, byte[]> images) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (header != null && !header.isBlank()) {
                document.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createRun().setText(header);
            }
            XWPFParagraph footerParagraph = document.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph();
            String footerText = (footer == null ? "" : footer) + (pageNumbers ? " Page numbers" : "");
            if (!footerText.isBlank()) footerParagraph.createRun().setText(footerText.trim());
            Document parsed = Jsoup.parse(html == null ? "" : html);
            for (Element child : parsed.body().children()) writeBlock(document, null, child, images);
            document.write(out);
            return out.toByteArray();
        }
    }

    public Imported importHtml(String html) {
        Document parsed = Jsoup.parse(html == null ? "" : html);
        List<String> dropped = new ArrayList<>();
        List<StoredImage> images = new ArrayList<>();
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "doc");
        ArrayNode content = root.putArray("content");
        for (Element child : parsed.body().children()) appendBlock(child, content, dropped, images, true);
        if (content.isEmpty()) content.add(paragraph(""));
        return new Imported(root, images, dropped);
    }

    public Imported importDocx(byte[] bytes) throws Exception {
        List<String> dropped = new ArrayList<>();
        dropped.add("DOCX comments and revisions were skipped");
        List<StoredImage> images = new ArrayList<>();
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "doc");
        ArrayNode content = root.putArray("content");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (org.apache.poi.xwpf.usermodel.IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) content.add(paragraphFromDocx(paragraph, images));
                else if (element instanceof XWPFTable table) content.add(tableFromDocx(table, images));
            }
        }
        if (content.isEmpty()) content.add(paragraph(""));
        return new Imported(root, images, dropped);
    }

    public String embedImages(String html, Map<String, byte[]> images, Map<String, String> types) {
        Document parsed = Jsoup.parse(html == null ? "" : html);
        for (Element image : parsed.select("img[data-attachment-id]")) {
            String id = image.attr("data-attachment-id");
            byte[] bytes = images.get(id);
            if (bytes == null) continue;
            String type = types.getOrDefault(id, "image/png");
            image.attr("src", "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes));
        }
        parsed.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false);
        return parsed.body().html();
    }

    private void writeBlock(XWPFDocument document, XWPFTableCell cell, Element element, Map<String, byte[]> images) {
        String name = element.normalName();
        if ("table".equals(name)) {
            XWPFTable table = document.createTable();
            boolean first = true;
            for (Element row : element.select("> tbody > tr, > tr")) {
                XWPFTableRow tableRow = first ? table.getRow(0) : table.createRow();
                first = false;
                int column = 0;
                for (Element item : row.select("> th, > td")) {
                    XWPFTableCell target = column == 0 && tableRow.getCell(0) != null ? tableRow.getCell(0) : tableRow.addNewTableCell();
                    target.removeParagraph(0);
                    for (Element child : item.children()) {
                        if (child.normalName().equals("table")) continue;
                        XWPFParagraph paragraph = target.addParagraph();
                        writeInline(paragraph, child, images);
                    }
                    if (target.getParagraphs().isEmpty()) target.addParagraph();
                    column += 1;
                }
            }
            return;
        }
        if ("ul".equals(name) || "ol".equals(name)) {
            for (Element item : element.select("> li")) {
                XWPFParagraph paragraph = cell == null ? document.createParagraph() : cell.addParagraph();
                paragraph.createRun().setText("• " + item.text());
            }
            return;
        }
        XWPFParagraph paragraph = cell == null ? document.createParagraph() : cell.addParagraph();
        if (name.startsWith("h") && name.length() == 2) paragraph.setStyle("Heading" + name.charAt(1));
        if ("div".equals(name) && element.hasClass("page-break")) {
            paragraph.setPageBreak(true);
            return;
        }
        writeInline(paragraph, element, images);
    }

    private void writeInline(XWPFParagraph paragraph, Element element, Map<String, byte[]> images) {
        if ("img".equals(element.normalName())) {
            byte[] bytes = images.get(element.attr("data-attachment-id"));
            if (bytes != null) {
                try {
                    paragraph.createRun().addPicture(new ByteArrayInputStream(bytes), pictureType(bytes), "image",
                            org.apache.poi.util.Units.toEMU(120), org.apache.poi.util.Units.toEMU(80));
                } catch (Exception ignored) {
                    paragraph.createRun().setText("[image]");
                }
            }
            return;
        }
        if (element.children().isEmpty()) {
            XWPFRun run = paragraph.createRun();
            run.setText(element.text());
            run.setBold("strong".equals(element.normalName()) || "b".equals(element.normalName()));
            return;
        }
        for (Node node : element.childNodes()) {
            if (node instanceof Element child) writeInline(paragraph, child, images);
            else {
                XWPFRun run = paragraph.createRun();
                run.setText(node.toString());
            }
        }
    }

    private ObjectNode paragraphFromDocx(XWPFParagraph paragraph, List<StoredImage> images) {
        String style = paragraph.getStyle() == null ? "" : paragraph.getStyle();
        boolean list = paragraph.getText() != null && paragraph.getText().startsWith("• ");
        ArrayNode content = mapper.createArrayNode();
        for (XWPFRun run : paragraph.getRuns()) {
            for (var picture : run.getEmbeddedPictures()) {
                if (images.size() >= 2000) break;
                byte[] data = picture.getPictureData().getData();
                String mime = AttachmentMime.sniff(data);
                if (mime == null) continue;
                UUID id = UUID.randomUUID();
                images.add(new StoredImage(id, data, mime, "Imported image"));
                content.add(image(id, "Imported image"));
            }
            if (run.text() == null || run.text().isBlank()) continue;
            ObjectNode text = mapper.createObjectNode();
            text.put("type", "text");
            text.put("text", run.text().startsWith("• ") ? run.text().substring(2) : run.text());
            ArrayNode marks = text.putArray("marks");
            if (run.isBold()) marks.add(mark("bold"));
            if (run.isItalic()) marks.add(mark("italic"));
            if (run.getUnderline() != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE) marks.add(mark("underline"));
            content.add(text);
        }
        if (style.toLowerCase(Locale.ROOT).contains("heading")) {
            ObjectNode heading = mapper.createObjectNode();
            heading.put("type", "heading");
            heading.putObject("attrs").put("level", 1);
            heading.set("content", content);
            return heading;
        }
        if (list) {
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "listItem");
            ArrayNode itemContent = item.putArray("content");
            ObjectNode paragraphNode = paragraphNode(content);
            itemContent.add(paragraphNode);
            ObjectNode listNode = mapper.createObjectNode();
            listNode.put("type", "bulletList");
            listNode.putArray("content").add(item);
            return listNode;
        }
        return paragraphNode(content);
    }

    private ObjectNode tableFromDocx(XWPFTable table, List<StoredImage> images) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "table");
        ArrayNode rows = node.putArray("content");
        for (XWPFTableRow row : table.getRows()) {
            ObjectNode rowNode = mapper.createObjectNode();
            rowNode.put("type", "tableRow");
            ArrayNode cells = rowNode.putArray("content");
            for (XWPFTableCell cell : row.getTableCells()) {
                ObjectNode cellNode = mapper.createObjectNode();
                cellNode.put("type", "tableCell");
                ArrayNode cellContent = cellNode.putArray("content");
                for (XWPFParagraph paragraph : cell.getParagraphs()) cellContent.add(paragraphFromDocx(paragraph, images));
                if (cellContent.isEmpty()) cellContent.add(paragraph(""));
                cells.add(cellNode);
            }
            rows.add(rowNode);
        }
        return node;
    }

    private void appendBlock(Element element, ArrayNode content, List<String> dropped, List<StoredImage> images, boolean top) {
        String name = element.normalName();
        if ("table".equals(name)) {
            content.add(tableFromHtml(element, dropped, images));
            return;
        }
        if ("ul".equals(name) || "ol".equals(name)) {
            ObjectNode list = mapper.createObjectNode();
            list.put("type", "ul".equals(name) ? "bulletList" : "orderedList");
            ArrayNode items = list.putArray("content");
            for (Element item : element.select("> li")) {
                ObjectNode listItem = mapper.createObjectNode();
                listItem.put("type", "listItem");
                ArrayNode itemContent = listItem.putArray("content");
                if (item.children().isEmpty()) itemContent.add(paragraph(item.text()));
                else {
                    ArrayNode nested = mapper.createArrayNode();
                    for (Element child : item.children()) appendBlock(child, nested, dropped, images, false);
                    if (nested.isEmpty()) itemContent.add(paragraph(item.text()));
                    else nested.forEach(itemContent::add);
                }
                items.add(listItem);
            }
            content.add(list);
            return;
        }
        if ("img".equals(name)) {
            addImage(element, content, dropped, images);
            return;
        }
        if (name.startsWith("h") && name.length() == 2 && Character.isDigit(name.charAt(1))) {
            ObjectNode heading = mapper.createObjectNode();
            heading.put("type", "heading");
            int level = Math.min(3, Math.max(1, name.charAt(1) - '0'));
            heading.putObject("attrs").put("level", level);
            heading.set("content", inline(element, dropped, images));
            content.add(heading);
            return;
        }
        if (!top && "div".equals(name)) {
            for (Element child : element.children()) appendBlock(child, content, dropped, images, false);
            return;
        }
        ObjectNode paragraph = mapper.createObjectNode();
        paragraph.put("type", "paragraph");
        paragraph.set("content", inline(element, dropped, images));
        content.add(paragraph);
    }

    private ObjectNode tableFromHtml(Element table, List<String> dropped, List<StoredImage> images) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "table");
        ArrayNode rows = node.putArray("content");
        for (Element row : table.select("> tbody > tr, > thead > tr, > tr")) {
            ObjectNode rowNode = mapper.createObjectNode();
            rowNode.put("type", "tableRow");
            ArrayNode cells = rowNode.putArray("content");
            for (Element cell : row.select("> th, > td")) {
                ObjectNode cellNode = mapper.createObjectNode();
                cellNode.put("type", cell.normalName().equals("th") ? "tableHeader" : "tableCell");
                ArrayNode cellContent = cellNode.putArray("content");
                Element nested = cell.selectFirst("table");
                if (nested != null) {
                    dropped.add("Nested table flattened");
                    for (Element child : cell.children()) {
                        if (!"table".equals(child.normalName())) appendBlock(child, cellContent, dropped, images, false);
                    }
                    if (cellContent.isEmpty()) cellContent.add(paragraph(""));
                } else if (cell.children().isEmpty()) {
                    cellContent.add(paragraph(cell.text()));
                } else {
                    for (Element child : cell.children()) appendBlock(child, cellContent, dropped, images, false);
                }
                cells.add(cellNode);
            }
            rows.add(rowNode);
        }
        return node;
    }

    private ArrayNode inline(Element element, List<String> dropped, List<StoredImage> images) {
        ArrayNode content = mapper.createArrayNode();
        for (Element image : element.select("img")) addImage(image, content, dropped, images);
        String text = element.text();
        if (!text.isBlank()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", "text");
            node.put("text", text);
            content.add(node);
        }
        return content;
    }

    private void addImage(Element element, ArrayNode content, List<String> dropped, List<StoredImage> images) {
        String src = element.attr("src");
        if (src.startsWith("http://") || src.startsWith("https://")) {
            dropped.add(src);
            return;
        }
        if (!src.startsWith("data:") || images.size() >= 2000) {
            if (images.size() >= 2000) dropped.add("Image limit reached");
            return;
        }
        try {
            String mime = src.substring(5, src.indexOf(';'));
            byte[] bytes = Base64.getDecoder().decode(src.substring(src.indexOf(',') + 1));
            String sniffed = AttachmentMime.sniff(bytes);
            if (sniffed == null) {
                dropped.add(src.substring(0, Math.min(src.length(), 40)));
                return;
            }
            UUID id = UUID.randomUUID();
            String alt = element.attr("alt");
            images.add(new StoredImage(id, bytes, sniffed, alt.isBlank() ? "Imported image" : alt));
            content.add(image(id, alt.isBlank() ? "Imported image" : alt));
        } catch (RuntimeException exception) {
            dropped.add("Unreadable image");
        }
    }

    private ObjectNode paragraph(String text) {
        ArrayNode content = mapper.createArrayNode();
        if (text != null && !text.isBlank()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", "text");
            node.put("text", text);
            content.add(node);
        }
        return paragraphNode(content);
    }

    private ObjectNode paragraphNode(ArrayNode content) {
        ObjectNode paragraph = mapper.createObjectNode();
        paragraph.put("type", "paragraph");
        paragraph.set("content", content);
        return paragraph;
    }

    private ObjectNode image(UUID id, String alt) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "image");
        ObjectNode attrs = node.putObject("attrs");
        attrs.put("attachmentId", id.toString());
        attrs.put("alt", alt);
        attrs.put("decorative", false);
        return node;
    }

    private ObjectNode mark(String type) {
        ObjectNode mark = mapper.createObjectNode();
        mark.put("type", type);
        return mark;
    }

    private static String wrap(String body, String header, String footer, boolean pageNumbers) {
        String footerText = (footer == null ? "" : escape(footer)) + (pageNumbers ? " <span>1</span>" : "");
        return "<html><head><style>@page{margin:18mm}.page-break{page-break-before:always}body{font-family:Arial,sans-serif}</style></head><body>"
                + "<div id=\"header\">" + escape(header) + "</div>" + (body == null ? "" : body)
                + "<div id=\"footer\">" + footerText + "</div></body></html>";
    }

    private static String xml(String html) {
        Document document = Jsoup.parse(html);
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false);
        return document.outerHtml();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int pictureType(byte[] bytes) {
        String mime = AttachmentMime.sniff(bytes);
        if ("image/jpeg".equals(mime)) return org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG;
        if ("image/gif".equals(mime)) return org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_GIF;
        return org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG;
    }

    public record StoredImage(UUID id, byte[] bytes, String mime, String alt) {}

    public record Imported(ObjectNode document, List<StoredImage> images, List<String> dropped) {}
}
