package other.xsy.myse.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PdfDecoder —— 把“整体 base64 包裹（或明文）的 query string，其 pdfContent 内嵌一个
 * 原始 PDF、且该 PDF 的二进制流用 base64 包裹”的数据，还原成可打开的 PDF，并解析出全部参数。
 *
 * 数据形态（支持以下两种）：
 *   A) 明文 query string：
 *      businessUnit=MySECn&documentType=P&documentKey=...&timestamp=...&pdfContent=%PDF-1.3...
 *   B) 整体 base64 包裹的 query string（即 base64(A)，常见于把整段数据塞进一个字段/文件）：
 *      WnVzaW5lc3M...（解码后即上面的 A）
 * 其中 pdfContent 的值 = 一个“流内容用 base64 包裹”的原始 PDF 文本（本数据集未做任何
 * URL 百分号编码，'%' 全部是 PDF 自身的注释行与二进制字面字节，故不再做 percentDecode，
 * 否则会把 %Devtype 之类误解码成 0xDE 破坏字体对象）。
 * 还原步骤：
 *   0) 若输入是整体 base64 包裹，先解开（自动识别）；
 *   1) 提取 pdfContent 值 -> 得到“流内容还是 base64”的 PDF 字节；
 *   2) 把每个 stream 块里的 base64 文本解码回原始字节（仅对“纯 base64 字符”的流解码，
 *      普通页面内容流保持原样），并同步修正 /Length -> 得到合法 PDF 字节；
 *   3) 重新扫描对象并重建 xref 交叉引用表，修正偏移，保证阅读器能直接打开。
 *
 * 设计：纯工具类（final + 私有构造），所有方法 static；
 *      纯 JDK，不使用 java.io.File，不依赖任何第三方 jar；兼容 JDK 8+。
 *
 * 典型用法：
 *   // 1) 整段 query string（含 pdfContent=...）或纯编码后的 PDF 字节
 *   byte[] out = PdfDecoder.decode(inputBytes);
 *
 *   // 2) 字符串输入（更方便，自动识别 base64 包裹）
 *   byte[] out = PdfDecoder.decode(queryString);
 *
 *   // 3) 流式：从 InputStream 读、写到 OutputStream（仍不碰 File）
 *   PdfDecoder.decode(System.in, System.out);
 *
 *   // 4) 解析 query string 里的“全部参数”（businessUnit/documentType/documentKey/...）
 *   PdfDecoder.ParsedInput r = PdfDecoder.decodeWithParams(queryString);
 *   String bu  = r.getBusinessUnit();   // "MySECn"
 *   String dt  = r.getDocumentType();   // "P"
 *   String dk  = r.getDocumentKey();    // "2003598901"
 *   String ts  = r.getTimestamp();      // "20120411:120000"
 *   Map<String,String> all = r.getParams();  // 所有 key=value
 *   byte[] pdf = r.getPdf();            // 还原后的 PDF 字节
 */
public final class PdfDecoder {

    private PdfDecoder() {
        throw new AssertionError("工具类不可实例化");
    }

    // ===================== 对外主入口 =====================

    /** 接受 query string / 整体 base64 包裹的 query / 纯编码 PDF 的字节，返回还原后的 PDF 字节。 */
    public static byte[] decode(byte[] input) throws IOException {
        byte[] unwrapped = maybeBase64Decode(input);
        byte[] pdf = extractPdfContent(unwrapped);
        byte[] decoded = rebuildStreams(pdf);
        byte[] withLen = fixLengths(decoded);
        return rebuildXref(withLen);
    }

    /** 字符串版入口（内部按 ISO-8859-1 处理，避免中文/编码干扰）。 */
    public static byte[] decode(String input) throws IOException {
        return decode(input.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** 流式入口：从 in 读、往 out 写，全程不使用 java.io.File。 */
    public static void decode(InputStream in, OutputStream out) throws IOException {
        out.write(decode(readAll(in)));
        out.flush();
    }

    /**
     * 仅从 query string 取出 pdfContent 原始字节（不做后续重建），便于单独调试。
     * 注意：本数据集 pdfContent 是原始 PDF 文本，未做百分号编码，故直接提取即可。
     */
    public static byte[] decodeQueryContent(String query) {
        return extractPdfContent(maybeBase64Decode(query.getBytes(StandardCharsets.ISO_8859_1)));
    }

    // ===================== 解析 query string 全部参数 =====================

    /**
     * 解析整段 query string（如 businessUnit=...&documentType=...&pdfContent=...），
     * 支持输入为“整体 base64 包裹”的 query string（会自动先解开）。
     * 返回全部 key=value 原始值（本数据集未做百分号编码，'%' 均为 PDF 注释/二进制，
     * 故不再 percentDecode，以免破坏内嵌 PDF）。
     * 注意：pdfContent 的值仍是“流被 base64 包裹”的原始 PDF 文本，
     *      其完整还原（base64 流解码）请使用 getPdf() / decode()。
     */
    public static Map<String, String> parseQuery(String query) {
        String u = tryUnwrapBase64(query);
        if (u != null) {
            query = u;
        }
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return map;
        }
        String lastKey = null;
        // 注意：pdfContent 的值是一个完整 PDF，内部可能含有未转义的 '&'，
        // 不能直接按 '&' 粗暴切分，否则值会被截断。规则：
        //   仅当“段首是合法标识符且后跟 '='”时才视为新的 key=value；
        //   否则（值内部含有 '&'）并入上一个参数的值。
        for (String pair : query.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            boolean newKey = false;
            String key = null;
            String encVal = "";
            if (eq > 0) {
                String maybeKey = pair.substring(0, eq);
                if (maybeKey.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    newKey = true;
                    key = maybeKey;
                    encVal = pair.substring(eq + 1);
                }
            }
            if (newKey) {
                // 本数据集的 query 中没有任何真正的百分号转义——'%' 全部是 PDF
                // 自身的注释行（%PDF/%Devtype 等）与二进制字面字节。做 percentDecode
                // 会把 %Devtype 里的 %De 误解码成 0xDE 从而破坏字体对象，故直接存
                // 原始值（query 已按 ISO-8859-1 为“一字符一字节”，存串无损）。
                map.put(key, encVal);
                lastKey = key;
            } else if (lastKey != null) {
                // 把这一段原样作为上一个值的一部分拼回去（值内含有未转义的 '&'）
                map.put(lastKey, map.get(lastKey) + "&" + pair);
            }
        }
        return map;
    }

    /**
     * 一站式：解析 query string 全部参数，并把 pdfContent 还原成可打开的 PDF。
     * 返回 ParsedInput，里面既有结构化参数，也有最终 PDF 字节。
     */
    public static ParsedInput decodeWithParams(String query) throws IOException {
        Map<String, String> params = parseQuery(query);
        // pdfContent 原始字节 —— 即“流还是 base64”的原始 PDF 文本（query 已按
        // ISO-8859-1 为“一字符一字节”，getBytes(ISO_8859_1) 可无损还原）
        String pc = params.get("pdfContent");
        byte[] pdfContentRaw = (pc != null)
                ? pc.getBytes(StandardCharsets.ISO_8859_1)
                : extractPdfContent(maybeBase64Decode(query.getBytes(StandardCharsets.ISO_8859_1)));
        // 完整还原
        byte[] pdf = decode(pdfContentRaw);
        return new ParsedInput(params, pdf, pdfContentRaw);
    }

    /** 解析结果容器：全部参数 + 还原后的 PDF 字节 + pdfContent 原始字节。 */
    public static final class ParsedInput {
        private final Map<String, String> params;
        private final byte[] pdf;            // 还原后的合法 PDF 字节
        private final byte[] pdfContentRaw;  // pdfContent 仅百分号解码后的字节

        ParsedInput(Map<String, String> params, byte[] pdf, byte[] pdfContentRaw) {
            this.params = params;
            this.pdf = pdf;
            this.pdfContentRaw = pdfContentRaw;
        }

        /** 全部参数（key=value，value 已百分号解码）。 */
        public Map<String, String> getParams() {
            return params;
        }

        /** 取任意参数（不存在返回 null）。 */
        public String getParam(String key) {
            return params.get(key);
        }

        public String getBusinessUnit() {
            return params.get("businessUnit");
        }

        public String getDocumentType() {
            return params.get("documentType");
        }

        public String getDocumentKey() {
            return params.get("documentKey");
        }

        public String getTimestamp() {
            return params.get("timestamp");
        }

        /** 还原后的合法 PDF 字节，可直接落盘为 .pdf。 */
        public byte[] getPdf() {
            return pdf;
        }

        /** pdfContent 原始字节（“流仍是 base64”的 PDF 文本，未经任何解码）。 */
        public byte[] getPdfContentRaw() {
            return pdfContentRaw;
        }

        /**
         * 从 query 参数里解析“文件名”：大小写不敏感匹配常见 key
         * （fileName/file_name/name/docname/documentname/title 等）。
         * 命中则做安全清洗并返回；未命中返回 null。
         */
        public String getFileName() {
            for (Map.Entry<String, String> e : params.entrySet()) {
                String k = e.getKey().toLowerCase();
                for (String fk : FILENAME_KEYS) {
                    if (k.equals(fk) || k.endsWith("_" + fk) || k.endsWith(fk + "_")) {
                        return sanitizeFileName(e.getValue());
                    }
                }
            }
            return null;
        }

        /**
         * 建议的文件名（解析不出显式文件名时使用）：
         *   1) query 中的文件名参数（清洗后）；
         *   2) 否则 documentKey + ".pdf"；
         *   3) 否则从 PDF 内部 /Title 提取 + ".pdf"；
         *   4) 兜底 "document.pdf"。
         * 返回的字符串已带 .pdf 扩展名（或原文件名自带扩展名时保持不变）。
         */
        public String getSuggestedFileName() {
            String fn = getFileName();
            if (fn != null) {
                return ensurePdfExt(fn);
            }
            String dk = getDocumentKey();
            if (dk != null && !dk.isEmpty()) {
                return ensurePdfExt(sanitizeFileName(dk));
            }
            String title = PdfDecoder.extractPdfTitle(pdf);
            if (title != null) {
                return ensurePdfExt(sanitizeFileName(title));
            }
            return "document.pdf";
        }

        /** 从解码后的 PDF 内部提取文档标题（/Info 的 /Title），best-effort，无则返回 null。 */
        public String getPdfTitle() {
            return PdfDecoder.extractPdfTitle(pdf);
        }
    }

    // ===================== 基础 IO（仅用流，不用 File） =====================

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    // ===================== 0. 自动解开“整体 base64 包裹”的底层 =====================

    /**
     * 探测输入是否为“整体 base64 包裹的 query string 或 PDF”。
     * 满足以下条件才认为是：① 字符集纯 base64（允许空白）；
     * ② 解码后内容看起来像我们的目标数据（含 businessUnit=/pdfContent=/documentType= 或开头是 %PDF）。
     * 命中则返回解码后的字符串，否则返回 null（交回原样处理）。
     */
    private static String tryUnwrapBase64(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.length() < 16) {
            return null;
        }
        // 纯 base64 字符集（含空白换行）才考虑，避免误伤普通 query string（含 & 等）
        if (!s.matches("[A-Za-z0-9+/=\\r\\n\\t ]+")) {
            return null;
        }
        try {
            byte[] d = Base64.getMimeDecoder().decode(s);
            String ds = new String(d, StandardCharsets.ISO_8859_1);
            if (ds.contains("businessUnit=") || ds.contains("pdfContent=")
                    || ds.contains("documentType=") || ds.startsWith("%PDF")) {
                return ds;
            }
        } catch (Exception e) {
            // 非法 base64，忽略
        }
        return null;
    }

    /** 字节版：若输入整体是 base64 包裹的 query/PDF，则解开；否则原样返回。 */
    public static byte[] maybeBase64Decode(byte[] in) {
        if (in == null) {
            return in;
        }
        String s = new String(in, StandardCharsets.ISO_8859_1);
        String u = tryUnwrapBase64(s);
        if (u != null) {
            return u.getBytes(StandardCharsets.ISO_8859_1);
        }
        return in;
    }

    // ===================== 1. 提取 pdfContent =====================

    public static byte[] extractPdfContent(byte[] in) {
        String s = new String(in, StandardCharsets.ISO_8859_1);
        int idx = s.indexOf("pdfContent=");
        if (idx < 0) {
            return in; // 整段已经是编码后的 PDF
        }
        int start = idx + "pdfContent=".length();
        int end = s.indexOf('&', start);
        if (end < 0) {
            end = s.length();
        }
        return s.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
    }

    // ===================== 2. 还原 stream 内的 base64 =====================

    static final byte[] KW_STREAM = "stream".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] KW_ENDSTREAM = "endstream".getBytes(StandardCharsets.ISO_8859_1);

    public static int indexOf(byte[] src, byte[] pat, int from) {
        int max = src.length - pat.length;
        for (int i = from; i <= max; i++) {
            boolean ok = true;
            for (int j = 0; j < pat.length; j++) {
                if (src[i + j] != pat[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return i;
            }
        }
        return -1;
    }

    public static byte[] rebuildStreams(byte[] pdf) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length);
        int pos = 0;
        while (true) {
            int sIdx = indexOf(pdf, KW_STREAM, pos);
            if (sIdx < 0) {
                out.write(pdf, pos, pdf.length - pos);
                break;
            }
            // 复制 "stream" 关键字本身
            out.write(pdf, pos, sIdx + KW_STREAM.length - pos);
            // 补一个 EOL（stream 关键字之后必须换行）
            out.write('\n');

            // stream 关键字后跳过原始 EOL（\r\n 或 \n）
            int dataStart = sIdx + KW_STREAM.length;
            if (dataStart < pdf.length && pdf[dataStart] == '\r') {
                dataStart++;
            }
            if (dataStart < pdf.length && pdf[dataStart] == '\n') {
                dataStart++;
            }

            int eIdx = indexOf(pdf, KW_ENDSTREAM, dataStart);
            if (eIdx < 0) {
                out.write(pdf, dataStart, pdf.length - dataStart);
                break;
            }

            // 取 dataStart..eIdx 之间的流数据，去掉尾部空白
            int dataEnd = eIdx;
            while (dataEnd > dataStart
                    && (pdf[dataEnd - 1] == ' ' || pdf[dataEnd - 1] == '\r'
                        || pdf[dataEnd - 1] == '\n' || pdf[dataEnd - 1] == '\t')) {
                dataEnd--;
            }
            byte[] data = new byte[dataEnd - dataStart];
            System.arraycopy(pdf, dataStart, data, 0, data.length);

            // 关键：并非所有流都被 base64 包裹。只有“流数据本身全是 base64 字符
            // （A-Za-z0-9+/= 及空白）”时才解码；含 < > / ( ) 等 PDF 语法字符的流
            // （如页面内容流）保持原样，否则会被误解码成乱码导致文件损坏。
            if (isPureBase64(data)) {
                byte[] raw = Base64.getMimeDecoder().decode(data);
                out.write(raw, 0, raw.length);
            } else {
                out.write(data, 0, data.length);
            }

            // 补一个 EOL（endstream 之前必须换行）
            out.write('\n');
            pos = eIdx; // 下一次循环会连 "endstream" 一起原样拷出
        }
        return out.toByteArray();
    }

    /** 判断一段流数据是否为“纯 base64（允许空白）”。含 < > / ( ) 等非 base64 字符则返回 false。 */
    private static boolean isPureBase64(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        int nonWs = 0;
        for (byte b : data) {
            int c = b & 0xFF;
            if (c == ' ' || c == '\r' || c == '\n' || c == '\t') {
                continue;
            }
            nonWs++;
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!ok) {
                return false;
            }
        }
        return nonWs > 0;
    }

    // ===================== 4. 把 /Length 同步为解码后的真实长度 =====================

    public static byte[] fixLengths(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);

        // 收集需要修改的 /Length（直接写在字典里 / 或间接引用某个对象）
        List<int[]> direct = new ArrayList<>();      // {charStart, charEnd, newLen}
        List<String[]> indirect = new ArrayList<>(); // {lengthObjNum, newLen}

        Matcher m = Pattern.compile("(\\d+) 0 obj").matcher(s);
        while (m.find()) {
            int objStart = m.start();
            int streamStart = s.indexOf("stream", m.end());
            if (streamStart < 0) {
                continue; // 该对象没有流
            }
            String dict = s.substring(objStart, streamStart);

            // 计算该流解码后的真实数据长度
            int ds = streamStart + "stream".length();
            if (ds < s.length() && s.charAt(ds) == '\r') {
                ds++;
            }
            if (ds < s.length() && s.charAt(ds) == '\n') {
                ds++;
            }
            int es = s.indexOf("endstream", ds);
            if (es < 0) {
                continue;
            }
            int de = es;
            while (de > ds && (s.charAt(de - 1) == ' ' || s.charAt(de - 1) == '\r'
                    || s.charAt(de - 1) == '\n' || s.charAt(de - 1) == '\t')) {
                de--;
            }
            int dataLen = de - ds;

            Matcher lm = Pattern.compile("/Length\\s+(\\d+)\\s+0\\s*R").matcher(dict);
            if (lm.find()) {
                indirect.add(new String[]{lm.group(1), String.valueOf(dataLen)});
            } else {
                Matcher lm2 = Pattern.compile("/Length\\s+(\\d+)").matcher(dict);
                if (lm2.find()) {
                    direct.add(new int[]{objStart + lm2.start(1), objStart + lm2.end(1), dataLen});
                }
            }
        }

        // 先应用直接写在字典里的 /Length（从后往前改，避免下标错位）
        direct.sort((a, b) -> Integer.compare(b[0], a[0]));
        StringBuilder sb = new StringBuilder(s);
        for (int[] d : direct) {
            sb.replace(d[0], d[1], String.valueOf(d[2]));
        }
        s = sb.toString();

        // 再应用间接引用的 /Length 对象
        for (String[] it : indirect) {
            String header = it[0] + " 0 obj";
            int idx = s.indexOf(header);
            if (idx < 0) {
                continue;
            }
            int vstart = idx + header.length();
            int vend = s.indexOf("endobj", vstart);
            if (vend < 0) {
                continue;
            }
            int p = vstart;
            while (p < vend && !Character.isDigit(s.charAt(p))) {
                p++;
            }
            int q = p;
            while (q < vend && Character.isDigit(s.charAt(q))) {
                q++;
            }
            if (p < q) {
                s = s.substring(0, p) + it[1] + s.substring(q);
            }
        }

        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    // ===================== 5. 重建 xref 交叉引用表 =====================

    public static byte[] rebuildXref(byte[] pdf) {
        String text = new String(pdf, StandardCharsets.ISO_8859_1);

        // 扫描所有 "N G obj"，记录对象号与偏移
        Pattern p = Pattern.compile("(?m)^(\\d+)\\s+\\d+\\s+obj");
        Matcher m = p.matcher(text);
        List<int[]> objs = new ArrayList<>();
        while (m.find()) {
            int num = Integer.parseInt(m.group(1));
            objs.add(new int[]{num, m.start()});
        }
        objs.sort((a, b) -> Integer.compare(a[0], b[0]));

        int max = 0;
        for (int[] o : objs) {
            if (o[0] > max) {
                max = o[0];
            }
        }

        int xrefPos = text.indexOf("xref");
        int trailerPos = text.indexOf("trailer");
        if (xrefPos < 0 || trailerPos < 0) {
            return pdf; // 没有 xref，原样返回（让阅读器自行修复）
        }

        // 构造新的 xref 表
        StringBuilder xref = new StringBuilder();
        xref.append("xref\n0 ").append(max + 1).append('\n');
        xref.append("0000000000 65535 f \n"); // 对象 0 永远空闲
        int[] offsets = new int[max + 1];
        boolean[] present = new boolean[max + 1];
        for (int[] o : objs) {
            offsets[o[0]] = o[1];
            present[o[0]] = true;
        }
        for (int n = 1; n <= max; n++) {
            if (present[n]) {
                xref.append(String.format("%010d 00000 n \n", offsets[n]));
            } else {
                xref.append("0000000000 65535 f \n");
            }
        }

        String before = text.substring(0, xrefPos);
        String after = text.substring(trailerPos);
        // 修正 trailer 里的 /Size
        after = after.replaceFirst("/Size\\s+\\d+", "/Size " + (max + 1));

        String rebuilt = before + xref.toString() + after;

        // 修正 startxref 指向新的 xref 位置
        rebuilt = fixStartXref(rebuilt, before.length());

        return rebuilt.getBytes(StandardCharsets.ISO_8859_1);
    }

    // ===================== 文件名解析辅助 =====================

    /** 常见的“文件名”参数 key（大小写不敏感匹配）。 */
    private static final String[] FILENAME_KEYS = {
            "filename", "file_name", "filename", "name",
            "docname", "doc_name", "documentname", "document_name", "title"
    };

    /** 清洗文件名：去掉路径分隔符与非法字符，限制长度，避免落盘时被当成路径。 */
    public static String sanitizeFileName(String name) {
        if (name == null) {
            return null;
        }
        String s = name.trim();
        // 去掉任何路径成分，只保留最后一段
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        // 替换 Windows / 通用非法字符
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (s.isEmpty()) {
            return null;
        }
        // 限制长度，避免过长
        if (s.length() > 200) {
            s = s.substring(0, 200).trim();
        }
        return s;
    }

    /** 确保文件名带 .pdf 扩展名（大小写不敏感判断是否已带）。 */
    public static String ensurePdfExt(String name) {
        if (name == null) {
            return null;
        }
        if (name.toLowerCase().endsWith(".pdf")) {
            return name;
        }
        return name + ".pdf";
    }

    /**
     * 从解码后的合法 PDF 字节里 best-effort 提取文档标题（/Info 字典的 /Title）。
     * 仅做字面串 (...) 提取并反转义，匹配不到返回 null。
     */
    public static String extractPdfTitle(byte[] pdf) {
        if (pdf == null) {
            return null;
        }
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        // 1) 字面串 (...)
        Matcher m = Pattern.compile("/Title\\s*\\(((?:\\\\.|[^()\\\\])*)\\)").matcher(s);
        if (m.find()) {
            String raw = m.group(1);
            // 反转义 \( \) \\ 以及常见的 \n \r \t
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '\\' && i + 1 < raw.length()) {
                    char n = raw.charAt(++i);
                    switch (n) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        default:  sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            String title = sb.toString().trim();
            if (!title.isEmpty()) {
                return title;
            }
        }
        // 2) 十六进制串 <...>（常见用于非 ASCII 标题）
        Matcher h = Pattern.compile("/Title\\s*<([0-9A-Fa-f\\s]+)>").matcher(s);
        if (h.find()) {
            String hex = h.group(1).replaceAll("\\s+", "");
            if ((hex.length() % 2) == 0) {
                try {
                    byte[] b = new byte[hex.length() / 2];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
                    }
                    String title = new String(b, StandardCharsets.UTF_8).trim();
                    return title.isEmpty() ? null : title;
                } catch (Exception e) {
                    // 解析失败则忽略，继续往下
                }
            }
        }
        return null;
    }

    public static String fixStartXref(String text, int xrefPos) {
        int idx = text.lastIndexOf("startxref");
        if (idx < 0) {
            return text;
        }
        int numStart = text.indexOf('\n', idx);
        if (numStart < 0) {
            return text;
        }
        numStart++; // 跳过换行
        int numEnd = text.indexOf('\n', numStart);
        if (numEnd < 0) {
            numEnd = text.length();
        }
        String tail = (numEnd + 1 <= text.length()) ? text.substring(numEnd + 1) : "";
        return text.substring(0, numStart) + xrefPos + "\n" + tail;
    }

    // ===================== 命令行入口（保留，方便直接跑） =====================

    public static void main(String[] args) throws IOException {
        // 读标准输入（不使用 java.io.File）
        byte[] raw = readAll(System.in);
        String text = new String(raw, StandardCharsets.ISO_8859_1);

        // 自动识别：整体 base64 包裹的 query string / 纯 query string
        String unwrapped = tryUnwrapBase64(text);
        if (unwrapped != null || text.contains("businessUnit=") || text.contains("pdfContent=")) {
            String q = (unwrapped != null) ? unwrapped : text;
            Map<String, String> params = parseQuery(q);
            System.err.println("=== 解析到的参数 ===");
            for (Map.Entry<String, String> e : params.entrySet()) {
                String v = e.getValue();
                if (v != null && v.length() > 200) {
                    v = v.substring(0, 200) + "...(已截断)";
                }
                System.err.println(e.getKey() + " = " + v);
            }
            ParsedInput r = decodeWithParams(q);
            System.err.println("=== 建议文件名 ===");
            System.err.println(r.getSuggestedFileName());
            System.err.println("=== 还原 PDF 字节数 ===");
            System.err.println(r.getPdf().length);
            System.out.write(r.getPdf());
        } else {
            // 既不是 query 也不是 base64 包裹的 query：按纯编码 PDF 处理
            System.out.write(decode(raw));
        }
        System.out.flush();
    }
}
