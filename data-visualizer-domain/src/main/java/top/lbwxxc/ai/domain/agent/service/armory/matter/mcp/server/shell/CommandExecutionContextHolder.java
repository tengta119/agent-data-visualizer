package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell;

/**
 * 命令执行上下文 ThreadLocal Holder。
 *
 * <p>限制：只能在已被运行日志验证为同线程的 Agent 执行链中使用
 * （CompletableFuture.runAsync → ADK → Spring AI Tool → ShellExecutor）。
 *
 * <p>设置必须发生在真正运行 Agent 的执行体内，并在同一执行线程的 finally 中调用
 * {@link #clear()}（remove），异常、超时、拒绝、取消和正常完成都不能残留上下文。
 * 不要使用 InheritableThreadLocal 代替本类；线程池场景下不能可靠传播。</p>
 */
public final class CommandExecutionContextHolder {

    private static final ThreadLocal<CommandExecutionContext> CONTEXT = new ThreadLocal<>();

    private CommandExecutionContextHolder() {
    }

    public static void set(CommandExecutionContext context) {
        CONTEXT.set(context);
    }

    public static CommandExecutionContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
