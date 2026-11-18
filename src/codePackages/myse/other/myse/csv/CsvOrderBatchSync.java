package other.myse.csv;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.*;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 CSV 高性能导入销售易 order__c。
 *
 * 运行逻辑：
 * 1. 第一遍流式读取 CSV，只收集账户编号和报价单号；
 * 2. 批量预取 account/quote__c ID，以及 order__c 的 entityType 和单选 value；
 * 3. 第二遍流式读取 CSV，组装 CRM 要求的数据类型，按最多 1000 条调用 batch 接口。
 *
 * 直接运行 main 默认执行完整导入：
 *   - CSV：DEFAULT_CSV_PATH
 *   - Token：DEFAULT_TOKEN_FILE
 *   - operate：insert
 *   - limit：0（全部）
 *
 * 如需测试，可给 main 传参：
 *   new String[]{CSV_PATH, "--execute", "--limit", "10", "--operate", "insert"}
 */
public class CsvOrderBatchSync {
    private static final String DEFAULT_GATEWAY = "https://crm-sandbox.xiaoshouyi.com";
    private static final String DEFAULT_TOKEN_FILE = "D:\\销售易\\myse-paas\\.neo-cli\\token.json";
    private static final String DEFAULT_CSV_PATH = "D:\\xwechat_files\\wxid_irr4f0y6wqj722_e018\\msg\\file\\2026-09\\QAS_Order_202609151721_2.csv";

    private static final String BATCH_PATH = "/rest/data/v2.0/scripts/api/custom/batch";
    private static final String XOQL_PATH = "/rest/data/v2.0/query/xoql";
    private static final String BUSI_TYPE_PATH = "/rest/data/v2.0/xobjects/order__c/busiType";
    private static final String FIELD_META_PATH = "/rest/metadata/v2.0/xobjects/order__c/items";
    private static final String ENTITY = "order__c";
    private static final int MAX_BATCH_SIZE = 500;
    private static final int QUERY_CHUNK_SIZE = 250;

    private static final LinkedHashMap<String, String> FIELD_MAP = new LinkedHashMap<String, String>();
    static {
        FIELD_MAP.put("ORDER_NUMBER", "orderNumberAutoGenerate__c");
        FIELD_MAP.put("ORDER_STATUS", "orderStatus__c");
        FIELD_MAP.put("PURCHASER_ID", "purchaseId__c");
        FIELD_MAP.put("PO_NUMBER", "purchaseOrder__c");
        FIELD_MAP.put("PROJECT_NAME", "projectName__c");
        FIELD_MAP.put("PMS_ID", "pmsId__c");
        FIELD_MAP.put("TOTAL_AMOUNT_WITH_TAX", "totalPrice__c");
        FIELD_MAP.put("ENTERING_USER_NAME", "userName__c");
        FIELD_MAP.put("REQUESTED_DELIVERY_DATE", "requireDeliverydate__c");
        FIELD_MAP.put("SHIP_ADDRESS1", "shipToAddress__c");
        FIELD_MAP.put("CREDIT_STATUS", "crediStatus__c");
        FIELD_MAP.put("QUOTATION_NUMBER", "quotation__c");
        FIELD_MAP.put("CUSTOMER_ID", "customerId__c");
        FIELD_MAP.put("RESP_SALES_TERRITORY", "respSalesTerritory__c");
    }

    public static void main(String[] args) throws Exception {
        String[] runArgs = args != null && args.length > 0 ? args : new String[] {
                DEFAULT_CSV_PATH,
                "--execute",
                "--limit", "0",
                "--operate", "insert"
        };
        run(runArgs);
    }

    private static void run(String[] args) throws Exception {
        Options opt = Options.parse(args);
        if (opt.csvPath == null) opt.csvPath = DEFAULT_CSV_PATH;
        String token = readAccessToken(opt.tokenFile);

        final LookupKeys keys = new LookupKeys();
        forEachCsvRow(opt.csvPath, new RowConsumer() {
            public void accept(int lineNo, Map<String, String> row) {
                String purchaser = trim(row.get("PURCHASER_ID"));
                String customer = trim(row.get("CUSTOMER_ID"));
                String quote = trim(row.get("QUOTATION_NUMBER"));
                String salesTerritory = trim(row.get("RESP_SALES_TERRITORY"));
                if (!purchaser.isEmpty()) keys.accountNumbers.add(purchaser);
                if (!customer.isEmpty()) keys.accountNumbers.add(customer);
                if (!quote.isEmpty()) keys.quoteNumbers.add(quote);
                if (!salesTerritory.isEmpty()) keys.salesTerritoryNumbers.add(salesTerritory);
            }
        });

        System.out.println("开始预取关联数据：账户编号=" + keys.accountNumbers.size()
                + ", 报价单号=" + keys.quoteNumbers.size());
        long entityType = loadEntityType(opt, token);
        Map<String, Long> accountIds = queryLookupIds(opt, token, "account", "kunnr__c", keys.accountNumbers);
        Map<String, Long> quoteIds = queryLookupIds(opt, token, "quote__c", "quoteNumberAutoGenerate__c", keys.quoteNumbers);
        Map<String, Long> salesTerritoryIds = queryLookupIds(opt, token, "saleorg__c", "name", keys.salesTerritoryNumbers);
        Map<String, Integer> orderStatusValues = loadOptionCodes(opt, token, "orderStatus__c");
        Map<String, Integer> creditStatusValues = loadOptionCodes(opt, token, "crediStatus__c");

        System.out.println("预取完成：entityType=" + entityType
                + ", 账户ID=" + accountIds.size()
                + ", 报价单ID=" + quoteIds.size()
                + ", 销售组织ID=" + salesTerritoryIds.size()
                + ", orderStatus选项=" + orderStatusValues.size()
                + ", crediStatus选项=" + creditStatusValues.size());

        final ImportContext context = new ImportContext(opt, token, entityType, accountIds, quoteIds,
                salesTerritoryIds, orderStatusValues, creditStatusValues);
        final List<Map<String, Object>> batch = new ArrayList<Map<String, Object>>(opt.batchSize);
        final int[] total = {0};
        final int[] successBatches = {0};
        final int[] failedBatches = {0};

        forEachCsvRow(opt.csvPath, new RowConsumer() {
            public void accept(int lineNo, Map<String, String> row) throws Exception {
                if (opt.limit > 0 && total[0] >= opt.limit) return;
                batch.add(toCrmRecord(row, context));
                if (batch.size() >= opt.batchSize || (opt.limit > 0 && total[0] + batch.size() >= opt.limit)) {
                    BatchResult result = callBatch(opt, token, batch);
                    total[0] += batch.size();
                    if (result.httpCode >= 200 && result.httpCode < 300) successBatches[0]++;
                    else failedBatches[0]++;
                    System.out.println("批次完成：rows=" + batch.size() + ", http=" + result.httpCode
                            + ", response=" + result.body);
                    batch.clear();
                }
            }
        });

        if (!batch.isEmpty()) {
            BatchResult result = callBatch(opt, token, batch);
            total[0] += batch.size();
            if (result.httpCode >= 200 && result.httpCode < 300) successBatches[0]++;
            else failedBatches[0]++;
            System.out.println("批次完成：rows=" + batch.size() + ", http=" + result.httpCode
                    + ", response=" + result.body);
        }
        System.out.println("完成：读取=" + total[0] + " 条，成功批次=" + successBatches[0]
                + "，失败批次=" + failedBatches[0] + "，模式=" + opt.operate);
    }

    private static Map<String, Object> toCrmRecord(Map<String, String> row, ImportContext context) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("entityType", context.entityType);

        for (Map.Entry<String, String> mapping : FIELD_MAP.entrySet()) {
            String csvName = mapping.getKey();
            String value = trim(row.get(csvName));
            if (value.isEmpty()) continue;

            Object crmValue = value;
            if ("REQUESTED_DELIVERY_DATE".equals(csvName)) {
                crmValue = normalizeTimestamp(value);
            } else if ("TOTAL_AMOUNT_WITH_TAX".equals(csvName)) {
                crmValue = new BigDecimal(value.replace(",", "").trim());
            } else if ("ORDER_STATUS".equals(csvName)) {
                crmValue = optionValue(value, context.orderStatusValues, csvName);
            } else if ("CREDIT_STATUS".equals(csvName)) {
                crmValue = optionValue(value, context.creditStatusValues, csvName);
            } else if ("PURCHASER_ID".equals(csvName) || "CUSTOMER_ID".equals(csvName)) {
                crmValue = context.accountIds.get(value);
            } else if ("QUOTATION_NUMBER".equals(csvName)) {
                crmValue = context.quoteIds.get(value);
            } else if ("RESP_SALES_TERRITORY".equals(csvName)) {
                crmValue = context.salesTerritoryIds.get(value);
            }

            // 关联编号没有匹配到时留空跳过，不把业务编号写入 Lookup 字段。
            if (crmValue == null || "".equals(crmValue)) continue;
            record.put(mapping.getValue(), crmValue);
        }
        return record;
    }

    private static Integer optionValue(String value, Map<String, Integer> values, String field) {
        String key = value.trim().toUpperCase(Locale.ROOT);
        Integer result = values.get(key);
        if (result != null) return result;
        if (key.matches("\\d+")) return Integer.valueOf(key);
        // CSV 单选值未在 CRM 选项集中找到时，按老板要求留空跳过，不中断整批导入。
        return null;
    }

    private static long loadEntityType(Options opt, String token) throws Exception {
        JSONObject ret = getJson(opt.gateway + BUSI_TYPE_PATH, token);
        Long entityType = findEntityType(ret);
        if (entityType == null) {
            throw new IllegalStateException("未获取到 order__c 的 entityType，请检查业务类型接口返回");
        }
        return entityType.longValue();
    }

    private static Long findEntityType(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            Long direct = longValue(object, "entityType", "busiTypeId", "defaultBusiTypeId");
            if (direct != null) return direct;
            Object records = object.get("records");
            Long fromRecords = findEntityType(records);
            if (fromRecords != null) return fromRecords;
            Object data = object.get("data");
            Long fromData = findEntityType(data);
            if (fromData != null) return fromData;
            Object items = object.get("items");
            Long fromItems = findEntityType(items);
            if (fromItems != null) return fromItems;
            if (object.get("id") != null && (object.get("apiKey") != null
                    || object.get("busiTypeApiKey") != null || object.get("name") != null
                    || object.get("label") != null)) {
                return toLong(object.get("id"));
            }
            for (Object value : object.values()) {
                Long nested = findEntityType(value);
                if (nested != null) return nested;
            }
        } else if (node instanceof JSONArray) {
            for (Object value : (JSONArray) node) {
                Long nested = findEntityType(value);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static Map<String, Long> queryLookupIds(Options opt, String token, String entity,
                                                     String field, Set<String> keys) throws Exception {
        Map<String, Long> result = new HashMap<String, Long>();
        List<String> values = new ArrayList<String>(keys);
        for (int start = 0; start < values.size(); start += QUERY_CHUNK_SIZE) {
            int end = Math.min(start + QUERY_CHUNK_SIZE, values.size());
            String xoql = "SELECT id, " + field + " FROM " + entity + " WHERE " + field
                    + " IN (" + inClause(values.subList(start, end)) + ") LIMIT " + QUERY_CHUNK_SIZE;
            JSONObject response = postJson(opt.gateway + XOQL_PATH, token, xoqlPayload(xoql).toJSONString());
            JSONArray records = extractRecords(response);
            for (Object item : records) {
                if (!(item instanceof JSONObject)) continue;
                JSONObject record = (JSONObject) item;
                String key = trim(record.getString(field));
                Long id = toLong(record.get("id"));
                if (!key.isEmpty() && id != null) result.put(key, id);
            }
            System.out.println("批量反查完成：entity=" + entity + ", range=" + start + "-" + end
                    + ", result=" + records.size());
        }
        return result;
    }

    private static Map<String, Integer> loadOptionCodes(Options opt, String token, String fieldApiKey) throws Exception {
        JSONObject ret = getJson(opt.gateway + FIELD_META_PATH, token);
        Map<String, Integer> result = new HashMap<String, Integer>();
        collectOptionCodes(ret, fieldApiKey, result);
        return result;
    }

    private static void collectOptionCodes(Object node, String fieldApiKey, Map<String, Integer> result) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (fieldApiKey.equals(object.getString("apiKey"))) {
                JSONArray picks = extractArray(object.get("pickOption"));
                for (Object item : picks) {
                    if (!(item instanceof JSONObject)) continue;
                    JSONObject pick = (JSONObject) item;
                    if (!pick.getBooleanValue("active")) continue;
                    Integer code = toInteger(pick.get("optionCode"));
                    if (code == null) continue;
                    String apiKey = trim(pick.getString("optionApiKey"));
                    String label = trim(pick.getString("optionLabel"));
                    if (!apiKey.isEmpty()) result.put(apiKey.toUpperCase(Locale.ROOT), code);
                    if (!label.isEmpty()) result.put(label.toUpperCase(Locale.ROOT), code);
                }
            }
            for (Object value : object.values()) collectOptionCodes(value, fieldApiKey, result);
        } else if (node instanceof JSONArray) {
            for (Object value : (JSONArray) node) collectOptionCodes(value, fieldApiKey, result);
        }
    }

    private static BatchResult callBatch(Options opt, String token, List<Map<String, Object>> records) throws Exception {
        JSONObject params = new JSONObject(true);
        params.put("operate", opt.operate);
        params.put("entity", ENTITY);
        params.put("partialSuccess", true);
        params.put("data", records);
        String payload = params.toJSONString();
        JSONObject ret = postJson(opt.gateway + BATCH_PATH, token, payload);
        int code = ret.containsKey("code") ? ret.getIntValue("code") : 200;
        return new BatchResult(code, ret.toJSONString());
    }

    private static JSONObject xoqlPayload(String xoql) {
        JSONObject body = new JSONObject(true);
        body.put("xoql", xoql);
        body.put("useSimpleCode", "true");
        return body;
    }

    private static JSONArray extractRecords(JSONObject ret) {
        if (ret == null) return new JSONArray();
        JSONArray result = extractArray(ret.get("records"));
        if (result != null) return result;
        result = extractArray(ret.get("data"));
        return result == null ? new JSONArray() : result;
    }

    private static JSONArray extractArray(Object value) {
        if (value instanceof JSONArray) return (JSONArray) value;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : new String[] {"records", "items", "data", "list"}) {
                Object nested = object.get(key);
                if (nested instanceof JSONArray) return (JSONArray) nested;
            }
        }
        return new JSONArray();
    }

    private static JSONObject getJson(String urlText, String token) throws Exception {
        HttpURLConnection conn = openConnection(urlText, "GET", token);
        int code = conn.getResponseCode();
        String body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        conn.disconnect();
        if (body.trim().isEmpty()) throw new IOException("GET 返回为空，HTTP=" + code + "，URL=" + urlText);
        JSONObject ret = JSON.parseObject(body);
        if (ret == null) throw new IOException("GET 返回不是 JSON，HTTP=" + code + "：" + body);
        return ret;
    }

    private static JSONObject postJson(String urlText, String token, String payload) throws Exception {
        HttpURLConnection conn = openConnection(urlText, "POST", token);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        conn.setFixedLengthStreamingMode(bytes.length);
        OutputStream out = conn.getOutputStream();
        out.write(bytes);
        out.close();
        int code = conn.getResponseCode();
        String body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        conn.disconnect();
        if (body.trim().isEmpty()) throw new IOException("POST 返回为空，HTTP=" + code + "，URL=" + urlText);
        JSONObject ret = JSON.parseObject(body);
        if (ret == null) throw new IOException("POST 返回不是 JSON，HTTP=" + code + "：" + body);
        return ret;
    }

    private static HttpURLConnection openConnection(String urlText, String method, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static void forEachCsvRow(String path, RowConsumer consumer) throws Exception {
        BufferedReader reader = openCsvReader(path);
        try {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new IllegalArgumentException("CSV 为空");
            List<String> headers = parseCsvLine(removeBom(headerLine));
            validateHeaders(headers);
            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (!line.trim().isEmpty()) consumer.accept(lineNo, rowMap(headers, parseCsvLine(line)));
            }
        } finally {
            reader.close();
        }
    }

    private static BufferedReader openCsvReader(String path) throws IOException {
        Charset charset = detectCharset(path);
        return Files.newBufferedReader(Paths.get(path), charset);
    }

    private static Charset detectCharset(String path) throws IOException {
        byte[] sample = new byte[65536];
        int count;
        InputStream input = Files.newInputStream(Paths.get(path));
        try {
            count = input.read(sample);
        } finally {
            input.close();
        }
        if (count >= 3 && (sample[0] & 0xff) == 0xef && (sample[1] & 0xff) == 0xbb && (sample[2] & 0xff) == 0xbf) {
            return StandardCharsets.UTF_8;
        }
        Charset[] charsets = {StandardCharsets.UTF_8, Charset.forName("GB18030")};
        for (Charset charset : charsets) {
            try {
                CharsetDecoder decoder = charset.newDecoder();
                decoder.onMalformedInput(CodingErrorAction.REPORT);
                decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
                decoder.decode(ByteBuffer.wrap(sample, 0, Math.max(count, 0)));
                return charset;
            } catch (CharacterCodingException ignored) { }
        }
        throw new IOException("CSV 不是 UTF-8 或 GB18030 编码");
    }

    private static String readAccessToken(String tokenFile) throws IOException {
        String json = new String(Files.readAllBytes(Paths.get(tokenFile)), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\\\"(?:access_token|user_token|token)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("token 文件未找到 access_token/user_token/token");
        return matcher.group(1);
    }

    private static String inClause(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append('\'').append(value.replace("'", "''")).append('\'');
        }
        return result.toString();
    }

    private static Long normalizeTimestamp(String value) {
        String v = value.trim();
        String[] formats = {"yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd", "yyyy/MM/dd", "yyyyMMdd"};
        for (String format : formats) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                LocalDateTime dateTime = format.contains("H")
                        ? LocalDateTime.parse(v, formatter)
                        : LocalDate.parse(v, formatter).atStartOfDay();
                return dateTime.toInstant(ZoneOffset.ofHours(8)).toEpochMilli();
            } catch (DateTimeParseException ignored) { }
        }
        throw new IllegalArgumentException("日期格式无法解析：" + value);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) { values.add(current.toString()); current.setLength(0); }
            else current.append(c);
        }
        values.add(current.toString());
        return values;
    }

    private static Map<String, String> rowMap(List<String> headers, List<String> values) {
        Map<String, String> row = new LinkedHashMap<String, String>();
        for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), i < values.size() ? values.get(i) : "");
        return row;
    }

    private static void validateHeaders(List<String> headers) {
        if (!headers.contains("ORDER_NUMBER")) throw new IllegalArgumentException("CSV 缺少 ORDER_NUMBER 列");
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String removeBom(String value) { return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value; }
    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Long longValue(JSONObject object, String... keys) {
        for (String key : keys) {
            if (object.containsKey(key)) {
                Long value = toLong(object.get(key));
                if (value != null) return value;
            }
        }
        return null;
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        try { return Long.valueOf(value.toString()); } catch (Exception ignored) { return null; }
    }

    private static Integer toInteger(Object value) {
        if (value == null) return null;
        try { return Integer.valueOf(value.toString()); } catch (Exception ignored) { return null; }
    }

    private interface RowConsumer { void accept(int lineNo, Map<String, String> row) throws Exception; }

    private static class LookupKeys {
        final Set<String> accountNumbers = new LinkedHashSet<String>();
        final Set<String> quoteNumbers = new LinkedHashSet<String>();
        final Set<String> salesTerritoryNumbers = new LinkedHashSet<String>();
    }

    private static class ImportContext {
        final Options opt;
        final String token;
        final long entityType;
        final Map<String, Long> accountIds;
        final Map<String, Long> quoteIds;
        final Map<String, Long> salesTerritoryIds;
        final Map<String, Integer> orderStatusValues;
        final Map<String, Integer> creditStatusValues;
        ImportContext(Options opt, String token, long entityType, Map<String, Long> accountIds,
                      Map<String, Long> quoteIds, Map<String, Long> salesTerritoryIds,
                      Map<String, Integer> orderStatusValues, Map<String, Integer> creditStatusValues) {
            this.opt = opt; this.token = token; this.entityType = entityType;
            this.accountIds = accountIds; this.quoteIds = quoteIds;
            this.salesTerritoryIds = salesTerritoryIds;
            this.orderStatusValues = orderStatusValues; this.creditStatusValues = creditStatusValues;
        }
    }

    private static class BatchResult {
        final int httpCode; final String body;
        BatchResult(int httpCode, String body) { this.httpCode = httpCode; this.body = body; }
    }

    private static class Options {
        String csvPath;
        String gateway = DEFAULT_GATEWAY;
        String tokenFile = DEFAULT_TOKEN_FILE;
        String operate = "insert";
        int batchSize = MAX_BATCH_SIZE;
        int limit = 0;
        static Options parse(String[] args) {
            Options result = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--limit".equals(arg)) result.limit = Integer.parseInt(args[++i]);
                else if ("--batch-size".equals(arg)) result.batchSize = Integer.parseInt(args[++i]);
                else if ("--gateway".equals(arg)) result.gateway = args[++i];
                else if ("--token-file".equals(arg)) result.tokenFile = args[++i];
                else if ("--operate".equals(arg)) result.operate = args[++i];
                else if ("--execute".equals(arg)) { /* main 默认已执行，兼容旧参数 */ }
                else if (!arg.startsWith("--")) result.csvPath = arg;
            }
            if (result.batchSize < 1 || result.batchSize > MAX_BATCH_SIZE) throw new IllegalArgumentException("batch-size 必须为 1~1000");
            return result;
        }
    }
}
