package dev.conduit.transport.allocation;

/**
 * Redis 中的身份池大小 {@code conduit:transport:pool-size} 不是正整数（非数字、0 或负数）。
 * 这是运维写错了值，不是 Redis 不可达：分配器据此保持当前 K、照常续期，并提示修正，而不是停止全部抢占。
 */
public class InvalidPoolSizeException extends RuntimeException {

    private final String rawValue;

    /** @param rawValue Redis 中读到的原始字符串 */
    public InvalidPoolSizeException(String rawValue) {
        super("conduit:transport:pool-size is not a positive integer: \"" + rawValue + "\"");
        this.rawValue = rawValue;
    }

    /** Redis 中读到的原始字符串。 */
    public String rawValue() {
        return rawValue;
    }
}
