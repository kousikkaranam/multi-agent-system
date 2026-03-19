package com.agent.controller;

import com.agent.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    /**
     * Connect to a project folder.
     */
    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(@RequestBody Map<String, String> body) {
        String path = body.get("path");
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "path is required"));
        }
        try {
            return ResponseEntity.ok(workspaceService.connect(path));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get connection status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        if (!workspaceService.isConnected()) {
            return ResponseEntity.ok(Map.of("connected", false));
        }
        return ResponseEntity.ok(Map.of(
                "connected", true,
                "path", workspaceService.getWorkspacePath().toString(),
                "name", workspaceService.getWorkspacePath().getFileName().toString()
        ));
    }

    /**
     * Get file tree.
     */
    @GetMapping("/tree")
    public ResponseEntity<?> getTree() {
        try {
            return ResponseEntity.ok(workspaceService.getFileTree());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Read a file.
     */
    @GetMapping("/file")
    public ResponseEntity<?> readFile(@RequestParam String path) {
        try {
            String content = workspaceService.readFile(path);
            return ResponseEntity.ok(Map.of("path", path, "content", content));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Write a file.
     */
    @PostMapping("/file")
    public ResponseEntity<?> writeFile(@RequestBody Map<String, String> body) {
        try {
            workspaceService.writeFile(body.get("path"), body.get("content"));
            return ResponseEntity.ok(Map.of("status", "written", "path", body.get("path")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete a file.
     */
    @DeleteMapping("/file")
    public ResponseEntity<?> deleteFile(@RequestParam String path) {
        try {
            workspaceService.deleteFile(path);
            return ResponseEntity.ok(Map.of("status", "deleted", "path", path));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Search files.
     */
    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody Map<String, String> body) {
        try {
            var results = workspaceService.searchFiles(
                    body.get("query"),
                    body.getOrDefault("pattern", null)
            );
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
