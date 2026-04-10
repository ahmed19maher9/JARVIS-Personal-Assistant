package com.jarvis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ConversationSessionLogger {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern emailPattern = Pattern.compile("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ipPattern = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern secretPattern = Pattern.compile("(?i)\\b(api[_-]?key|secret|password|token)\\b\\s*[:=]\\s*\\S+");

    private final String sessionId;
    private final long sessionStartEpochMs;
    private final List<ObjectNode> turns = new ArrayList<>();

    public ConversationSessionLogger() {
        this.sessionId = UUID.randomUUID().toString();
        this.sessionStartEpochMs = System.currentTimeMillis();
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getSessionStartEpochMs() {
        return sessionStartEpochMs;
    }

    public void recordUser(String text) {
        recordTurn("user", text);
    }

    public void recordAssistant(String text) {
        recordTurn("assistant", text);
    }

    public void recordEvent(String name, String detail) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "event");
        node.put("name", name == null ? "" : name);
        node.put("detail", redact(detail == null ? "" : detail));
        node.put("ts", System.currentTimeMillis());
        synchronized (turns) {
            turns.add(node);
        }
    }

    public void recordTool(String tool, String action, String input, String output, boolean success) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool");
        node.put("tool", tool == null ? "" : tool);
        node.put("action", action == null ? "" : action);
        node.put("input", redact(input == null ? "" : input));
        node.put("output", redact(output == null ? "" : output));
        node.put("success", success);
        node.put("ts", System.currentTimeMillis());
        synchronized (turns) {
            turns.add(node);
        }
    }

    private void recordTurn(String role, String text) {
        if (text == null) {
            return;
        }
        String trimmed = redact(text.trim());
        if (trimmed.isEmpty()) {
            return;
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "message");
        node.put("role", role);
        node.put("content", trimmed);
        node.put("ts", System.currentTimeMillis());
        synchronized (turns) {
            turns.add(node);
        }
    }

    public String getFullTranscript() {
        StringBuilder sb = new StringBuilder();
        synchronized (turns) {
            for (ObjectNode turn : turns) {
                if ("message".equals(turn.path("type").asText())) {
                    sb.append(turn.path("role").asText().toUpperCase()).append(": ")
                      .append(turn.path("content").asText()).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    private String redact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String t = emailPattern.matcher(text).replaceAll("<REDACTED_EMAIL>");
        t = ipPattern.matcher(t).replaceAll("<REDACTED_IP>");
        t = secretPattern.matcher(t).replaceAll("$1=<REDACTED>");
        return t;
    }

    /**
     * Appends the dialogue turns to conversations.jsonl in ShareGPT format
     */
    public synchronized void exportSession() {
        if (turns.isEmpty()) return;

        // Snapshot the current turns to avoid concurrent modification while writing to disk
        List<ObjectNode> turnsSnapshot;
        synchronized (turns) {
            turnsSnapshot = new ArrayList<>(turns);
        }

        Path path = Paths.get(JarvisConfig.get().conversationsDir(), "conversations.jsonl");
        CompletableFuture.runAsync(() -> {
            try {
                ObjectNode root = objectMapper.createObjectNode();
                root.put("id", sessionId + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
                
                com.fasterxml.jackson.databind.node.ArrayNode conversationsArr = root.putArray("conversations");

                for (ObjectNode turn : turnsSnapshot) {
                    if ("message".equals(turn.path("type").asText())) {
                        ObjectNode m = conversationsArr.addObject();
                        String role = turn.path("role").asText();
                        m.put("from", "user".equals(role) ? "human" : "gpt");
                        m.put("value", turn.path("content").asText());
                    }
                }

                Files.createDirectories(path.getParent());
                try (FileWriter fw = new FileWriter(path.toFile(), true)) {
                    fw.write(objectMapper.writeValueAsString(root) + "\n");
                }
            } catch (IOException e) {
                System.err.println("❌ Failed to export conversation session: " + e.getMessage());
            }
        });
    }

    /**
     * Saves neural insights to memories.jsonl
     */
    public synchronized void exportMemory(String insights) {
        if (insights == null || insights.isBlank()) return;

        Path path = Paths.get(JarvisConfig.get().conversationsDir(), "memories.jsonl");
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("id", "MEM_" + UUID.randomUUID().toString().substring(0, 8));
            root.put("type", "insight");
            root.put("content", insights);
            root.put("ts", System.currentTimeMillis());

            Files.createDirectories(path.getParent());
            try (FileWriter fw = new FileWriter(path.toFile(), true)) {
                fw.write(objectMapper.writeValueAsString(root) + "\n");
            }
        } catch (IOException e) {
            System.err.println("❌ Failed to append to memories.jsonl: " + e.getMessage());
        }
    }
}
