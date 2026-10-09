package top.lbwxxc.ai.domain.agent.service.chat.stream;

import org.junit.jupiter.api.Test;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowEvent.Kind.*;

class WorkflowStreamLogPublisherTest {
    @Test
    void tinyTokensBecomeCompleteLinesWithoutBlankLogs() {
        List<String> logs = new ArrayList<>();
        WorkflowStreamLogPublisher publisher = new WorkflowStreamLogPublisher((stage, text) -> logs.add(text));
        for (String delta : List.of("##", " ", "需求", "分析", "结果", "\n", "\n", "用户", "意图", "：画图")) {
            publisher.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "analyst", delta));
        }

        assertEquals(List.of("## 需求分析结果\n"), logs);
        publisher.flush();
        publisher.flush();
        assertEquals(List.of("## 需求分析结果\n", "用户意图：画图"), logs);
    }

    @Test
    void toolAndStageEventsFlushTheTailWithoutRepeatingTheFinalResult() {
        List<String> logs = new ArrayList<>();
        WorkflowStreamLogPublisher publisher = new WorkflowStreamLogPublisher((stage, text) -> logs.add(text));
        publisher.accept(new PaiCliWorkflowEvent(STAGE_STARTED, "analyst", ""));
        publisher.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "analyst", "正在查询"));
        publisher.accept(new PaiCliWorkflowEvent(TOOL_CALL, "analyst", "lookup"));
        publisher.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "analyst", "查询完成"));
        publisher.accept(new PaiCliWorkflowEvent(STATUS, "analyst", "idle"));
        publisher.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "analyst", "最终正文"));
        publisher.accept(new PaiCliWorkflowEvent(STAGE_COMPLETED, "analyst", "最终正文"));
        publisher.flush();

        assertEquals(List.of("stage started", "正在查询", "lookup", "查询完成", "idle",
                "最终正文", "stage completed"), logs);
    }

    @Test
    void interleavedStagesAndSeparateRequestsNeverSharePendingText() {
        List<String> firstLogs = new ArrayList<>();
        List<String> secondLogs = new ArrayList<>();
        WorkflowStreamLogPublisher first = new WorkflowStreamLogPublisher(
                (stage, text) -> firstLogs.add(stage + ":" + text));
        WorkflowStreamLogPublisher second = new WorkflowStreamLogPublisher(
                (stage, text) -> secondLogs.add(stage + ":" + text));
        first.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "a", "分析"));
        first.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "b", "绘图"));
        second.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "a", "另一个请求"));
        first.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "a", "完成\n"));
        first.flush();
        second.flush();

        assertEquals(List.of("a:分析完成\n", "b:绘图"), firstLogs);
        assertEquals(List.of("a:另一个请求"), secondLogs);
    }

    @Test
    void longSingleLineStreamsBeforeCompletionAndPreservesUnicode() {
        List<String> logs = new ArrayList<>();
        WorkflowStreamLogPublisher publisher = new WorkflowStreamLogPublisher((stage, text) -> logs.add(text));
        String content = "字".repeat(255) + "😀" + "尾".repeat(600);
        // 代理对可以被模型回调拆成两个 delta，聚合层仍需保持完整字符。
        for (int i = 0; i < content.length(); i++) {
            publisher.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "drawer", content.substring(i, i + 1)));
        }
        assertTrue(logs.size() >= 3);
        publisher.flush();

        assertEquals(content, String.join("", logs));
        assertTrue(logs.stream().allMatch(text -> text.length() <= 257));
        assertTrue(logs.stream().noneMatch(text -> Character.isHighSurrogate(text.charAt(text.length() - 1))));
        assertTrue(logs.stream().noneMatch(text -> Character.isLowSurrogate(text.charAt(0))));
    }

    @Test
    void ignoresEmptyDeltasAndWhitespaceOnlyEvents() {
        List<String> logs = new ArrayList<>();
        WorkflowStreamLogPublisher publisher = new WorkflowStreamLogPublisher((stage, text) -> logs.add(text));
        publisher.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "a", null));
        publisher.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "a", ""));
        publisher.accept(new PaiCliWorkflowEvent(CONTENT_DELTA, "a", " \r\n\t"));
        publisher.accept(new PaiCliWorkflowEvent(STATUS, "a", " "));
        publisher.flush();

        assertTrue(logs.isEmpty());
    }
}
