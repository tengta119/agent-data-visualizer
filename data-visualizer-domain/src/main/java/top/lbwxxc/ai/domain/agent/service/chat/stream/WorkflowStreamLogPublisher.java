package top.lbwxxc.ai.domain.agent.service.chat.stream;

import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 将模型 token 片段聚合为可阅读的日志；每次流式请求独立创建一个实例。 */
public final class WorkflowStreamLogPublisher implements Consumer<PaiCliWorkflowEvent> {
    private static final int MAX_CHUNK_LENGTH = 256;
    private final Map<String, StringBuilder> pendingByStage = new LinkedHashMap<>();
    private final BiConsumer<String, String> publishLog;

    public WorkflowStreamLogPublisher(BiConsumer<String, String> publishLog) {
        this.publishLog = publishLog;
    }

    @Override
    public synchronized void accept(PaiCliWorkflowEvent event) {
        if (event.kind() == PaiCliWorkflowEvent.Kind.CONTENT_DELTA) {
            append(event.stage(), event.content());
            return;
        }
        // 工具、状态和阶段事件不能越过尚未发送的正文。
        flushStage(event.stage());
        String content = switch (event.kind()) {
            case STAGE_STARTED -> "stage started";
            case STAGE_COMPLETED -> "stage completed";
            default -> event.content();
        };
        publishNonBlank(event.stage(), content);
    }

    /** 在最终结果或错误之前发送没有换行的尾部正文。 */
    public synchronized void flush() {
        for (String stage : pendingByStage.keySet().toArray(String[]::new)) {
            flushStage(stage);
        }
    }

    private void append(String stage, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        StringBuilder pending = pendingByStage.computeIfAbsent(stage, ignored -> new StringBuilder());
        for (int i = 0; i < delta.length(); i++) {
            char character = delta.charAt(i);
            pending.append(character);
            // 无换行的 JSON/XML 也分段推送；避免在 UTF-16 代理对中间切断。
            if (character == '\n'
                    || (pending.length() >= MAX_CHUNK_LENGTH && !Character.isHighSurrogate(character))) {
                String content = pending.toString();
                pending.setLength(0);
                publishNonBlank(stage, content);
            }
        }
    }

    private void flushStage(String stage) {
        StringBuilder pending = pendingByStage.remove(stage);
        if (pending != null) {
            publishNonBlank(stage, pending.toString());
        }
    }

    private void publishNonBlank(String stage, String content) {
        if (content != null && !content.isBlank()) {
            publishLog.accept(stage, content);
        }
    }
}
