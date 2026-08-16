package top.lbwxxc.ai.domain.agent.service.armory.matter.plugin;

import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Builds a bounded model context without changing ADK Session events.
 *
 * <p>The project creates ChatModel instances dynamically for each Agent, so this helper deliberately
 * creates a local structured summary instead of making another model invocation from a Plugin. This
 * keeps compaction deterministic and prevents a summary request from recursively entering the same
 * plugin callback chain.</p>
 */
public final class ContextCompactionSupport {

    public static final String CONTEXT_SUMMARY_KEY = "context_summary";

    static final int TOKEN_THRESHOLD = 12_000;
    static final int CHARACTER_THRESHOLD = 40_000;
    static final int EVENT_THRESHOLD = 30;
    static final int RECENT_CONTENT_COUNT = 8;
    static final int MAX_SUMMARY_CHARACTERS = 6_000;
    static final int MAX_ENTRY_CHARACTERS = 800;

    private ContextCompactionSupport() {
    }

    public static CompactionResult compact(List<Content> requestContents, String existingSummary, int eventCount) {
        List<Content> contents = requestContents == null ? Collections.emptyList() : requestContents;
        ContextSize contextSize = measure(contents);
        if (!shouldCompact(contextSize, eventCount) || contents.size() <= RECENT_CONTENT_COUNT) {
            return CompactionResult.notCompacted();
        }

        int recentStart = findRecentStart(contents);
        List<Content> compactedHistory = contents.subList(0, recentStart);
        List<Content> recentContents = contents.subList(recentStart, contents.size());
        String summary = buildSummary(existingSummary, compactedHistory);

        if (summary.isEmpty()) {
            return CompactionResult.notCompacted();
        }

        List<Content> compactedContents = new ArrayList<>(recentContents.size() + 1);
        compactedContents.add(Content.builder()
                .role("user")
                .parts(Part.fromText("以下是本会话较早历史的压缩摘要，请结合后续完整上下文继续完成当前任务：\n" + summary))
                .build());
        compactedContents.addAll(recentContents);

        return CompactionResult.compacted(compactedContents, summary, contextSize);
    }

    static boolean shouldCompact(ContextSize contextSize, int eventCount) {
        if (contextSize.estimatedTokens >= TOKEN_THRESHOLD) {
            return true;
        }
        if (contextSize.characters >= CHARACTER_THRESHOLD) {
            return true;
        }
        return eventCount >= EVENT_THRESHOLD;
    }

    private static int findRecentStart(List<Content> contents) {
        int start = Math.max(0, contents.size() - RECENT_CONTENT_COUNT);

        // A tool response without its preceding function call is not useful to the model. Move the
        // boundary backwards until the first retained record is not an orphaned tool response.
        while (start > 0 && hasFunctionResponse(contents.get(start))) {
            start--;
        }
        return start;
    }

    private static String buildSummary(String existingSummary, List<Content> compactedHistory) {
        StringBuilder summary = new StringBuilder();
        appendBounded(summary, normalize(existingSummary), MAX_SUMMARY_CHARACTERS / 2);

        for (Content content : compactedHistory) {
            String record = describeContent(content);
            if (record.isEmpty()) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append('\n');
            }
            appendBounded(summary, record, MAX_ENTRY_CHARACTERS);
            if (summary.length() >= MAX_SUMMARY_CHARACTERS) {
                break;
            }
        }

        if (summary.length() > MAX_SUMMARY_CHARACTERS) {
            return summary.substring(0, MAX_SUMMARY_CHARACTERS);
        }
        return summary.toString();
    }

    private static ContextSize measure(List<Content> contents) {
        int characters = 0;
        for (Content content : contents) {
            characters += describeContent(content).length();
        }
        // Chinese and mixed prompts do not have a stable char-to-token ratio. Four characters per
        // token is intentionally conservative and character/event thresholds remain fallbacks.
        int estimatedTokens = (characters + 3) / 4;
        return new ContextSize(characters, estimatedTokens);
    }

    private static String describeContent(Content content) {
        if (content == null) {
            return "";
        }

        String role = content.role().orElse("unknown");
        StringBuilder text = new StringBuilder("[").append(role).append("] ");
        List<Part> parts = content.parts().orElse(Collections.emptyList());
        for (Part part : parts) {
            Optional<String> partText = part.text();
            if (partText.isPresent() && !partText.get().trim().isEmpty()) {
                text.append(partText.get().trim());
            } else if (part.functionCall().isPresent()) {
                text.append("[工具调用: ")
                        .append(part.functionCall().get().name().orElse("unknown"))
                        .append("]");
            } else if (part.functionResponse().isPresent()) {
                text.append("[工具结果: ")
                        .append(part.functionResponse().get().name().orElse("unknown"))
                        .append("]");
            } else if (part.inlineData().isPresent() || part.fileData().isPresent()) {
                text.append("[非文本多模态内容已省略]");
            }
        }
        return text.toString().trim();
    }

    private static boolean hasFunctionResponse(Content content) {
        return content != null
                && content.parts().orElse(Collections.emptyList()).stream()
                .anyMatch(part -> part.functionResponse().isPresent());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static void appendBounded(StringBuilder builder, String value, int maxLength) {
        if (value == null || value.isEmpty() || builder.length() >= MAX_SUMMARY_CHARACTERS) {
            return;
        }
        int remaining = Math.min(maxLength, MAX_SUMMARY_CHARACTERS - builder.length());
        builder.append(value, 0, Math.min(value.length(), remaining));
    }

    static final class ContextSize {
        private final int characters;
        private final int estimatedTokens;

        private ContextSize(int characters, int estimatedTokens) {
            this.characters = characters;
            this.estimatedTokens = estimatedTokens;
        }
    }

    public static final class CompactionResult {
        private final boolean compacted;
        private final List<Content> contents;
        private final String summary;
        private final ContextSize contextSize;

        private CompactionResult(boolean compacted, List<Content> contents, String summary, ContextSize contextSize) {
            this.compacted = compacted;
            this.contents = contents;
            this.summary = summary;
            this.contextSize = contextSize;
        }

        static CompactionResult notCompacted() {
            return new CompactionResult(false, Collections.emptyList(), "", null);
        }

        static CompactionResult compacted(List<Content> contents, String summary, ContextSize contextSize) {
            return new CompactionResult(true, Collections.unmodifiableList(new ArrayList<>(contents)), summary, contextSize);
        }

        public boolean isCompacted() {
            return compacted;
        }

        public List<Content> getContents() {
            return contents;
        }

        public String getSummary() {
            return summary;
        }

        public int getOriginalCharacterCount() {
            return contextSize == null ? 0 : contextSize.characters;
        }

        public int getEstimatedTokenCount() {
            return contextSize == null ? 0 : contextSize.estimatedTokens;
        }
    }
}
