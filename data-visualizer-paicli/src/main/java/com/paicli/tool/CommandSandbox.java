package com.paicli.tool;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in macOS Seatbelt wrapper for {@code execute_command}.
 *
 * <p>The profile is passed as one argv item to {@code sandbox-exec}; neither the
 * profile nor the command is interpolated into another shell command. The
 * sandbox may read the workspace and explicitly selected runtime directories,
 * may write only below the workspace root, and has no network access.
 */
final class CommandSandbox {

    static final Path DEFAULT_EXECUTABLE = Path.of("/usr/bin/sandbox-exec");
    private static final Path DEV_NULL = Path.of("/dev/null");
    private static final long PROBE_TIMEOUT_SECONDS = 3;
    private static final String STATE_DIRECTORY = ".paicli-command-sandbox";
    private static final List<Path> SYSTEM_READ_DIRECTORY_ROOTS = List.of(
            Path.of("/System"),
            Path.of("/bin"),
            Path.of("/sbin"),
            Path.of("/usr/bin"),
            Path.of("/usr/sbin"),
            Path.of("/usr/lib"),
            Path.of("/usr/libexec"),
            Path.of("/usr/share"),
            Path.of("/Library/Java"),
            Path.of("/Library/Developer"),
            Path.of("/Applications/Xcode.app/Contents/Developer"),
            Path.of("/opt/homebrew/bin"),
            Path.of("/opt/homebrew/sbin"),
            Path.of("/opt/homebrew/Cellar"),
            Path.of("/opt/homebrew/lib"),
            Path.of("/opt/homebrew/share"),
            Path.of("/usr/local/bin"),
            Path.of("/usr/local/sbin"),
            Path.of("/usr/local/Cellar"),
            Path.of("/usr/local/lib"),
            Path.of("/usr/local/share")
    );
    private static final List<Path> SYSTEM_READ_LITERAL_PATHS = List.of(
            Path.of("/dev/null"),
            Path.of("/dev/random"),
            Path.of("/dev/urandom"),
            Path.of("/dev/zero")
    );

    private final Path root;
    private final Path executable;
    private final Path homeDirectory;
    private final Path tempDirectory;
    private final Path javaHome;
    private final String inheritedPath;
    private final List<Path> readRoots;
    private final String profile;

    static CommandSandbox enable(Path root) {
        return enable(
                root,
                DEFAULT_EXECUTABLE,
                System.getenv(),
                configuredJavaHome());
    }

    static CommandSandbox enable(Path root,
                                 Path executable,
                                 Map<String, String> inheritedEnvironment,
                                 Path javaHome) {
        CommandSandbox sandbox = new CommandSandbox(
                root,
                executable,
                inheritedEnvironment,
                javaHome);
        if (!Files.isRegularFile(sandbox.executable) || !Files.isExecutable(sandbox.executable)) {
            throw new IllegalStateException(
                    "命令沙箱不可用: " + sandbox.executable + " 不存在或不可执行");
        }
        sandbox.probe();
        return sandbox;
    }

    CommandSandbox(Path root,
                   Path executable,
                   Map<String, String> inheritedEnvironment,
                   Path javaHome) {
        this.root = canonicalDirectory(root, "command sandbox root");
        if (executable == null) {
            throw new IllegalArgumentException("sandbox executable must not be null");
        }
        this.executable = executable.toAbsolutePath().normalize();
        this.homeDirectory = this.root.resolve(STATE_DIRECTORY).resolve("home");
        this.tempDirectory = this.root.resolve(STATE_DIRECTORY).resolve("tmp");
        this.javaHome = canonicalOptionalDirectory(javaHome);
        this.inheritedPath = inheritedEnvironment == null
                ? ""
                : inheritedEnvironment.getOrDefault("PATH", "");
        this.readRoots = collectReadRoots(this.root, this.javaHome, inheritedEnvironment);
        this.profile = buildProfile(this.root, this.readRoots);
    }

    Invocation prepare(Path workingDirectory, String command) throws IOException {
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IOException("命令沙箱不可用: " + executable + " 不存在或不可执行");
        }
        if (command == null || command.isBlank()) {
            throw new IOException("命令沙箱拒绝空命令");
        }

        Path workingRoot = requireInsideRoot(workingDirectory, "command working directory");
        ensurePrivateDirectory(root.resolve(STATE_DIRECTORY));
        ensurePrivateDirectory(homeDirectory);
        ensurePrivateDirectory(tempDirectory);

        return new Invocation(
                List.of(
                        executable.toString(),
                        "-p",
                        profile,
                        "/bin/bash",
                        "-c",
                        command),
                workingRoot);
    }

    void configureEnvironment(Map<String, String> environment) {
        if (environment == null) {
            throw new IllegalArgumentException("command environment must not be null");
        }
        environment.put("HOME", homeDirectory.toString());
        environment.put("TMPDIR", tempDirectory.toString());
        environment.put("TMP", tempDirectory.toString());
        environment.put("TEMP", tempDirectory.toString());
        environment.put("XDG_CACHE_HOME", homeDirectory.resolve(".cache").toString());
        environment.put("XDG_CONFIG_HOME", homeDirectory.resolve(".config").toString());
        environment.put("PATH", commandPath());
    }

    Path root() {
        return root;
    }

    Path homeDirectory() {
        return homeDirectory;
    }

    Path tempDirectory() {
        return tempDirectory;
    }

    String profile() {
        return profile;
    }

    List<String> arguments(String command) {
        return List.of(
                executable.toString(),
                "-p",
                profile,
                "/bin/bash",
                "-c",
                command);
    }

    private void probe() {
        Process process = null;
        try {
            Invocation invocation = prepare(root, "/usr/bin/true");
            ProcessBuilder builder = new ProcessBuilder(invocation.arguments());
            builder.directory(invocation.workingDirectory().toFile());
            builder.redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.clear();
            configureEnvironment(environment);
            environment.put("LANG", "C");
            environment.put("LC_ALL", "C");
            process = builder.start();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                throw new IllegalStateException("命令沙箱不可用: Seatbelt probe 超时");
            }
            if (process.exitValue() != 0) {
                String diagnostic = new String(
                        process.getInputStream().readNBytes(4_096), StandardCharsets.UTF_8)
                        .replaceAll("[\\r\\n]+", " ")
                        .trim();
                throw new IllegalStateException(
                        "命令沙箱不可用: Seatbelt probe exit code=" + process.exitValue()
                                + (diagnostic.isBlank() ? "" : " (" + diagnostic + ")"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("命令沙箱不可用: Seatbelt probe 被中断", e);
        } catch (IOException | SecurityException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("命令沙箱不可用: Seatbelt probe 启动失败", e);
        }
    }

    private String commandPath() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        if (javaHome != null) {
            entries.add(javaHome.resolve("bin").toString());
        }
        entries.add("/usr/bin");
        entries.add("/bin");
        entries.add("/usr/sbin");
        entries.add("/sbin");
        if (!inheritedPath.isBlank()) {
            for (String entry : inheritedPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    entries.add(entry);
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private Path requireInsideRoot(Path candidate, String label) throws IOException {
        if (candidate == null) {
            throw new IOException(label + " must not be null");
        }
        Path real = candidate.toAbsolutePath().normalize().toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException(label + " is outside command sandbox root: " + real);
        }
        return real;
    }

    private void ensurePrivateDirectory(Path directory) throws IOException {
        Path relative = root.relativize(directory.toAbsolutePath().normalize());
        if (relative.startsWith("..")) {
            throw new IOException("sandbox state directory escapes workspace: " + directory);
        }
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("sandbox state path is not a private directory: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
            Path real = current.toRealPath();
            if (!real.startsWith(root)) {
                throw new IOException("sandbox state directory escapes workspace: " + current);
            }
        }
    }

    private static Path configuredJavaHome() {
        String value = System.getProperty("java.home");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static Path canonicalDirectory(Path path, String label) {
        if (path == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        try {
            Path real = path.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException(label + " must be a directory: " + path);
            }
            rejectControlCharacters(real, label);
            return real;
        } catch (IOException e) {
            throw new IllegalArgumentException(label + " is unavailable: " + path, e);
        }
    }

    private static Path canonicalOptionalDirectory(Path path) {
        if (path == null) {
            return null;
        }
        try {
            Path real = path.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(real)) {
                return null;
            }
            rejectControlCharacters(real, "runtime path");
            return real;
        } catch (IOException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static List<Path> collectReadRoots(Path workspace,
                                               Path javaHome,
                                               Map<String, String> environment) {
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(workspace);
        for (Path systemRoot : SYSTEM_READ_DIRECTORY_ROOTS) {
            Path canonical = canonicalOptionalDirectory(systemRoot);
            if (canonical != null) {
                roots.add(canonical);
            }
        }
        if (javaHome != null) {
            roots.add(javaHome);
        }
        if (environment != null) {
            addConfiguredRuntimeRoot(roots, environment.get("JAVA_HOME"));
            addPathRuntimeRoots(roots, environment.get("PATH"));
        }
        return List.copyOf(roots);
    }

    private static void addConfiguredRuntimeRoot(Set<Path> roots, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            Path canonical = canonicalOptionalDirectory(Path.of(raw));
            if (canonical != null) {
                roots.add(canonical);
            }
        } catch (RuntimeException ignored) {
            // An invalid inherited runtime path is omitted; the sandbox stays closed.
        }
    }

    private static void addPathRuntimeRoots(Set<Path> roots, String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return;
        }
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(entry);
                if (!path.isAbsolute()) {
                    continue;
                }
                Path canonical = canonicalOptionalDirectory(path);
                if (canonical != null && isNarrowRuntimeDirectory(canonical)) {
                    roots.add(canonical);
                }
            } catch (RuntimeException ignored) {
                // Invalid PATH entries remain unusable instead of widening the profile.
            }
        }
    }

    private static boolean isNarrowRuntimeDirectory(Path directory) {
        Path fileName = directory.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return name.equals("bin") || name.equals("sbin") || name.equals("shims");
    }

    private static String buildProfile(Path workspace, Collection<Path> readableRoots) {
        StringBuilder profile = new StringBuilder();
        profile.append("(version 1)\n")
                .append("(deny default)\n")
                .append("(deny network*)\n")
                .append("(allow process*)\n")
                .append("(allow sysctl-read)\n")
                // macOS language runtimes use Mach services for basic process/runtime setup.
                // This remains broader than container/VM IPC isolation; network and filesystem
                // policy are still independently denied/restricted by this Seatbelt profile.
                .append("(allow mach-lookup)\n")
                .append("(allow file-read*\n");
        for (Path readableRoot : readableRoots) {
            appendPathFilters(profile, readableRoot);
        }
        for (Path readablePath : SYSTEM_READ_LITERAL_PATHS) {
            if (Files.exists(readablePath)) {
                appendLiteralFilter(profile, readablePath);
            }
        }
        profile.append(")\n")
                .append("(allow file-write*\n");
        appendPathFilters(profile, workspace);
        profile.append("  (literal ").append(seatbeltString(DEV_NULL)).append(")\n");
        profile.append(")\n");
        return profile.toString();
    }

    private static void appendPathFilters(StringBuilder profile, Path path) {
        String literal = seatbeltString(path);
        profile.append("  (literal ").append(literal).append(")\n")
                .append("  (subpath ").append(literal).append(")\n");
    }

    private static void appendLiteralFilter(StringBuilder profile, Path path) {
        profile.append("  (literal ").append(seatbeltString(path)).append(")\n");
    }

    private static String seatbeltString(Path path) {
        String value = path.toString();
        rejectControlCharacters(path, "sandbox profile path");
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private static void rejectControlCharacters(Path path, String label) {
        String value = path.toString();
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException(label + " contains a control character");
            }
        }
    }

    record Invocation(List<String> arguments, Path workingDirectory) {
        Invocation {
            arguments = List.copyOf(new ArrayList<>(arguments));
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
        }
    }
}
