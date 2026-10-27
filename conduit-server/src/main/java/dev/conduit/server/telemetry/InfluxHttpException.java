package dev.conduit.server.telemetry;

/** InfluxDB 返回了非 2xx，且不是「部分行被拒」的响应。 */
public class InfluxHttpException extends RuntimeException {

    private static final int MAX_BODY = 300;

    private final int status;
    private final String body;

    public InfluxHttpException(int status, String body) {
        super("InfluxDB HTTP " + status + ": " + abbreviate(body));
        this.status = status;
        this.body = body == null ? "" : body;
    }

    /**
     * 是否为「查询需要打开的 parquet 文件数超过上限」。InfluxDB 3 Core 把它报成 500，但它不是故障：
     * 缩短时间范围即可成功，所以调用方要把它和真正的服务不可用区分开。
     * 原文形如：{@code Query would scan 432 Parquet files, exceeding the file limit.}
     * 判据取两个稳定关键词，不依赖具体数字与后半段的升级推广文案。
     */
    public boolean fileLimitExceeded() {
        String lower = body.toLowerCase();
        return lower.contains("parquet files") && lower.contains("file limit");
    }

    /**
     * InfluxDB 返回的 HTTP 状态码，调用方据此区分「请求有误」（400）与「服务不可用」。
     */
    public int status() {
        return status;
    }

    /**
     * 截断响应体到 300 字符，避免异常消息过长；null 视为空串。
     */
    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY) + "…";
    }
}
