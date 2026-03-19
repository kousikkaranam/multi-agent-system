package com.agent.controller;

import com.agent.terminal.TerminalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/terminal")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TerminalController {

    private final TerminalService terminalService;

    /**
     * Get all pending commands waiting for approval.
     */
    @GetMapping("/pending")
    public ResponseEntity<List<Map<String, String>>> getPending() {
        var pending = terminalService.getPendingCommands().values().stream()
                .map(cmd -> Map.of(
                        "id", cmd.id(),
                        "command", cmd.command(),
                        "reason", cmd.reason()
                ))
                .toList();
        return ResponseEntity.ok(pending);
    }

    /**
     * Approve and execute a pending command.
     */
    @PostMapping("/approve/{id}")
    public ResponseEntity<?> approve(@PathVariable String id) {
        try {
            var result = terminalService.approveAndRun(id);
            return ResponseEntity.ok(Map.of(
                    "command", result.command(),
                    "exitCode", result.exitCode(),
                    "output", result.output(),
                    "success", result.success()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Reject a pending command.
     */
    @PostMapping("/reject/{id}")
    public ResponseEntity<?> reject(@PathVariable String id) {
        terminalService.rejectCommand(id);
        return ResponseEntity.ok(Map.of("status", "rejected", "id", id));
    }
}
