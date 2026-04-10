package com.jarvis;

import java.io.IOException;

/**
 * Manages the Cortex (Ollama) service.
 * Professional start/stop logic for the primary inference engine.
 */
public class CortexManager {

    private Process ollamaProcess;
    private final JarvisController controller;

    public CortexManager(JarvisController controller) {
        this.controller = controller;
    }

    /**
     * Starts the Ollama service which hosts the 'jarvis' model.
     */
    public void startCortex() {
        try {
            System.out.println("🧠 Initializing Cortex Service (Ollama)...");
            ProcessBuilder pb = new ProcessBuilder("ollama", "serve");
            pb.redirectErrorStream(true);
            ollamaProcess = pb.start();
            
            if (controller != null) {
                controller.registerProcessOutput("Cortex (Brain)", ollamaProcess, "ollama serve");
            }
        } catch (IOException e) {
            System.err.println("❌ Failed to launch Cortex: " + e.getMessage());
        }
    }

    /**
     * Stops the Ollama service safely.
     */
    public void stopCortex() {
        if (ollamaProcess != null) {
            ollamaProcess.destroy();
            System.out.println("🛑 Cortex Service Terminated.");
        }

        // Professional Cleanup: Ensure the ollama executable itself is terminated
        try {
            new ProcessBuilder("taskkill", "/F", "/IM", "ollama.exe").start();
        } catch (IOException ignored) {}
    }

    /**
     * Utility to check if service is active.
     */
    public boolean isCortexActive() {
        return ollamaProcess != null && ollamaProcess.isAlive();
    }
}