package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope;

import org.apache.commons.lang3.StringUtils;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContextHolder;

import java.util.UUID;

/**
 * 本地 Shell 的作用域 key —— <b>整个系统中解析该 key 的唯一决策点</b>。
 *
 * <p>默认作用域为"一次请求"：使用当前命令执行上下文中的 {@code requestId}，
 * 因此同一请求内的多条本地命令复用同一个 Shell，请求之间互不共享。</p>
 *
 * <p>读取不到请求上下文（例如同步 {@code /api/v1/chat} 路径没有 requestId）时，
 * 返回 {@link #ephemeral()} 为 true 的一次性作用域：为该条命令启动一个临时 Shell
 * 并在命令结束后立即销毁。这里刻意<b>不</b>退化为全局共享 Shell，也<b>不</b>按
 * userId/sessionId 猜测 key（sessionId 会在同一会话的多次请求间复用，等于跨请求共享）。</p>
 *
 * <p>不要在本类之外拼接或推断 key；也不要把 agentId/sessionId 拼成复合字符串，
 * 避免出现与 {@code ChatService} 的 {@code agentId:userId} 会话键类似的分隔符碰撞。</p>
 */
public record LocalShellScope(String key, boolean ephemeral) {

    public static LocalShellScope resolve() {
        CommandExecutionContext context = CommandExecutionContextHolder.get();
        if (context != null && StringUtils.isNotBlank(context.requestId())) {
            return new LocalShellScope(context.requestId(), false);
        }
        return new LocalShellScope(UUID.randomUUID().toString(), true);
    }
}
