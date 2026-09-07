package top.lbwxxc.ai.domain.agent.service.armory.matter.skills.impl;


import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI Community 构建skills <a href="https://github.com/spring-ai-community/spring-ai-agent-utils">spring-ai-agent-utils</a>
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/2/6 08:04
 */
@Slf4j
@Service
public class DefaultToolSkillsCreateService implements ToolSkillsCreateService {

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception {

        String type = toolSkills.getType();
        String path = toolSkills.getPath();

        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if ("directory".equals(type)) {
            ToolCallback toolCallback = SkillsTool.builder()
                    .addSkillsDirectory(path)
                    .build();
            toolCallbackList.add(toolCallback);
        }

        if ("resource".equals(type)) {
            ToolCallback toolCallback = SkillsTool.builder()
                    .addSkillsDirectory(resolveSkillsDirectory(path))
                    .build();
            toolCallbackList.add(toolCallback);
        }

        return toolCallbackList.toArray(new ToolCallback[0]);
    }

    /**
     * 解析 skills 目录为文件系统路径。
     * <p>
     * IDE（exploded classpath）下 {@link ClassPathResource#getFile()} 可直接解析；
     * fat JAR（spring boot nested jar）中 classpath 资源不在文件系统上，
     * {@code getFile()} 会抛 {@link FileNotFoundException}，
     * 此时将 classpath 下的 skills 目录整体解包到临时目录后再装配。
     */
    private String resolveSkillsDirectory(String path) throws Exception {
        try {
            Path file = new ClassPathResource(path).getFile().toPath();
            if (Files.isDirectory(file)) {
                return file.toAbsolutePath().toString();
            }
        } catch (FileNotFoundException ignored) {
            // fat JAR 内继续走解包逻辑
        }

        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:" + cleanPath + "/**/*");

        Path tempDir = Files.createTempDirectory("agent-skills-");
        String marker = cleanPath + "/";
        int copied = 0;
        for (Resource resource : resources) {
            String relative = resolveRelativePath(resource, marker);
            if (relative == null || relative.isEmpty()) {
                continue;
            }
            Path target = tempDir.resolve(relative).normalize();
            if (!target.startsWith(tempDir)) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (java.io.IOException dirOrUnreadable) {
                // 目录条目或不可读资源，跳过
            }
        }

        if (copied == 0) {
            throw new IllegalStateException("未从 classpath 解析到任何 skills 文件: " + path);
        }

        log.info("skills 已从 classpath 解包到临时目录: {} ({} 个文件, 来源路径 {})", tempDir, copied, path);
        return tempDir.toAbsolutePath().toString();
    }

    private String resolveRelativePath(Resource resource, String marker) throws Exception {
        String url = resource.getURL().toString();
        int index = url.indexOf(marker);
        if (index < 0) {
            return null;
        }
        return url.substring(index + marker.length());
    }

}
