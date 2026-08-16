package top.lbwxxc.ai.test.domain.agent;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Test;
import top.lbwxxc.ai.domain.agent.service.armory.matter.plugin.ContextCompactionSupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContextCompactionSupportTest {

    @Test
    public void should_notCompact_whenContextBelowAllThresholds() {
        List<Content> contents = textContents(9);

        ContextCompactionSupport.CompactionResult result =
                ContextCompactionSupport.compact(contents, null, 0);

        assertFalse(result.isCompacted());
    }

    @Test
    public void should_compactByEstimatedTokenThreshold() {
        List<Content> contents = textContents(9);
        contents.set(0, textContent("x".repeat(50_000)));

        ContextCompactionSupport.CompactionResult result =
                ContextCompactionSupport.compact(contents, null, 0);

        assertTrue(result.isCompacted());
        assertTrue(result.getEstimatedTokenCount() >= 12_000);
    }

    @Test
    public void should_compactByCharacterFallbackThreshold() {
        List<Content> contents = textContents(9);
        contents.set(0, textContent("x".repeat(42_000)));

        ContextCompactionSupport.CompactionResult result =
                ContextCompactionSupport.compact(contents, null, 0);

        assertTrue(result.isCompacted());
        assertTrue(result.getEstimatedTokenCount() < 12_000);
        assertTrue(result.getOriginalCharacterCount() >= 40_000);
    }

    @Test
    public void should_compactByEventThreshold_andKeepExistingSummaryAndRecentContents() {
        List<Content> contents = textContents(12);

        ContextCompactionSupport.CompactionResult result =
                ContextCompactionSupport.compact(contents, "已知用户偏好：输出中文。", 30);

        assertTrue(result.isCompacted());
        assertTrue(result.getSummary().contains("已知用户偏好：输出中文。"));
        assertTrue(result.getSummary().contains("message-0"));
        assertEquals(9, result.getContents().size());
        assertTrue(result.getContents().get(0).text().contains("较早历史的压缩摘要"));
        assertTrue(result.getContents().get(result.getContents().size() - 1).text().contains("message-11"));
    }

    @Test
    public void should_keepFunctionCallWithLeadingFunctionResponse() {
        List<Content> contents = new ArrayList<>();
        contents.add(textContent("old context"));
        contents.add(Content.builder()
                .role("model")
                .parts(Part.fromFunctionCall("queryChart", Collections.emptyMap()))
                .build());
        contents.add(Content.builder()
                .role("user")
                .parts(Part.fromFunctionResponse("queryChart", Collections.singletonMap("status", "ok")))
                .build());
        for (int i = 0; i < 7; i++) {
            contents.add(textContent("recent-" + i));
        }

        ContextCompactionSupport.CompactionResult result =
                ContextCompactionSupport.compact(contents, null, 30);

        assertTrue(result.isCompacted());
        assertTrue(result.getContents().get(1).parts().get().get(0).functionCall().isPresent());
        assertTrue(result.getContents().get(2).parts().get().get(0).functionResponse().isPresent());
    }

    private List<Content> textContents(int count) {
        List<Content> contents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            contents.add(textContent("message-" + i));
        }
        return contents;
    }

    private Content textContent(String text) {
        return Content.builder().role("user").parts(Part.fromText(text)).build();
    }
}
