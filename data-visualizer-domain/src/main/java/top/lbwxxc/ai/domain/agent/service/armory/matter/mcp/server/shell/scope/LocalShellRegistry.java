package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地 Shell 作用域注册表：按作用域 key 持有当前 JVM 内所有存活的本地 Shell。
 *
 * <p>并发约定：</p>
 * <ul>
 *   <li><b>登记只发生在本地 Shell 专用执行线程内</b>（{@code getOrCreate} 的调用方），
 *       因此不存在两个线程竞争创建同一个 key 的正常路径；{@link #register(LocalShellSession)}
 *       仍使用 {@code putIfAbsent} 并返回最终生效的会话，便于调用方销毁多余的进程。</li>
 *   <li><b>移除与销毁可发生在任意线程</b>（超时线程、请求结束清理线程、执行器线程、Spring 关闭线程），
 *       统一通过 {@link #remove(String)} 的原子移除保证幂等。</li>
 *   <li>清扫与淘汰必须跳过正在执行命令的会话，并且不回收"请求仍然活跃"的 Shell，
 *       因此需要读取 {@link AgentStreamBridge} 判断请求是否仍在流式会话中
 *       （它是不引入新请求状态表的前提下唯一可用的活跃性来源）。</li>
 * </ul>
 *
 * <p>本类只管理生命周期，不关心命令内容、策略与审批；也不做任何持久化。</p>
 */
@Slf4j
@Component
public class LocalShellRegistry {

    private final ConcurrentMap<String, LocalShellSession> sessions = new ConcurrentHashMap<>();

    private final AgentStreamBridge agentStreamBridge;

    public LocalShellRegistry(AgentStreamBridge agentStreamBridge) {
        this.agentStreamBridge = agentStreamBridge;
    }

    public LocalShellSession get(String key) {
        return key == null ? null : sessions.get(key);
    }

    /**
     * 登记一个新建的会话。若该 key 已被其他线程登记，返回已存在的会话，
     * 调用方需要销毁自己新建的多余进程。
     */
    public LocalShellSession register(LocalShellSession session) {
        LocalShellSession existing = sessions.putIfAbsent(session.key(), session);
        return existing == null ? session : existing;
    }

    /**
     * 原子移除指定 key 的会话。移除成功说明本次调用是"第一个"处理者，调用方据此保证销毁幂等。
     *
     * @return 被移除的会话；key 不存在时返回 null（无副作用）
     */
    public LocalShellSession remove(String key) {
        return key == null ? null : sessions.remove(key);
    }

    public int size() {
        return sessions.size();
    }

    public List<LocalShellSession> all() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * 机会式空闲清扫：销毁"超过空闲阈值、当前空闲、且其请求已不再活跃"的 Shell。
     * 由本地 Shell 专用执行线程在创建新 Shell 之前调用，不引入任何调度线程。
     *
     * @return 被销毁的会话列表，供调用方记录日志
     */
    public List<LocalShellSession> sweepIdle(long nowMillis, long idleTimeoutMillis) {
        List<LocalShellSession> destroyed = new ArrayList<>();
        if (idleTimeoutMillis <= 0) {
            return destroyed;
        }
        for (LocalShellSession session : sessions.values()) {
            if (session.isInUse() || nowMillis - session.lastUsedAtMillis() < idleTimeoutMillis) {
                continue;
            }
            if (agentStreamBridge.contains(session.key())) {
                // 活跃请求的 Shell 即使长时间空闲也不回收，避免打断模型长思考后的后续命令
                continue;
            }
            if (sessions.remove(session.key(), session)) {
                session.destroyForcibly();
                destroyed.add(session);
            }
        }
        return destroyed;
    }

    /**
     * 淘汰最久未使用且当前空闲的会话，用于把存活 Shell 数控制在配置上限内。
     *
     * @return 被淘汰的会话；没有可淘汰项时返回 null
     */
    public LocalShellSession evictLeastRecentlyUsedIdle() {
        LocalShellSession candidate = null;
        for (LocalShellSession session : sessions.values()) {
            if (session.isInUse()) {
                continue;
            }
            if (candidate == null || session.lastUsedAtMillis() < candidate.lastUsedAtMillis()) {
                candidate = session;
            }
        }
        if (candidate == null) {
            return null;
        }
        if (!sessions.remove(candidate.key(), candidate)) {
            return null;
        }
        candidate.destroyForcibly();
        return candidate;
    }

    /**
     * 应用关闭时销毁全部存活 Shell。
     *
     * @return 被销毁的会话列表
     */
    public List<LocalShellSession> destroyAll() {
        List<LocalShellSession> destroyed = new ArrayList<>(sessions.values());
        sessions.clear();
        for (LocalShellSession session : destroyed) {
            session.destroyForcibly();
        }
        return destroyed;
    }
}
