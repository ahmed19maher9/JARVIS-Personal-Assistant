package com.jarvis;

import javafx.application.Platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.Collections;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public class ServiceHealthMonitor {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "jarvis-health-monitor");
        t.setDaemon(true);
        return t;
    });
    
    private final java.util.concurrent.ExecutorService workerPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "health-worker");
        t.setDaemon(true);
        return t;
    });

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .proxy(HttpClient.Builder.NO_PROXY)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final Consumer<ServiceStatus> onStatus;
    private final Consumer<java.util.List<String>> onModelsDiscovery;

    private final Map<JarvisModule, ServiceStatus> lastStatuses = 
            Collections.synchronizedMap(new EnumMap<>(JarvisModule.class));
    private java.util.List<String> lastDiscoveredModels = new ArrayList<>();
    private final Map<JarvisModule, Long> startingTimes = 
            Collections.synchronizedMap(new EnumMap<>(JarvisModule.class));
    private volatile boolean running = false;

    public ServiceHealthMonitor(Consumer<ServiceStatus> onStatus, Consumer<java.util.List<String>> onModelsDiscovery) {
        this.onStatus = Objects.requireNonNull(onStatus, "onStatus");
        this.onModelsDiscovery = Objects.requireNonNull(onModelsDiscovery, "onModelsDiscovery");
    }

    public void start() {
        running = true;
        // Increased polling frequency to 15s to be more responsive to service changes
        scheduler.scheduleAtFixedRate(this::pollOnce, 5, 15, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    private void pollOnce() {
        if (!running) return;
        // Execute all checks in parallel to prevent one slow module from lagging the monitor
        CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> publish(checkMouth()), workerPool),
            CompletableFuture.runAsync(() -> publish(checkBrain()), workerPool),
            CompletableFuture.runAsync(() -> publish(checkEars()), workerPool),
            CompletableFuture.runAsync(() -> publish(checkEyes()), workerPool),
            CompletableFuture.runAsync(() -> publish(checkCore()), workerPool),
            CompletableFuture.runAsync(() -> publish(checkRepulsors()), workerPool),
            CompletableFuture.runAsync(() -> publish(checkMemory()), workerPool),
            CompletableFuture.runAsync(this::refreshModelDiscovery, workerPool)
        );
    }

    /**
     * Updates the internal status cache without triggering listeners.
     * Used to synchronize state (like STARTING) from the controller.
     */
    public void updateCache(ServiceStatus status) {
        lastStatuses.put(status.module(), status);
    }

    private ServiceStatus checkMemory() {
        return timedGet(JarvisModule.LONG_TERM_MEMORY, URI.create(JarvisConfig.get().memoryApiBaseUrl() + "/health"));
    }

    private void publishUnknown(JarvisModule module) {
        publish(new ServiceStatus(module, ServiceState.UNKNOWN, -1, "external"));
    }

    private void publish(ServiceStatus status) {
        ServiceStatus prev = lastStatuses.get(status.module());
        
        // Track start time for STARTING states
        if (status.state() == ServiceState.STARTING) {
            startingTimes.put(status.module(), System.currentTimeMillis());
        }
        // Clean up start time when module reaches READY or OFFLINE
        else if (status.state() == ServiceState.READY || status.state() == ServiceState.OFFLINE) {
            startingTimes.remove(status.module());
        }
        
        // Priority Protection: If controller has marked a module as STARTING, 
        // do not let health monitor overwrite it with OFFLINE.
        // The Python script will eventually report READY via the API once initialized.
        if (prev != null && prev.state() == ServiceState.STARTING && status.state() == ServiceState.OFFLINE) {
            Long startTime = startingTimes.get(status.module());
            if (startTime != null && (System.currentTimeMillis() - startTime) < 30000) {
                return; // Keep STARTING status for at least 30 seconds
            }
        }

        if (prev != null
                && prev.state() == status.state()
                && prev.latencyMs() == status.latencyMs()
                && Objects.equals(prev.detail(), status.detail())) {
            return;
        }
        lastStatuses.put(status.module(), status);
        Platform.runLater(() -> onStatus.accept(status));
    }

    private ServiceStatus checkMouth() {
        return timedGet(JarvisModule.VOCAL_MOUTH, URI.create("http://127.0.0.1:8880/health"));
    }

    private ServiceStatus checkEars() {
        return timedGet(JarvisModule.AURAL_EARS, URI.create("http://127.0.0.1:8881/health"));
    }

    private ServiceStatus checkEyes() {
        return timedGet(JarvisModule.OCULAR_EYES, URI.create("http://127.0.0.1:8882/health"));
    }

    private ServiceStatus checkBrain() {
        // Use /api/ps instead of /api/tags to see models currently loaded in VRAM/RAM
        // Prioritize 127.0.0.1 to avoid Windows IPv6 resolution delays
        return timedGet(JarvisModule.CORTEX_BRAIN, URI.create("http://127.0.0.1:11434/api/ps"));
    }

    /**
     * Queries Ollama for all available tags (models) and notifies if the list has changed.
     */
    private void refreshModelDiscovery() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(JarvisConfig.get().ollamaApiBaseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode modelsNode = root.get("models");
                if (modelsNode != null && modelsNode.isArray()) {
                    java.util.List<String> currentModels = new ArrayList<>();
                    for (JsonNode m : modelsNode) {
                        String name = m.path("name").asText("");
                        if (!name.isEmpty()) currentModels.add(name);
                    }
                    // Only trigger UI update if the model set has actually changed
                    if (!currentModels.equals(lastDiscoveredModels)) {
                        lastDiscoveredModels = currentModels;
                        Platform.runLater(() -> onModelsDiscovery.accept(currentModels));
                    }
                }
            }
        } catch (Exception e) {
            // Silent ignore, but discovery will retry next poll
        }
    }

    private ServiceStatus checkCore() {
        return timedGet(JarvisModule.CORE_REACTOR, URI.create("http://127.0.0.1:8082/health"));
    }

    private ServiceStatus checkRepulsors() {
        return timedGet(JarvisModule.REPULSORS, URI.create("http://127.0.0.1:8084/health"));
    }

    private ServiceStatus timedGet(JarvisModule module, URI uri) {
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            double cpu = 0, ram = 0, gpu = 0;
            String modelName = null;
            ServiceState state = ServiceState.UNKNOWN;

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                state = ServiceState.READY; // Default success state
                try {
                    JsonNode root = objectMapper.readTree(resp.body());
                    
                    // Dynamically resolve state from the module's own status report
                    String statusStr = root.path("status").asText("ready");
                    try {
                        state = ServiceState.valueOf(statusStr.toUpperCase());
                    } catch (Exception ignored) {}

                    // Extract loaded model name from Ollama /api/ps response
                    if (root.has("models") && root.get("models").isArray() && root.get("models").size() > 0) {
                        modelName = root.get("models").get(0).path("name").asText();
                    }

                    if (root.has("metrics")) {
                        JsonNode mNode = root.get("metrics");
                        cpu = mNode.path("cpu").asDouble();
                        ram = mNode.path("ram_mb").asDouble();
                        gpu = mNode.path("gpu").asDouble();
                    }
                } catch (Exception ignored) {}

                // Only apply latency degradation if the module claims to be READY.
                // This prevents overriding states like BUSY or STARTING.
                if (state == ServiceState.READY && ms > 2500) {
                    state = ServiceState.DEGRADED;
                }
                return new ServiceStatus(module, state, ms, "ok", cpu, ram, gpu, modelName);
            }
            return new ServiceStatus(module, ServiceState.OFFLINE, ms, "http " + resp.statusCode());
        } catch (Exception e) {
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return new ServiceStatus(module, ServiceState.OFFLINE, ms, e.getClass().getSimpleName());
        }
    }
}
