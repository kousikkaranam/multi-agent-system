package com.agent.terminal;

import com.agent.workspace.WorkspaceService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Terminal command execution with approval queue.
 * Agent proposes → user approves in UI → command runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TerminalService {

    private final WorkspaceService workspaceService;

    /**
     * Pending commands waiting for user approval.
     */
    @Getter
    private final ConcurrentHashMap<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    /**
     * Completed command results (kept for agent to read back).
     */
    @Getter
    private final ConcurrentHashMap<String, CommandResult> completedCommands = new ConcurrentHashMap<>();

    /**
     * Agent proposes a command. Returns an ID for tracking.
     */
    public String proposeCommand(String command, String reason) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        pendingCommands.put(id, new PendingCommand(id, command, reason));
        log.info("Command proposed [{}]: {} (reason: {})", id, command, reason);
        return id;
    }

    /**
     * User approves a pending command. Executes it.
     */
    public CommandResult approveAndRun(String commandId) {
        PendingCommand pending = pendingCommands.remove(commandId);
        if (pending == null) {
            throw new IllegalArgumentException("No pending command with id: " + commandId);
        }

        CommandResult result = executeCommand(pending.command());
        completedCommands.put(commandId, result);
        return result;
    }

    /**
     * User rejects a pending command.
     */
    public void rejectCommand(String commandId) {
        PendingCommand removed = pendingCommands.remove(commandId);
        if (removed != null) {
            completedCommands.put(commandId, new CommandResult(
                    removed.command(), -1, "", "Command rejected by user", false
            ));
            log.info("Command rejected [{}]: {}", commandId, removed.command());
        }
    }

    /**
     * Get result of a completed command (for agent to read back).
     */
    public CommandResult getResult(String commandId) {
        return completedCommands.get(commandId);
    }

    private CommandResult executeCommand(String command) {
        try {
            log.info("Executing command: {}", command);

            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            if (workspaceService.isConnected()) {
                pb.directory(workspaceService.getWorkspacePath().toFile());
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < 500) {
                    output.append(line).append("\n");
                    lineCount++;
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(command, -1, output.toString(), "Command timed out (60s)", false);
            }

            int exitCode = process.exitValue();
            return new CommandResult(command, exitCode, output.toString(), null, exitCode == 0);

        } catch (Exception e) {
            log.error("Command execution failed: {}", e.getMessage());
            return new CommandResult(command, -1, "", "Error: " + e.getMessage(), false);
        }
    }

    public record PendingCommand(String id, String command, String reason) {}

    public record CommandResult(String command, int exitCode, String output, String error, boolean success) {}
}
