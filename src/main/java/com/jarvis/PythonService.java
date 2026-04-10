package com.jarvis;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.stage.Screen;
import javafx.geometry.Rectangle2D;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing and executing Python scripts in unified environment
 * Optimized for Dell 7577 (GTX 1060) - single Python process
 */
public class PythonService {
    
    private static final Logger logger = LoggerFactory.getLogger(PythonService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // --- CONFIGURATION ---
    // Unified system - detect and use activated conda environment
    private static final String PYTHON_EXECUTABLE = "python"; // Will use activated conda python if available

    private final String scriptsDir;
    private final String voicesDir;
    private final String mouthApiBaseUrl;
    private final String ollamaApiBaseUrl;
    private final String ocularApiBaseUrl;
    private final String earsApiBaseUrl;
    private final String actuatorsApiBaseUrl;
    private final String memoryApiBaseUrl;
    private final String brainApiBaseUrl = "http://127.0.0.1:8883";
    
    private final JarvisController controller;
    private volatile String selectedVoice = "af_heart";
    private volatile String selectedBrainModel = "jarvis:latest";
    private volatile String selectedTrainingBaseModel = "unsloth/Llama-3.2-3B-Instruct-bnb-4bit";
    
    // Optimized: Use a pooled connection manager for low-latency local requests
    private final PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
    private final CloseableHttpClient httpClient;

    {
        connectionManager.setMaxTotal(20);
        connectionManager.setDefaultMaxPerRoute(10);
        this.httpClient = HttpClients.custom().setConnectionManager(connectionManager).build();
    }

    private final RequestConfig defaultRequestConfig = RequestConfig.custom()
        .setConnectTimeout(Timeout.ofSeconds(5))
        .setResponseTimeout(Timeout.ofSeconds(120))
        .build();

    private final java.util.Map<JarvisModule, Process> activeProcesses = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger windowCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    public PythonService(JarvisController controller) {
        this.controller = controller;
        JarvisConfig cfg = JarvisConfig.get();
        this.scriptsDir = cfg.scriptsDir();
        this.voicesDir = cfg.voicesDir();
        this.mouthApiBaseUrl = cfg.mouthApiBaseUrl();
        this.ollamaApiBaseUrl = cfg.ollamaApiBaseUrl();
        this.ocularApiBaseUrl = cfg.ocularApiBaseUrl();
        this.earsApiBaseUrl = cfg.earsApiBaseUrl();
        this.actuatorsApiBaseUrl = cfg.actuatorsApiBaseUrl();
        this.memoryApiBaseUrl = cfg.memoryApiBaseUrl();
    }
    
    /**
     * Initialize all Python services using unified Python environment
     * Optimized for single-process execution on Dell 7577
     */
    public CompletableFuture<Boolean> initializeServices() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Initializing JARVIS system with unified Python environment...");
                
                // First, try to activate jarvis_system conda environment
                boolean condaActivated = activateCondaEnvironment();
                
                // Then verify Python installation and dependencies
                if (!verifyPythonEnvironment()) {
                    logger.error("Python environment not properly configured. Check requirements_system.txt");
                    return false;
                }
                
                if (condaActivated) {
                    logger.info("🎯 JARVIS system ready - jarvis_system conda environment active");
                } else {
                    logger.info("🎯 JARVIS system ready - Using available Python environment");
                }
                return true;
                
            } catch (Exception e) {
                logger.error("Error initializing JARVIS system", e);
                return false;
            }
        });
    }
    
    /**
     * Activate jarvis_system conda environment automatically
     */
    private boolean activateCondaEnvironment() {
        try {
            logger.info("🔄 Attempting to activate jarvis_system conda environment...");
            
            // Check if jarvis_system is already activated
            String currentEnv = System.getenv("CONDA_DEFAULT_ENV");
            if (currentEnv != null && currentEnv.equals("jarvis_system")) {
                logger.info("✅ jarvis_system already activated");
                return true;
            }
            
            // Try to activate jarvis_system conda environment
            ProcessBuilder condaActivate = new ProcessBuilder(
                "cmd.exe", "/c", 
                "conda activate jarvis_system && echo CONDA_ACTIVATED"
            );
            condaActivate.redirectErrorStream(true);
            
            Process activateProcess = condaActivate.start();
            
            // Read output to verify activation
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (java.io.InputStream in = activateProcess.getInputStream()) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = in.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                }
            }
            
            int exitCode = activateProcess.waitFor();
            String result = output.toString().trim();
            
            if (exitCode == 0 && result.contains("CONDA_ACTIVATED")) {
                logger.info("✅ Successfully activated jarvis_system conda environment");
                return true;
            } else {
                logger.warn("⚠️ Could not activate jarvis_system automatically. Will use current Python environment.");
                logger.warn("Output: " + result);
                return false;
            }
            
        } catch (Exception e) {
            logger.warn("⚠️ Error activating conda environment: " + e.getMessage());
            return false;
        }
    }

    /**
     * Verify Python environment and detect activated conda
     */
    private boolean verifyPythonEnvironment() {
        try {
            logger.info("Verifying unified Python environment...");
            
            // Check Python installation
            Process pythonCheck = Runtime.getRuntime().exec(new String[]{PYTHON_EXECUTABLE, "--version"});
            if (pythonCheck.waitFor() != 0) {
                logger.error("Python not found in PATH");
                return false;
            }
            
            // Detect if conda environment is activated
            String condaEnv = System.getenv("CONDA_DEFAULT_ENV");
            if (condaEnv != null && !condaEnv.isEmpty()) {
                logger.info("✅ Detected activated conda environment: " + condaEnv);
                
                // Verify it's jarvis_system or warn user
                if (!condaEnv.equals("jarvis_system") && !condaEnv.equals("base")) {
                    logger.warn("⚠️ Conda environment '" + condaEnv + "' detected. For optimal performance, consider activating 'jarvis_system'");
                }
            } else {
                logger.info("Using system Python (no conda environment detected)");
            }
            
            // Check if scripts directory exists
            java.io.File scriptsDirFile = new java.io.File(scriptsDir);
            if (!scriptsDirFile.exists()) {
                logger.error("Scripts directory not found: " + scriptsDir);
                return false;
            }
            
            // Check key module files
            String[] requiredScripts = {
                "jarvis_actuator.py", "jarvis_mouth.py", 
                "jarvis_eye.py", "jarvis_brain.py", "jarvis_ear.py"
            };
            
            for (String script : requiredScripts) {
                java.io.File scriptFile = new java.io.File(scriptsDirFile, script);
                if (!scriptFile.exists()) {
                    logger.error("Required script not found: " + script);
                    return false;
                }
            }
            
            // Verify Python can import key dependencies
            Process dependencyCheck = Runtime.getRuntime().exec(new String[]{PYTHON_EXECUTABLE, "-c", 
                "import sys; print('Python version:', sys.version.split()[0]); " +
                "try: import mediapipe; print('✅ MediaPipe available'); except: print('❌ MediaPipe missing'); " +
                "try: import cv2; print('✅ OpenCV available'); except: print('❌ OpenCV missing'); " +
                "try: import torch; print('✅ PyTorch available'); except: print('❌ PyTorch missing')"});
            
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (java.io.InputStream in = dependencyCheck.getInputStream()) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = in.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                }
            }
            
            logger.info("Python environment check results:\n" + output.toString());
            
            logger.info("✅ Unified Python environment verified successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error verifying Python environment", e);
            return false;
        }
    }
    
    /**
     * Start a specific module service script using unified Python environment
     */
    public synchronized void startModule(JarvisModule module) {
        // Check if the process is already managed
        Process existing = activeProcesses.get(module);
        if (existing != null && existing.isAlive()) {
            logger.debug("Module {} already has an active process.", module);
            return;
        }

        String script = switch (module) {
            case AURAL_EARS -> "jarvis_ear.py";
            case VOCAL_MOUTH -> "jarvis_mouth.py";
            case OCULAR_EYES -> "jarvis_eye.py";
            case REPULSORS -> "jarvis_actuator.py";
            case LONG_TERM_MEMORY -> "jarvis_memory.py";
            default -> null;
        };

        if (script == null) return;

        try {
            boolean isActuator = (module == JarvisModule.REPULSORS);
            
            // RESOURCE MANAGEMENT: If launching a detached window (Actuator),
            // clear the port first to prevent "Address already in use" errors.
            if (isActuator) {
                logger.info("🧹 [SYSTEM] Clearing port 8084 for Actuator fresh start...");
                clearPort(8084);
            }

            // UNIFIED SYSTEM: Use conda-activated python for optimal performance
            // Automatically activates jarvis_system if available, falls back gracefully
            ProcessBuilder pb;
            String condaEnv = System.getenv("CONDA_DEFAULT_ENV");
            
            if (condaEnv != null && condaEnv.equals("jarvis_system")) {
                // Use the activated conda environment
                pb = new ProcessBuilder("cmd.exe", "/c", "python \"" + script + "\"");
                logger.info("🐍 Using jarvis_system conda Python for: " + script);
            } else {
                // Try to activate jarvis_system for this module
                pb = new ProcessBuilder("cmd.exe", "/c", 
                    "conda activate jarvis_system 2>nul && python \"" + script + "\" || python \"" + script + "\"");
                logger.info("🔄 Attempting jarvis_system activation for: " + script);
            }
            
            pb.directory(new java.io.File(scriptsDir));
            pb.redirectErrorStream(true);
            
            // Set environment variables for optimization
            Map<String, String> env = pb.environment();
            env.put("OMP_NUM_THREADS", "4");
            env.put("MEDIAPIPE_GPU_ENABLED", "0");
            env.put("CUDA_LAUNCH_BLOCKING", "1");
            
            // Preserve conda environment if activated
            String condaPrefix = System.getenv("CONDA_PREFIX");
            String condaDefaultEnv = System.getenv("CONDA_DEFAULT_ENV");
            if (condaPrefix != null && condaDefaultEnv != null && !condaDefaultEnv.isEmpty()) {
                env.put("CONDA_PREFIX", condaPrefix);
                env.put("CONDA_DEFAULT_ENV", condaDefaultEnv);
                logger.info("🔗 Preserving conda environment: " + condaDefaultEnv);
            }
            
            Process p = pb.start();
            logger.info("🚀 Started module {} with PID: {}", module, p.pid());
            
            activeProcesses.put(module, p);

            if (controller != null) {
                String title = (module == JarvisModule.VOCAL_MOUTH) ? "Mouth CMD" : module.getDisplayName();
                String commandLine = String.join(" ", pb.command());
                controller.registerProcessOutput(title, p, commandLine);
            }
            
            logger.info("Started process for " + module + " using script " + script);
        } catch (java.io.IOException e) {
            logger.error("Failed to start module: " + module, e);
        }
    }

    private void clearPort(int port) {
        try {
            // RESOURCE MANAGEMENT: Use ProcessBuilder for robust PowerShell execution with pipes
            String psCommand = String.format(
                "Get-NetTCPConnection -LocalPort %d -ErrorAction SilentlyContinue | ForEach-Object { " +
                "try { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue } catch {} }", 
                port
            );
            ProcessBuilder pb = new ProcessBuilder("powershell", "-Command", psCommand);
            pb.start().waitFor(3, TimeUnit.SECONDS);
            
            // Small grace period for the OS to fully release the socket
            Thread.sleep(500);
        } catch (Exception e) {
            logger.warn("⚠️ [SYSTEM] Failed to clear port {}: {}", port, e.getMessage());
        }
    }

    private String getPositioningCommand(String title) {
        try {
            // Get visual bounds of primary screen (excludes taskbar)
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            int w = (int) bounds.getWidth() / 2;
            int h = (int) bounds.getHeight() / 2;
            
            // Calculate corner position clockwise starting from Top-Left
            int index = windowCounter.getAndIncrement() % 4;
            int x = (index == 1 || index == 2) ? w : 0;
            int y = (index == 2 || index == 3) ? h : 0;
            int screenTop = (int) bounds.getMinY();
            
            // Use dynamic type name to avoid assembly collisions.
            // Use FindWindow with the unique title for maximum reliability.
            String typeName = "W" + index + "_" + (System.currentTimeMillis() % 100000);
            return String.format(
                "powershell -command \"$m='[DllImport(\\\"user32.dll\\\")]public static extern bool MoveWindow(IntPtr h,int x,int y,int w,int n,bool b);[DllImport(\\\"user32.dll\\\")]public static extern IntPtr FindWindow(string c,string t);';" +
                "$u=Add-Type -MemberDefinition $m -Name '%s' -PassThru;" +
                "for($i=0;$i -lt 20;$i++){$h=$u::FindWindow($null, '%s');" +
                "if($h -ne 0){ [void]$u::MoveWindow($h,%d,%d,%d,%d,$true); break };" +
                "sleep -m 100}\"",
                typeName, title, x, screenTop + y, w, h
            );
        } catch (Exception e) {
            return "rem positioning failed";
        }
    }

    /**
     * Triggers the screen capture overlay on the Ocular module
     */
    public void triggerScreenCapture() {
        postJson(ocularApiBaseUrl + "/capture", objectMapper.createObjectNode(), 5);
    }

    /**
     * Performs a warm-up call to the Mouth service to ensure the Kokoro model 
     * is pinned in memory indefinitely.
     */
    public void preloadMouth() {
        CompletableFuture.runAsync(() -> {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("input", ""); // Minimal input to trigger model load
            payload.put("voice", selectedVoice);
            
            logger.info("🔊 [MOUTH] Preloading Kokoro model into memory...");
            postJson(mouthApiBaseUrl + "/v1/audio/speech", payload, 30);
        });
    }

    /**
     * Controls the residency of the 'jarvis' model in Ollama VRAM.
     * @param pin true for indefinite residency (-1), false for immediate release (0).
     */
    public void pinModel(boolean pin) {
        CompletableFuture.runAsync(() -> {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", selectedBrainModel);
            payload.put("keep_alive", pin ? -1 : 0);
            payload.put("prompt", ""); // Trigger a load/unload without generating text
            payload.put("stream", false);

            logger.info("🧠 [BRAIN] Setting model 'jarvis' keep_alive to: {}", pin ? "PERMANENT" : "RELEASE");
            postJson(ollamaApiBaseUrl + "/api/generate", payload, 15);
        });
    }

    public void stopModule(JarvisModule module) {
        // Attempt to free resources via module-specific shutdown API first
        attemptGracefulShutdown(module);

        Process p = activeProcesses.remove(module);

        if (p != null) {
            try {
                // Force kill the tree; we don't check isAlive() because the launcher (cmd.exe)
                // might have terminated while the underlying python process lingers.
                Process killProc = Runtime.getRuntime().exec("taskkill /F /T /PID " + p.pid());
                killProc.waitFor(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                p.destroyForcibly();
            }
            logger.info("Terminated internal process tree for module: " + module);
        }

        // Actuator specific port cleanup (the nuclear option for Windows)
        if (module == JarvisModule.REPULSORS) {
            clearPort(8084);
        }
    }

    private void attemptGracefulShutdown(JarvisModule module) {
        String url = switch (module) {
            case VOCAL_MOUTH -> mouthApiBaseUrl + "/shutdown";
            case AURAL_EARS -> earsApiBaseUrl + "/shutdown";
            case OCULAR_EYES -> ocularApiBaseUrl + "/shutdown"; // Keep this as it's a separate script
            case REPULSORS -> actuatorsApiBaseUrl + "/shutdown";
            case LONG_TERM_MEMORY -> memoryApiBaseUrl + "/shutdown";
            default -> null;
        };

        if (url != null) {
            try {
                // Increased timeout to 3s to allow heavy AI modules time to respond while busy
                postJson(url, objectMapper.createObjectNode(), 3);
                
                // Give the module a moment to cleanup before force-killing
                Thread.sleep(800);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Execute brain processing command
     */
    public CompletableFuture<String> executeBrainCommand(String command, String dataPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Executing brain command: " + command);
                
                String title = "JARVIS-BRAIN-TASK: " + command.toUpperCase();
                String posCmd = getPositioningCommand(title);
                String pyCmd = "python \"" + 
                               scriptsDir + "\\jarvis_brain.py\" --" + command + " \"" + dataPath + "\"";
                pyCmd = "color 0B && prompt [JARVIS] $G && " + posCmd + " && " + pyCmd;
                String fullCommand = "start /min \"" + title + "\" cmd /k \"" + pyCmd + "\"";
                
                ProcessResult result = executeCommandWithOutput(fullCommand, 1800);
                
                if (result.exitCode == 0) {
                    logger.info("Brain command executed successfully");
                    return result.output;
                } else {
                    logger.error("Brain command failed: " + result.error);
                    return "ERROR: " + result.error;
                }
                
            } catch (Exception e) {
                logger.error("Error executing brain command", e);
                return "ERROR: " + e.getMessage();
            }
        });
    }
    
    /**
     * Execute a command and return success/failure
     */
    private boolean executeCommand(String command, int timeoutSeconds) {
        try {
            CommandLine cmdLine = CommandLine.parse("cmd.exe /c " + command);
            DefaultExecutor executor = new DefaultExecutor();
            ExecuteWatchdog watchdog = new ExecuteWatchdog(timeoutSeconds * 1000L);
            executor.setWatchdog(watchdog);
            
            int exitCode = executor.execute(cmdLine);
            return exitCode == 0;
            
        } catch (ExecuteException e) {
            logger.error("Command execution failed: " + command, e);
            return false;
        } catch (IOException e) {
            logger.error("IO error executing command: " + command, e);
            return false;
        }
    }
    
    /**
     * Execute a command and capture output
     */
    private ProcessResult executeCommandWithOutput(String command, int timeoutSeconds) {
        try {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            
            CommandLine cmdLine = CommandLine.parse("cmd.exe /c " + command);
            DefaultExecutor executor = new DefaultExecutor();
            executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));
            ExecuteWatchdog watchdog = new ExecuteWatchdog(timeoutSeconds * 1000L);
            executor.setWatchdog(watchdog);
            
            int exitCode = executor.execute(cmdLine);
            
            return new ProcessResult(exitCode, stdout.toString(), stderr.toString());
            
        } catch (Exception e) {
            logger.error("Error executing command with output: " + command, e);
            return new ProcessResult(-1, "", e.getMessage());
        }
    }
    
    /**
     * Generate speech using the mouth service
     */
    public CompletableFuture<String> generateSpeech(String text) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("🔊 [MOUTH] Generating speech for: " + text);
                logger.info("🔗 [MOUTH] Sending to mouth service: " + mouthApiBaseUrl + "/v1/audio/speech");
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("input", text);
                payload.put("voice", selectedVoice);
                payload.put("speed", 1.0);

                // Increased timeout to 120s for long TTS generations
                HttpResult result = postJson(mouthApiBaseUrl + "/v1/audio/speech", payload, 120);
                if (result.statusCode >= 200 && result.statusCode < 300) {
                    logger.info("✅ [MOUTH] Speech generated successfully");
                    if (result.body != null && !result.body.isEmpty()) {
                        logger.info("🔊 [MOUTH] Response: " + result.body);
                    }
                    String audioFile = extractMouthAudioFile(result.body);
                    return audioFile != null ? audioFile : "";
                }

                logger.error("❌ [MOUTH] Speech generation failed: HTTP " + result.statusCode + " - " + result.body);
                return "ERROR: " + result.body;
                
            } catch (Exception e) {
                logger.error("❌ [MOUTH] Error generating speech", e);
                return "ERROR: " + e.getMessage();
            }
        });
    }
    
    /**
     * Queries long-term memory for relevant context.
     */
    public CompletableFuture<String> queryMemory(String text) {
        return CompletableFuture.supplyAsync(() -> {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("text", text);
            payload.put("top_k", 3);

            HttpResult result = postJson(memoryApiBaseUrl + "/memory/query", payload, 10);
            if (result.statusCode == 200) {
                try {
                    JsonNode root = objectMapper.readTree(result.body);
                    JsonNode results = root.get("results");
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode res : results) {
                        sb.append("- ").append(res.path("text").asText()).append("\n");
                    }
                    return sb.toString();
                } catch (Exception e) {
                    return "";
                }
            }
            return "";
        });
    }

    /**
     * Generates a "Memo" of insights from a conversation transcript for fine-tuning.
     */
    public CompletableFuture<String> generateMemoInsights(String transcript) {
        return CompletableFuture.supplyAsync(() -> {
            if (transcript == null || transcript.isBlank()) return "No significant interactions recorded.";
            
            String prompt = "### INSTRUCTION:\nAnalyze the following conversation transcript. " +
                            "Provide a concise 'System Memo' containing key user preferences, " +
                            "learned facts about the user, and interaction patterns to improve future neural responses. " +
                            "Keep the summary strictly technical and useful for fine-tuning.\n\n" +
                            "### TRANSCRIPT:\n" + transcript + "\n\n" +
                            "### MEMO:";
            return getBrainResponse(prompt);
        });
    }

    /**
     * Process a command from ear service and generate response
     */
    public CompletableFuture<String> processCommand(String command) {
        return CompletableFuture.supplyAsync(() -> {
            String brainResponse = "";
            try {
                logger.info("Processing command: " + command);
                
                // Step 1: Get response from Ollama (brain)
                brainResponse = getBrainResponse(command);
                logger.info("Brain response: " + brainResponse);
                
                // Step 2: Generate speech for the response using mouth service
                String audioFile = "";
                try {
                    // Aligned with the 120s generation timeout
                    audioFile = generateSpeech(brainResponse).get(120, TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.error("⚠️ [MOUTH] Speech generation timed out or failed, proceeding with text only", e);
                    audioFile = "ERROR: " + e.getMessage();
                }

                ObjectNode result = objectMapper.createObjectNode();
                result.put("text", brainResponse);
                result.put("audioFile", audioFile != null ? audioFile : "");
                return objectMapper.writeValueAsString(result);
                
            } catch (Exception e) {
                logger.error("Error processing command", e);
                return "ERROR: " + e.getMessage();
            }
        });
    }

    private String extractMouthAudioFile(String jsonOutput) {
        try {
            if (jsonOutput == null || jsonOutput.trim().isEmpty()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(jsonOutput);
            JsonNode fileNode = root.get("file");
            if (fileNode == null || fileNode.isNull()) {
                return null;
            }

            String file = fileNode.asText(null);
            return file != null ? file.trim() : null;
        } catch (Exception e) {
            logger.error("Error parsing mouth response", e);
            return null;
        }
    }
    
    /**
     * Get response from Ollama (brain)
     */
    public CompletableFuture<String> getBrainResponseAsync(String command) {
        return CompletableFuture.supplyAsync(() -> getBrainResponse(command));
    }

    public String getBrainResponse(String command) {
        try {
            logger.info("🧠 [BRAIN] Processing command: " + command);

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", selectedBrainModel);
            payload.put("prompt", command);
            payload.put("stream", false);

            int maxRetries = 3;
            int attempt = 0;
            HttpResult result = null;

            while (attempt < maxRetries) {
                attempt++;
                logger.info("🧠 [BRAIN] Attempt {}/{} for model: {}", attempt, maxRetries, selectedBrainModel);
                
                // Increased timeout to 120 seconds to prevent connection resets on slow inference
                result = postJson(ollamaApiBaseUrl + "/api/generate", payload, 120);

                if (result.statusCode >= 200 && result.statusCode < 300) {
                    String response = extractOllamaResponse(result.body);
                    if (response != null) {
                        logger.info("🗣️ [BRAIN] Response received successfully after {} attempts.", attempt);
                        return response;
                    }
                } else if (result.statusCode == 404) {
                    logger.error("❌ [BRAIN] Model '{}' not found in Ollama.", selectedBrainModel);
                    return "I cannot find the model '" + selectedBrainModel + "' in my library.";
                }

                logger.warn("⚠️ [BRAIN] Request failed (HTTP {}). Retrying in 2s...", 
                    result != null ? result.statusCode : "Unknown");
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }

            logger.error("❌ [BRAIN] Ollama request failed after {} attempts: HTTP {} - {}", 
                maxRetries, result.statusCode, result.body);
            
            if (result.statusCode == -1) {
                return "My neural connection was reset. Please ensure Ollama is running.";
            }
            return "I'm having trouble connecting to my brain right now (Error " + result.statusCode + ").";

        } catch (Exception e) {
            logger.error("❌ [BRAIN] Error getting brain response", e);
            return "I'm experiencing some technical difficulties with my brain.";
        }
    }
    
    /**
     * Extract response from Ollama JSON output
     */
    private String extractOllamaResponse(String jsonOutput) {
        try {
            if (jsonOutput == null || jsonOutput.trim().isEmpty()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(jsonOutput);
            JsonNode responseNode = root.get("response");
            if (responseNode == null || responseNode.isNull()) {
                return null;
            }

            String response = responseNode.asText(null);
            return response != null ? response.trim() : null;
        } catch (Exception e) {
            logger.error("Error parsing Ollama response", e);
        }
        return null;
    }

    private HttpResult postJson(String url, JsonNode payload, int timeoutSeconds) {
        try {
            HttpPost post = new HttpPost(url);
            
            // Use custom timeout if provided, otherwise use optimized default
            RequestConfig requestConfig = (timeoutSeconds > 0) 
                ? RequestConfig.copy(defaultRequestConfig)
                    .setResponseTimeout(Timeout.ofSeconds(timeoutSeconds))
                    .build()
                : defaultRequestConfig;
                
            post.setConfig(requestConfig);
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));

            return httpClient.execute(post, response -> {
                int statusCode = response.getCode();
                String body = response.getEntity() != null ? EntityUtils.toString(response.getEntity()) : "";
                return new HttpResult(statusCode, body);
            });
        } catch (Exception e) {
            logger.error("❌ [SYSTEM] HTTP Request failed to " + url + ": " + e.getMessage());
            return new HttpResult(-1, e.getMessage());
        }
    }
    
    public void setSelectedVoice(String voice) {
        this.selectedVoice = voice;
        logger.info("🎤 [MOUTH] Voice changed to: " + voice);
    }
    
    public String getSelectedVoice() {
        return selectedVoice;
    }
    
    public java.util.List<String> getAvailableVoices() {
        java.util.List<String> voices = new java.util.ArrayList<>();
        java.io.File voicesDirFile = new java.io.File(this.voicesDir);
        if (voicesDirFile.exists() && voicesDirFile.isDirectory()) {
            java.io.File[] files = voicesDirFile.listFiles((dir, name) -> name.endsWith(".pt"));
            if (files != null) {
                for (java.io.File file : files) {
                    String name = file.getName();
                    voices.add(name.substring(0, name.lastIndexOf('.')));
                }
            }
        }
        java.util.Collections.sort(voices);
        return voices;
    }
    
    public void setSelectedBrainModel(String model) {
        this.selectedBrainModel = model;
        logger.info("🧠 [BRAIN] Model changed to: " + model);
    }
    
    public String getSelectedBrainModel() {
        return selectedBrainModel;
    }
    
    public void setSelectedTrainingBaseModel(String modelId) {
        this.selectedTrainingBaseModel = modelId;
        logger.info("🏋️ [GYM] Training base model set to: " + modelId);
    }

    public String getSelectedTrainingBaseModel() {
        return selectedTrainingBaseModel;
    }
    
    /**
     * Fetch available models from Ollama API
     */
    public java.util.List<String> getAvailableBrainModels() {
        java.util.List<String> models = new java.util.ArrayList<>();
        try {
            HttpGet get = new HttpGet(ollamaApiBaseUrl + "/api/tags");
            return httpClient.execute(get, response -> {
                if (response.getCode() == 200) {
                    String body = EntityUtils.toString(response.getEntity());
                    JsonNode modelsNode = objectMapper.readTree(body).path("models");
                    if (modelsNode != null && modelsNode.isArray()) {
                        for (JsonNode modelNode : modelsNode) {
                            String name = modelNode.path("name").asText("");
                            if (!name.isEmpty()) models.add(name);
                        }
                    }
                }
                if (models.isEmpty()) models.add(selectedBrainModel);
                return models;
            });
        } catch (Exception e) {
            if (models.isEmpty()) models.add(selectedBrainModel);
            return models;
        }
    }
    
    /**
     * Shutdown all services
     */
    public void shutdown() {
        logger.info("Shutting down JARVIS system...");
        
        // Iterate through all modules and attempt to kill their windows
        for (JarvisModule module : JarvisModule.values()) {
            stopModule(module);
        }
        activeProcesses.clear();
        
        try {
            httpClient.close();
        } catch (IOException e) {
            logger.error("Error closing HTTP client during shutdown", e);
        }

        logger.info("JARVIS system shutdown complete");
    }
    
    /**
     * Result holder for command execution
     */
    private static class ProcessResult {
        final int exitCode;
        final String output;
        final String error;
        
        ProcessResult(int exitCode, String output, String error) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }
        
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    private static class HttpResult {
        final int statusCode;
        final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
