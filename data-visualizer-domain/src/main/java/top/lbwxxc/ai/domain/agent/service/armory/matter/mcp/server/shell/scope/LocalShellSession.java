package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个作用域 key 对应的本地 Shell 会话（仅存在于当前 JVM）。
 *
 * <p>线程约束（沿用 ADR-003 的字段划分）：</p>
 * <ul>
 *   <li>{@code handle} 的 writer/reader 只允许本地 Shell 专用执行线程读写；</li>
 *   <li>{@code destroyForcibly()} 允许任意线程调用（超时、请求结束、上限淘汰、空闲回收、应用关闭），
 *       目的是解除执行线程在 {@code readLine} 上的阻塞；</li>
 *   <li>{@code inUse} 只用于让清扫/淘汰跳过正在执行命令的会话，不参与显式销毁的判断。</li>
 * </ul>
 */
public class LocalShellSession {

    private final String key;
    private final boolean ephemeral;
    private final LocalShellHandle handle;
    private final AtomicBoolean inUse = new AtomicBoolean(false);
    private final long createdAtMillis;
    private volatile long lastUsedAtMillis;

    public LocalShellSession(String key, boolean ephemeral, LocalShellHandle handle, long nowMillis) {
        this.key = key;
        this.ephemeral = ephemeral;
        this.handle = handle;
        this.createdAtMillis = nowMillis;
        this.lastUsedAtMillis = nowMillis;
    }

    public String key() {
        return key;
    }

    /**
     * 是否为"无请求上下文"下创建的一次性 Shell：命令结束后立即销毁，不参与复用。
     */
    public boolean ephemeral() {
        return ephemeral;
    }

    public LocalShellHandle handle() {
        return handle;
    }

    public String shellName() {
        return handle.shellName();
    }

    public boolean isAlive() {
        return handle.isAlive();
    }

    public boolean isInUse() {
        return inUse.get();
    }

    public void markInUse() {
        inUse.set(true);
    }

    public void markIdle() {
        inUse.set(false);
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long lastUsedAtMillis() {
        return lastUsedAtMillis;
    }

    public void touch(long nowMillis) {
        this.lastUsedAtMillis = nowMillis;
    }

    /**
     * 销毁底层进程。幂等：重复调用与进程已退出都是安全操作。
     *
     * @return 销毁前进程是否仍存活，便于日志区分"真的杀掉了"与"已经死了"
     */
    public boolean destroyForcibly() {
        boolean alive = handle.isAlive();
        try {
            handle.destroyForcibly();
        } catch (Exception ignored) {
            // 进程已退出或句柄已释放：销毁本身是幂等的，不需要向上传播
        }
        return alive;
    }
}
