package com.paicli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息
 *
 * 职责：
 * 1. 持久化用户偏好、项目事实、关键决策等
 * 2. 支持关键词检索
 * 3. 在相同类型和作用域内，基于规范化内容近似匹配自动去重
 * 4. 定期持久化到磁盘
 */
public class LongTermMemory implements Memory {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_DIR_PROPERTY = "paicli.memory.dir";
    private static final String STORAGE_DIR_ENV = "PAICLI_MEMORY_DIR";
    private static final String STORAGE_FILE = "long_term_memory.json";
    private final Map<String, MemoryEntry> entries;
    private final AtomicInteger tokenCounter;
    private final ObjectMapper mapper;
    private final File storageFile;
    private final MemoryConflictDetector conflictDetector;
    private final boolean inMemory;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this(storageDir, MemoryConflictDetector.fromConfiguration());
    }

    LongTermMemory(File storageDir, MemoryConflictDetector conflictDetector) {
        this(storageDir, conflictDetector, false);
    }

    private LongTermMemory(File storageDir, MemoryConflictDetector conflictDetector, boolean inMemory) {
        this.conflictDetector = conflictDetector;
        this.inMemory = inMemory;
        this.entries = new ConcurrentHashMap<>();
        this.tokenCounter = new AtomicInteger(0);
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 确保存储目录存在
        if (inMemory) {
            this.storageFile = null;
        } else {
            File dir = storageDir;
            if (!dir.exists()) {
                dir.mkdirs();
            }
            this.storageFile = new File(dir, STORAGE_FILE);
            loadFromDisk();
        }
    }

    /** Isolated memory for an embedded Agent; never reads or writes ~/.paicli. */
    public static LongTermMemory inMemory() {
        return new LongTermMemory(null, MemoryConflictDetector.fromConfiguration(), true);
    }

    @Override
    public synchronized void store(MemoryEntry entry) {
        // type + scope + project 共同限定去重域，避免不同项目或不同可见性的记忆互相误杀。
        boolean duplicate = entries.values().stream()
                .anyMatch(existing -> MemoryDeduplicator.isDuplicate(existing, entry));
        if (duplicate) {
            return;
        }

        MemoryEntry previous = entries.put(entry.getId(), entry);
        if (previous != null) {
            tokenCounter.addAndGet(-previous.getTokenCount());
        }
        tokenCounter.addAndGet(entry.getTokenCount());
        saveToDisk();
    }

    /**
     * 用户/模型显式保存记忆时的写入入口：去重 → 冲突检测 → 写入或替换，整体原子执行。
     *
     * <p>{@link #store(MemoryEntry)} 仍是底层存储语义（只做去重）；这里额外负责：
     * 等价条目只刷新核实时间；高度相似但不一致的条目不写入，交给用户决定；
     * {@code replaceId} 表示用户已选择用新内容替换某条旧记忆。</p>
     *
     * @param replaceId  用户选择替换的旧条目 id，可为 null
     * @param allowConflict 用户明确要求两条都保留时为 true
     * @param projectKey 当前项目，用于限制 replaceId 只能指向当前可见的条目
     */
    public synchronized MemoryWriteResult write(MemoryEntry entry, String replaceId,
                                                boolean allowConflict, String projectKey) {
        String scope = scopeOf(entry);
        MemoryEntry target = null;
        if (replaceId != null && !replaceId.isBlank()) {
            target = entries.get(replaceId.trim());
            if (target == null || !isVisibleInProject(target, projectKey)) {
                return MemoryWriteResult.replaceTargetNotFound(entry, replaceId.trim(), scope);
            }
        }
        String excludedId = target == null ? null : target.getId();

        Optional<MemoryEntry> duplicate = entries.values().stream()
                .filter(existing -> !existing.getId().equals(excludedId))
                .filter(existing -> MemoryDeduplicator.isDuplicate(existing, entry))
                .findFirst();
        if (duplicate.isPresent()) {
            MemoryEntry verified = duplicate.get().withLastVerifiedAt(entry.getLastVerifiedAt());
            entries.put(verified.getId(), verified);
            if (target != null) {
                removeEntry(target.getId());
            }
            saveToDisk();
            return MemoryWriteResult.duplicate(verified, scope);
        }

        if (!allowConflict) {
            List<MemoryEntry> conflicts = entries.values().stream()
                    .filter(existing -> !existing.getId().equals(excludedId))
                    .filter(existing -> conflictDetector.isConflict(existing, entry))
                    .sorted(Comparator.comparing(MemoryEntry::getTimestamp))
                    .toList();
            if (!conflicts.isEmpty()) {
                return MemoryWriteResult.conflict(entry, conflicts, scope);
            }
        }

        if (target != null) {
            removeEntry(target.getId());
        }
        putEntry(entry);
        saveToDisk();
        return target == null
                ? MemoryWriteResult.stored(entry, scope)
                : MemoryWriteResult.replaced(entry, target, scope);
    }

    /** 用户确认某条记忆仍然成立：刷新最后核实时间。 */
    public synchronized Optional<MemoryEntry> markVerified(String id, Instant verifiedAt) {
        MemoryEntry existing = id == null ? null : entries.get(id.trim());
        if (existing == null) {
            return Optional.empty();
        }
        MemoryEntry verified = existing.withLastVerifiedAt(verifiedAt);
        entries.put(verified.getId(), verified);
        saveToDisk();
        return Optional.of(verified);
    }

    private void putEntry(MemoryEntry entry) {
        MemoryEntry previous = entries.put(entry.getId(), entry);
        if (previous != null) {
            tokenCounter.addAndGet(-previous.getTokenCount());
        }
        tokenCounter.addAndGet(entry.getTokenCount());
    }

    private void removeEntry(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            tokenCounter.addAndGet(-removed.getTokenCount());
        }
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        return search(query, limit, null);
    }

    public List<MemoryEntry> search(String query, int limit, String projectKey) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);

        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .filter(entry -> {
                    if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                        return true;
                    }
                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    public List<MemoryEntry> getAll(String projectKey) {
        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            tokenCounter.addAndGet(-removed.getTokenCount());
            saveToDisk();
            return true;
        }
        return false;
    }

    @Override
    public synchronized void clear() {
        entries.clear();
        tokenCounter.set(0);
        saveToDisk();
    }

    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    @Override
    public int size() {
        return entries.size();
    }

    /**
     * 按类型筛选记忆
     */
    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .collect(Collectors.toList());
    }

    public static boolean isVisibleInProject(MemoryEntry entry, String projectKey) {
        String scope = scopeOf(entry);
        if ("global".equals(scope)) {
            return true;
        }
        String entryProject = entry.getMetadata().get("project");
        return projectKey != null && !projectKey.isBlank() && Objects.equals(entryProject, projectKey);
    }

    public static String scopeOf(MemoryEntry entry) {
        String scope = entry.getMetadata().get("scope");
        if ("project".equalsIgnoreCase(scope)) {
            return "project";
        }
        return "global";
    }

    /**
     * 持久化到磁盘
     */
    private void saveToDisk() {
        if (inMemory) {
            return;
        }
        try {
            List<Map<String, Object>> dataList = entries.values().stream()
                    .map(this::entryToMap)
                    .collect(Collectors.toList());
            mapper.writeValue(storageFile, dataList);
        } catch (IOException e) {
            log.warn("长期记忆持久化失败: {}", e.getMessage(), e);
        }
    }

    private static File resolveStorageDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return new File(configuredDir);
        }
        return new File(new File(System.getProperty("user.home"), ".paicli"), "memory");
    }

    /**
     * 从磁盘加载
     */
    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (inMemory) return;
        if (!storageFile.exists()) return;

        try {
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {
                    entries.put(entry.getId(), entry);
                    tokenCounter.addAndGet(entry.getTokenCount());
                }
            }
            log.info("加载了 {} 条长期记忆", entries.size());
        } catch (IOException e) {
            log.warn("加载长期记忆失败: {}", e.getMessage(), e);
        }
    }

    private Map<String, Object> entryToMap(MemoryEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("content", entry.getContent());
        map.put("type", entry.getType().name());
        map.put("timestamp", entry.getTimestamp().toString());
        map.put("lastVerifiedAt", entry.getLastVerifiedAt().toString());
        map.put("metadata", entry.getMetadata());
        map.put("tokenCount", entry.getTokenCount());
        return map;
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry mapToEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));
            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof String timestampValue && !timestampValue.isBlank()) {
                timestamp = Instant.parse(timestampValue);
            }
            Instant lastVerifiedAt = null;
            if (map.get("lastVerifiedAt") instanceof String verifiedValue && !verifiedValue.isBlank()) {
                lastVerifiedAt = Instant.parse(verifiedValue);
            }
            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number n ? n.intValue() : MemoryEntry.estimateTokens(content);
            return new MemoryEntry(id, content, type, timestamp, lastVerifiedAt, metadata, tokenCount);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成记忆状态摘要
     */
    public String getStatusSummary() {
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));

        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L));
    }
}
