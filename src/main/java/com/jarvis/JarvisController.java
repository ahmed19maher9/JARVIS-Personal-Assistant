package com.jarvis;

import javafx.application.Platform;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main controller for JARVIS application
 * Integrates UI, Python services, and administrative commands
 */
public class JarvisController {
    
    private static final Logger logger = LoggerFactory.getLogger(JarvisController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final JarvisApplication application;
    private final PythonService pythonService;
    private final AdminCommandService adminService;
    private final JarvisApiServer apiServer;
    private final CortexManager cortexManager;
    private final ConversationSessionLogger conversationLogger;
    private final Map<JarvisModule, ServiceStatus> currentMetrics = new ConcurrentHashMap<>();
    
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean startupSoundPlayed = new AtomicBoolean(false);
    private Timeline pipelineTimeline;
    private final Map<JarvisModule, Long> startTimes = new ConcurrentHashMap<>();
    
    // UI components (will be injected)
    private Label statusLabel;
    private Label listeningLabel;
    private Label responseLabel;
    private Label earInputDisplay;
    private Label mouthResponseDisplay;
    private AudioVisualizer leftVisualizer;
    private AudioVisualizer rightVisualizer;
    private ServiceHealthMonitor healthMonitor;

    private volatile MediaPlayer mediaPlayer;
    
    public JarvisController(JarvisApplication application) {
        this.application = application;
        this.pythonService = new PythonService(this);
        this.adminService = new AdminCommandService();
        this.apiServer = new JarvisApiServer(this);
        this.cortexManager = new CortexManager(this);
        this.conversationLogger = new ConversationSessionLogger();

        // Initialize metrics with OFFLINE so transitions are detected from the first action
        for (JarvisModule m : JarvisModule.values()) {
            currentMetrics.put(m, new ServiceStatus(m, ServiceState.OFFLINE, -1, "Initialization"));
        }
    }
    
    public void setHealthMonitor(ServiceHealthMonitor healthMonitor) {
        this.healthMonitor = healthMonitor;
    }

    /**
     * Initialize the JARVIS system
     */
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Initializing JARVIS system...");
                
                updateStatus("Initializing JARVIS systems...");
                
                // Escalate process priority to High for maximum resource allocation
                elevateProcessPriority();

                // 1. Start API server immediately so Ear/Eye can connect
                apiServer.startServer().thenAccept(success -> {
                    if (success) {
                        logger.info("API server ready for external commands");
                        
                        // Wait a moment for server to fully start
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        
                        // Initialize Python services after API server is ready
                        initializePythonServices();
                        
                    } else {
                        logger.error("API server critical failure");
                    }
                });
                
            } catch (Exception e) {
                logger.error("Error during JARVIS initialization", e);
                updateStatus("Initialization failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Escalate the priority of the current JARVIS process to HIGH
     */
    private void elevateProcessPriority() {
        try {
            long pid = ProcessHandle.current().pid();
            String cmd = String.format("powershell -Command \"(Get-Process -Id %d).PriorityClass = 'High'\"", pid);
            adminService.executeSilentCommand(cmd, 10);
            logger.info("🚀 [SYSTEM] JARVIS process priority escalated to HIGH (PID: {})", pid);
        } catch (Exception e) {
            logger.warn("⚠️ [SYSTEM] Failed to escalate process priority: {}", e.getMessage());
        }
    }

    private void initializePythonServices() {
        try {
            // Initialize Python services
            Boolean pythonInitialized = pythonService.initializeServices().get(30, java.util.concurrent.TimeUnit.SECONDS);
            
            if (!pythonInitialized) {
                updateStatus("Failed to initialize Python services");
                return;
            }
            
            // Update indicators to show services are starting
            application.updateEarIndicator(true);
            application.updateMouthIndicator(true);
            updateStatus("Python services initializing...");
            
            // Get system information
            adminService.getSystemInfo().thenAccept(systemInfo -> {
                if (systemInfo.isSuccess()) {
                    logger.info("System information retrieved");
                    updateStatus("JARVIS systems online - Ready for commands");
                    // Set indicators to normal operation state
                    application.updateEarIndicator(false);
                    application.updateMouthIndicator(false);
                } else {
                    logger.warn("Failed to get system info: " + systemInfo.getInfo());
                }
            });
            
            logger.info("JARVIS initialization complete");
        } catch (InterruptedException e) {
            logger.error("Python services initialization interrupted", e);
            updateStatus("Initialization interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            logger.error("Python services initialization failed", e);
            updateStatus("Initialization failed: " + e.getMessage());
        } catch (java.util.concurrent.TimeoutException e) {
            logger.error("Python services initialization timeout", e);
            updateStatus("Initialization timeout");
        }
    }
    
    /**
     * Start monitoring voice activity
     */
    private void startVoiceMonitoring() {
        CompletableFuture.runAsync(() -> {
            while (true) {
                try {
                    // Simulate voice activity detection
                    // In real implementation, this would interface with the ear service
                    
                    Thread.sleep(1000);
                    
                    // Randomly simulate voice detection for demo
                    if (Math.random() > 0.95) {
                        simulateVoiceCommand();
                    }
                    
                } catch (InterruptedException e) {
                    logger.info("Voice monitoring interrupted");
                    break;
                } catch (Exception e) {
                    logger.error("Error in voice monitoring", e);
                }
            }
        });
    }
    
    /**
     * Simulate voice command processing
     */
    private void simulateVoiceCommand() {
        if (isListening.get() || isProcessing.get()) {
            return; // Already busy
        }
        
        Platform.runLater(() -> {
            isListening.set(true);
            updateListeningStatus("🎤 LISTENING... ANALYZING");
            leftVisualizer.setActive(true);
            application.updateEarIndicator(true);
            updateStatus("Voice command detected - Processing...");
        });
        
        // Simulate processing time
        CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
            Platform.runLater(() -> {
                isListening.set(false);
                isProcessing.set(true);
                updateListeningStatus("🎤 Processing command...");
                leftVisualizer.setActive(false);
                application.updateEarIndicator(false);
                updateResponseStatus("🔊 GENERATING RESPONSE");
                rightVisualizer.setActive(true);
                application.updateMouthIndicator(true);
            });
            
            // Process the command
            processVoiceCommand("What time is it?");
        });
    }
    
    /**
     * Process a voice command from ear service
     */
    public void processVoiceCommand(String command) {
        if (isProcessing.get()) {
            logger.info("Command ignored - already processing");
            return;
        }
        
        logger.info("Processing voice command: " + command);
        
        if (command.toLowerCase().contains("capture screen")) {
            conversationLogger.recordTool("ocular", "capture_screen", command, "", true);
            pythonService.triggerScreenCapture();
            updateStatus("Ocular system: Initializing screen capture...");
            return;
        }

        Platform.runLater(() -> {
            isListening.set(true);
            updateListeningStatus("🎤 PROCESSING COMMAND...");
            leftVisualizer.setActive(true);
            application.updateEarIndicator(true);
            updateStatus("Processing: " + command);
        });
        
        // New Multi-Stage Pipeline processing
        long brainStart = System.currentTimeMillis();
        Platform.runLater(() -> startPipelineTimer("Neural Processing", ""));

        // Enriched Pipeline: Memory Query -> Neural Processing -> Vocal Synthesis
        pythonService.queryMemory(command).thenCompose(context -> {
            String enrichedCommand = command;
            if (context != null && !context.isBlank()) {
                enrichedCommand = "### CONTEXT FROM MEMORY:\n" + context + "\n\n### USER COMMAND:\n" + command;
                logger.info("🧠 [MEMORY] Enriched prompt with context from vector store");
            }
            return pythonService.getBrainResponseAsync(enrichedCommand);
        }).thenAccept((String brainResponse) -> { // Changed to thenAccept as the next step doesn't return a new CompletableFuture
            long brainEnd = System.currentTimeMillis();
            double brainDuration = (brainEnd - brainStart) / 1000.0;
            final String brainStageResult = String.format("(Neural Processing : %.1fs)", brainDuration);
            Platform.runLater(() -> {
                isListening.set(false);
                isProcessing.set(true);
                leftVisualizer.setActive(false);
                application.updateEarIndicator(false);
                updateListeningStatus("🎤 BRAIN RESPONSE READY");
                updateMouthResponseDisplay(brainResponse);
                
                stopPipelineTimer();
                startPipelineTimer("Vocal Synthesis", brainStageResult + " -> ");
            });

            final long mouthStart = System.currentTimeMillis();
            pythonService.generateSpeech(brainResponse).thenAccept(audioFile -> {
                if (audioFile != null && !audioFile.isBlank() && !audioFile.startsWith("ERROR:")) {
                    long mouthEnd = System.currentTimeMillis();
                    double mouthDuration = (mouthEnd - mouthStart) / 1000.0;
                    String mouthStageResult = String.format("(Vocal Synthesis : %.1fs)", mouthDuration);
                    
                    Platform.runLater(() -> {
                        stopPipelineTimer();
                        updateStatus(brainStageResult + " -> " + mouthStageResult);
                        updateResponseStatus("🔊 SPEAKING RESPONSE");
                        rightVisualizer.setActive(true);
                        application.updateMouthIndicator(true);

                        // Pre-emptive log for perceived speed
                        logger.info("🔊 [SYSTEM] Executing playback for: {}", audioFile);

                        playAudioFile(audioFile, this::resetUIAfterSpeech);
                    });
                } else {
                    Platform.runLater(() -> {
                        stopPipelineTimer();
                        updateStatus("Mouth Failure: " + audioFile);
                        isProcessing.set(false);
                    });
                }
            });
        })
        .exceptionally(ex -> {
            Platform.runLater(() -> {
                stopPipelineTimer();
                String error = (ex != null && ex.getMessage() != null) ? ex.getMessage() : "Unknown Pipeline Error";
                updateStatus("Pipeline Failure: " + error);
                isProcessing.set(false);
                isListening.set(false);
            });
            return null;
        });
    }

    /**
     * ADVANCED: Parses the brain response for structured tool commands.
     * e.g., "Certainly. [[ACTUATE:CLICK:500:500]]"
     */
    private void handleAutonomousActions(String response) {
        Pattern pattern = Pattern.compile("\\[\\[(.*?)\\]\\]");
        Matcher matcher = pattern.matcher(response);
        while (matcher.find()) {
            String command = matcher.group(1);
            logger.info("🤖 [AUTONOMOUS] Executing tool command: {}", command);
            // Implementation: Route to pythonService.executeActuator(command)
        }
    }

    private void resetUIAfterSpeech() {
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() -> {
            Platform.runLater(() -> {
                isProcessing.set(false);
                rightVisualizer.setActive(false);
                application.updateMouthIndicator(false);
                updateListeningStatus("🎤 LISTENING... ANALYZING");
                updateResponseStatus("🔊 PREPARING RESPONSE");
                updateStatus("JARVIS systems online - Ready for commands");
            });
        });
    }

    private void startPipelineTimer(String description, String prefix) {
        Platform.runLater(() -> {
            stopPipelineTimer();
            final long startTime = System.currentTimeMillis();
            pipelineTimeline = new Timeline(new KeyFrame(Duration.millis(200), e -> {
                double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                // Direct UI update to avoid console logging spam from frequent timer updates
                if (statusLabel != null) {
                    statusLabel.setText("System Status: " + prefix + String.format("(%s : %.1fs)", description, elapsed));
                }
            }));
            pipelineTimeline.setCycleCount(Animation.INDEFINITE);
            pipelineTimeline.play();
        });
    }

    private void stopPipelineTimer() {
        if (pipelineTimeline != null) {
            pipelineTimeline.stop();
            pipelineTimeline = null;
        }
    }

    /**
     * Handles target data from the Ocular Analysis Layer
     */
    public void handleTargetDetection(String targetLabel) {
        Platform.runLater(() -> {
            if (statusLabel != null) {
                statusLabel.setText("System Status: TARGET LOCK: " + targetLabel.toUpperCase());
            }
        });
    }

    /**
     * Processes an external status report from a module
     */
    public void reportModuleStatus(String moduleName, String stateStr, String detail) {
        JarvisModule module = null;
        // Try to match the module by name or display name
        for (JarvisModule m : JarvisModule.values()) {
            if (m.name().equalsIgnoreCase(moduleName) || m.getDisplayName().toLowerCase().contains(moduleName.toLowerCase())) {
                module = m;
                break;
            }
        }

        if (module != null) {
            ServiceState state = ServiceState.UNKNOWN;
            try {
                state = ServiceState.valueOf(stateStr.toUpperCase());
            } catch (Exception ignored) {}
            
            logger.info("📡 [SYSTEM] External status report for {}: {} ({})", module, state, detail);
            onServiceStatusUpdate(new ServiceStatus(module, state, -1, detail));
        }
    }

    /**
     * Unified status update hub to handle UI reporting and transition sounds.
     */
    public synchronized void onServiceStatusUpdate(ServiceStatus status) {
        JarvisModule module = status.module();
        
        // Priority: If we are shutting down, ignore everything except OFFLINE confirmations
        if (isShuttingDown.get() && status.state() != ServiceState.OFFLINE) {
            return;
        }

        // Progress Parsing: Look for conversion progress in BUSY detail messages
        if (module == JarvisModule.CORTEX_BRAIN && status.state() == ServiceState.BUSY) {
            String detail = status.detail();
            if (detail != null && detail.contains("pages processed")) {
                Pattern p = Pattern.compile("(\\d+)/(\\d+)");
                Matcher m = p.matcher(detail);
                if (m.find()) {
                    application.updateGymProgress(Double.parseDouble(m.group(1)) / Double.parseDouble(m.group(2)));
                }
            }
        }

        // Synchronize the status back to the health monitor's cache to ensure 
        // STARTING/BUSY protection logic works correctly during polling.
        if (healthMonitor != null) {
            healthMonitor.updateCache(status);
        }

        ServiceStatus oldStatus = currentMetrics.get(module);

        // Sound Logic: Trigger only on transitions, and NEVER while JARVIS is speaking/processing.
        // This prevents "Module Ready" sounds from interrupting the vocal response.
        if (oldStatus != null && !isProcessing.get() && !isShuttingDown.get()) {
            boolean wasOnline = isStateOnline(oldStatus.state());
            boolean isNowOnline = isStateOnline(status.state());

            if (wasOnline != isNowOnline) {
                playModuleStatusSound(module, isNowOnline);
            }
        }

        currentMetrics.put(module, status);
        application.onServiceStatusUpdate(status);
        
        // Check if all systems are nominal before playing the intro
        checkAndPlayStartupSound();
    }

    private void checkAndPlayStartupSound() {
        if (startupSoundPlayed.get() || isShuttingDown.get()) return;

        boolean allReady = Arrays.stream(JarvisModule.values())
                .allMatch(m -> {
                    ServiceStatus s = currentMetrics.get(m);
                    return s != null && isStateOnline(s.state());
                });

        if (allReady && startupSoundPlayed.compareAndSet(false, true)) {
            logger.info("🎯 [SYSTEM] All modules online. Triggering system ready sequence.");
            playStartupSound();
        }
    }

    private boolean isStateOnline(ServiceState state) {

        return state == ServiceState.READY || state == ServiceState.BUSY || state == ServiceState.DEGRADED;
    }

    private void playModuleStatusSound(JarvisModule module, boolean online) {
        String statusSuffix = online ? "ready" : "offline";
        String moduleFileName = module.getDisplayName().toLowerCase()
                .replaceAll("[^a-z0-9]", "_").replaceAll("__+", "_").replaceAll("^_|_$", "");
        
        String soundPath = System.getProperty("user.dir") + "/scripts/resources/sounds/modules/" 
                + moduleFileName + "_module_" + statusSuffix + ".mp3";
        
        logger.info("🔊 [SYSTEM] Playing status sound: {}", moduleFileName + "_module_" + statusSuffix);
        playAudioFile(soundPath);
    }

    private ParsedAssistantResult parseAssistantResult(String result) {
        try {
            if (result == null || result.isBlank()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(result);
            ParsedAssistantResult parsed = new ParsedAssistantResult();
            JsonNode textNode = root.get("text");
            JsonNode audioNode = root.get("audioFile");
            parsed.text = textNode != null && !textNode.isNull() ? textNode.asText("") : "";
            parsed.audioFile = audioNode != null && !audioNode.isNull() ? audioNode.asText("") : "";
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Play JARVIS startup sound
     */
    public void playStartupSound() {
        String startupSoundPath = System.getProperty("user.dir") + "/scripts/resources/sounds/jarvis_intro-1.mp3";
        playAudioFile(startupSoundPath);
    }
    
    public void playAudioFile(String audioFilePath) {
        playAudioFile(audioFilePath, null);
    }

    /**
     * Play sound effect for various events
     */
    public void playSoundEffect(String soundName) {
        // Prevent UI sound effects from disposing the player if JARVIS is currently speaking
        if (isProcessing.get()) {
            return;
        }

        String soundPath = System.getProperty("user.dir") + "/scripts/resources/sounds/" + soundName + ".mp3";
        playAudioFile(soundPath);
    }

    public void playAudioFile(String audioFilePath, Runnable onFinished) {
        // Critical: MediaPlayer must be created and controlled on the JavaFX Application Thread
        Platform.runLater(() -> {
            try {
                File file = new File(audioFilePath);
                if (!file.exists()) {
                    logger.warn("⚠️ [SYSTEM] Audio file not found at path: {}", audioFilePath);
                    return;
                }

                // Stop and dispose previous player to release file locks
                if (mediaPlayer != null) {
                    try {
                        mediaPlayer.stop();
                        mediaPlayer.dispose();
                    } catch (Exception ignored) {}
                    mediaPlayer = null;
                }

                logger.info("🔊 [SYSTEM] Playing audio: {}", file.getName());
                Media media = new Media(file.toURI().toString());
                mediaPlayer = new MediaPlayer(media);

                // Add error listener for diagnostics
                mediaPlayer.setOnError(() -> {
                    MediaException ex = mediaPlayer.getError();
                    logger.error("❌ [SYSTEM] MediaPlayer Error: {} (Type: {})", ex.getMessage(), ex.getType());
                });

                mediaPlayer.setOnEndOfMedia(() -> {
                    try {
                        mediaPlayer.stop();
                        mediaPlayer.dispose();
                    } catch (Exception ignored) {}
                    mediaPlayer = null;
                    if (onFinished != null) onFinished.run();
                });

                mediaPlayer.play();
            } catch (Exception e) {
                logger.error("❌ [SYSTEM] Failed to initialize audio playback: " + audioFilePath, e);
            }
        });
    }
    
    /**
     * Execute administrative command
     */
    public CompletableFuture<AdminCommandService.CommandResult> executeAdminCommand(String command) {
        updateStatus("Executing administrative command...");

        conversationLogger.recordTool("admin", "execute", command, "", true);
        
        return adminService.executeAdminCommand(command, 60).thenApply(result -> {
            Platform.runLater(() -> {
                if (result.isSuccess()) {
                    updateStatus("Command executed successfully");
                } else {
                    updateStatus("Command failed: " + result.getError());
                }
            });

            if (result != null) {
                conversationLogger.recordTool(
                    "admin",
                    "result",
                    command,
                    result.isSuccess() ? result.getOutput() : result.getError(),
                    result.isSuccess()
                );
            }
            return result;
        });
    }
    
    /**
     * Update status label
     */
    private void updateStatus(String status) {
        Platform.runLater(() -> {
            if (statusLabel != null) {
                statusLabel.setText("System Status: " + status);
            }
            logger.info("Status: " + status);
        });
    }
    
    /**
     * Update listening status
     */
    private void updateListeningStatus(String status) {
        Platform.runLater(() -> {
            if (listeningLabel != null) {
                listeningLabel.setText(status);
            }
        });
    }
    
    /**
     * Update response status
     */
    private void updateResponseStatus(String status) {
        Platform.runLater(() -> {
            if (responseLabel != null) {
                responseLabel.setText(status);
            }
        });
    }
    
    /**
     * Set UI components (dependency injection)
     */
    public void setUIComponents(Label statusLabel, Label listeningLabel, Label responseLabel, 
                                AudioVisualizer leftVisualizer, AudioVisualizer rightVisualizer, 
                                Label earInputDisplay, Label mouthResponseDisplay) {
        this.statusLabel = statusLabel;
        this.listeningLabel = listeningLabel;
        this.responseLabel = responseLabel;
        this.leftVisualizer = leftVisualizer;
        this.rightVisualizer = rightVisualizer;
        this.earInputDisplay = earInputDisplay;
        this.mouthResponseDisplay = mouthResponseDisplay;
    }

    public void registerProcessOutput(String title, Process process, String commandLine) {
        application.addConsoleTab(title);
        application.selectConsoleTab(title); // Automatically switch focus to the new tab
        
        // Log the initiation command for transparency in the console tab
        application.appendToConsole(title, "EXEC> " + commandLine);
        application.appendToConsole(title, "--------------------------------------------------");
        
        // Resource Management: Elevate priority of the child process
        elevateChildPriority(process, title.contains("Ear") || title.contains("Mouth") ? "High" : "AboveNormal");

        // Read both Stdout and Stderr to ensure all logs appear in the console
        CompletableFuture.runAsync(() -> readProcessStream(process.getInputStream(), title));
        CompletableFuture.runAsync(() -> readProcessStream(process.getErrorStream(), title));
        
        process.onExit().thenAccept(p -> application.appendToConsole(title, ">>> PROCESS TERMINATED (Exit Code: " + p.exitValue() + ") <<<"));
    }

    private void readProcessStream(java.io.InputStream is, String title) {
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                application.appendToConsole(title, line);
                
                // Progress Parsing: Look for tqdm style progress in training logs (e.g. 10%|█ | 6/60)
                if (title.equals("Neural Training")) {
                    Pattern p = Pattern.compile("(\\d+)%\\|.*\\| (\\d+)/(\\d+)");
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        try {
                            double percent = Double.parseDouble(m.group(1)) / 100.0;
                            application.updateGymProgress(percent);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (java.io.IOException e) {
            if (!isShuttingDown.get()) {
                logger.error("Error reading stream for {}: {}", title, e.getMessage());
            }
        }
    }

    private void elevateChildPriority(Process process, String priority) {
        try {
            long pid = process.pid();
            String cmd = String.format("powershell -Command \"(Get-Process -Id %d).PriorityClass = '%s'\"", pid, priority);
            adminService.executeSilentCommand(cmd, 5);
            logger.info("🚀 [SYSTEM] Elevated process {} (PID: {}) to {}", process.info().command().orElse("unknown"), pid, priority);
        } catch (Exception e) {
            logger.warn("⚠️ [SYSTEM] Could not elevate child priority: {}", e.getMessage());
        }
    }

    public Process executeGymTraining(String cmd) throws java.io.IOException {
        ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        registerProcessOutput("Neural Training", p, cmd);
        return p;
    }

    public void startModule(JarvisModule module) {
        conversationLogger.recordTool("module", "start", module != null ? module.name() : "", "", true);
        
        // Play startup sound for module activation
        playSoundEffect("beep");
        
        // RESOURCE MANAGEMENT: Prevent GPU/RAM overloading
        double totalRam = currentMetrics.values().stream().mapToDouble(ServiceStatus::ramUsageMb).sum();
        if (totalRam > 8000) { // Example 8GB threshold
            logger.warn("⚠️ [SYSTEM] High RAM usage detected ({} MB). Starting {} might cause instability.", totalRam, module);
        }

        // Immediately update UI to show intent to start, removing "UNKNOWN"
        onServiceStatusUpdate(new ServiceStatus(module, ServiceState.STARTING, -1, "Initializing..."));
        
        if (module == JarvisModule.CORTEX_BRAIN) {
            cortexManager.startCortex();
            // Allow 5s for the Ollama server to boot, then pin the model in memory indefinitely
            CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                pythonService.pinModel(true);
            });
        } else {
            startTimes.put(module, System.currentTimeMillis());
            pythonService.startModule(module);

            // If Mouth is starting, trigger an immediate model preload
            if (module == JarvisModule.VOCAL_MOUTH) {
                CompletableFuture.delayedExecutor(3, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                    pythonService.preloadMouth();
                });
            }
        }
    }

    public void stopModule(JarvisModule module) {
        conversationLogger.recordTool("module", "stop", module != null ? module.name() : "", "", true);
        
        // Play shutdown sound for module deactivation
        if (!isShuttingDown.get()) {
            playSoundEffect("whistle");
        }
        
        if (module == JarvisModule.CORTEX_BRAIN) {
            pythonService.pinModel(false); // Immediate VRAM release
            cortexManager.stopCortex();
        } else {
            pythonService.stopModule(module);
        }

        // Immediately update status to OFFLINE to synchronize UI and prevent "STARTING" hang
        onServiceStatusUpdate(new ServiceStatus(module, ServiceState.OFFLINE, -1, "Terminated"));

        // If the core reactor (system heart) is stopped, cascade the stop to all other modules
        if (module == JarvisModule.CORE_REACTOR) {
            for (JarvisModule m : JarvisModule.values()) {
                if (m != JarvisModule.CORE_REACTOR) {
                    stopModule(m);
                }
            }
        }
    }
    
    /**
     * Update the ear input display with received command
     */
    public void updateEarInputDisplay(String command) {
        if (earInputDisplay == null) {
            return;
        }
        Platform.runLater(() -> {
            if (command != null && !command.trim().isEmpty()) {
                conversationLogger.recordUser(command);
                earInputDisplay.setText("🎤 HEARD: " + command);
                earInputDisplay.setStyle("-fx-background-color: rgba(0,255,255,0,0.1); -fx-background-radius: 8; -fx-padding: 10;");
            } else {
                earInputDisplay.setText("🎤 Waiting for voice input...");
                earInputDisplay.setStyle("-fx-background-color: rgba(0,0,0,0,0.7); -fx-background-radius: 8; -fx-padding: 10;");
            }
        });
    }
    
    /**
     * Update the mouth response display with AI response
     */
    public void updateMouthResponseDisplay(String response) {
        if (mouthResponseDisplay == null) {
            return;
        }
        Platform.runLater(() -> {
            if (response != null && !response.trim().isEmpty()) {
                conversationLogger.recordAssistant(response);
                mouthResponseDisplay.setText(response);
                mouthResponseDisplay.setStyle("-fx-background-color: rgba(255,0,255,0.1); -fx-background-radius: 8; -fx-padding: 10;");
            } else {
                mouthResponseDisplay.setText("🔊 Waiting for response...");
                mouthResponseDisplay.setStyle("-fx-background-color: rgba(0,0,0,0,0.7); -fx-background-radius: 8; -fx-padding: 10;");
            }
        });
    }
    
    public PythonService getPythonService() {
        return pythonService;
    }
    
    public boolean isListening() {
        return isListening.get();
    }

    public boolean isProcessing() {
        return isProcessing.get();
    }

    /**
     * Shutdown the JARVIS system
     */
    public void shutdown() {
        if (isShuttingDown.getAndSet(true)) return;
        
        logger.info("Shutting down JARVIS system...");
        
        // 1. Immediately halt the health monitor to prevent status overwrite during teardown
        if (healthMonitor != null) {
            healthMonitor.stop();
        }

        application.showShutdownProgress("INITIALIZING SECURE SHUTDOWN...");

        CompletableFuture.runAsync(() -> {
            try {
                // 2. Neural Reflection (Must happen while Brain/Cortex is still online)
                if (cortexManager.isCortexActive()) {
                    application.showShutdownProgress("NEURAL REFLECTION: ANALYZING SESSION...");
                    String transcript = conversationLogger.getFullTranscript();
                    if (!transcript.isBlank()) {
                        try {
                            // Reduced timeout for snappier shutdown
                            String insights = pythonService.generateMemoInsights(transcript).get(30, TimeUnit.SECONDS);
                            
                            application.showShutdownProgress("NEURAL REFLECTION: ARCHIVING MEMORIES...");
                            conversationLogger.exportMemory(insights);
                        } catch (Exception e) {
                            logger.warn("⚠️ [SYSTEM] Neural Reflection failed or timed out: {}", e.getMessage());
                        }
                    }
                }

                // 3. Data Persistence
                application.showShutdownProgress("DATA EXPORT: SAVING CONVERSATION LOGS...");
                conversationLogger.exportSession();

                // 4. Final Subsystem Termination
                application.showShutdownProgress("TERMINATING EXTERNAL API SERVERS...");
                apiServer.stopServer();
                
                application.showShutdownProgress("RELEASING NEURAL (GPU) RESOURCES...");
                pythonService.pinModel(false); // Release VRAM
                cortexManager.stopCortex();
                
                application.showShutdownProgress("SHUTTING DOWN PYTHON SUBSYSTEMS...");
                pythonService.shutdown();

                application.showShutdownProgress("SHUTDOWN COMPLETE. DISCONNECTING.");
                // Allow the Arc Reactor to continue its cycle until the process terminates
                Thread.sleep(500);

            } catch (Exception e) {
                logger.error("❌ [SYSTEM] Error during background shutdown sequence", e);
            } finally {
                // Ensure the application exits regardless of success/failure
                Platform.runLater(Platform::exit);
            }
        });
    }

    private static class ParsedAssistantResult {
        String text;
        String audioFile;
    }
}
