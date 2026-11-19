package dev.conduit.transport.adapter;

import java.util.function.BooleanSupplier;

/**
 * 协议适配器：把某种设备协议的消息转换为 RawMessage 投递到总线。新增协议（HTTP、Modbus TCP 等）只需新增一个实现。
 * 生命周期由身份池分配器 IdentityAllocator 驱动：每个适配器对应一个身份，本实例持有该身份时启动，
 * 失去身份或进程停止时立即停止；同一实例可多次 start / stop。
 */
public interface ProtocolAdapter {

    /** 适配器名称，用于日志。 */
    String name();

    /**
     * 建立连接并开始接收，就绪（连接与订阅都成功）后才返回。
     *
     * @param stillValid 启动是否仍然有效（例如本实例仍持有该身份）；适配器在发出连接请求前与订阅前各检查一次，
     *                   为 false 时放弃并抛异常，避免失去身份后仍连上 broker 顶掉新持有者的会话
     * @throws IllegalStateException 连接或订阅失败、超时、启动已失效或被并发 stop；资源已清理，调用方可稍后重试
     */
    void start(BooleanSupplier stillValid);

    /** 无条件启动，等价于 start(() -> true)。 */
    default void start() {
        start(() -> true);
    }

    /** 立即停止接收并断开，幂等；可与进行中的 start 并发调用，使其失败返回。 */
    void stop();

    /** 当前是否已连接并在接收。 */
    boolean isConnected();

    /** 连接已断开且不会自行恢复（断线、会话被接管等），需由调用方 stop 后重新 start。 */
    boolean needsRestart();
}
