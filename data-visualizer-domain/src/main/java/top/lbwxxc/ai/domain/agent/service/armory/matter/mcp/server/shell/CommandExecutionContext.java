package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell;

/**
 * 一次流式请求的命令执行上下文。
 *
 * <p>仅保存与当前请求相关的不可变标识，不作为业务状态、审批状态或跨线程存储使用。
 * 只在已确认的同线程执行链中通过 {@link CommandExecutionContextHolder} 读取；
 * 读取不到时必须安全失败，不得按 userId、sessionId 或全局变量猜测目标流。</p>
 */
public record CommandExecutionContext(
        String requestId,
        String agentId,
        String userId,
        String sessionId
) {
}
