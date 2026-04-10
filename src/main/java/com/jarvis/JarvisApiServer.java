package com.jarvis;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * Simple HTTP server to receive commands from ear service
 * Listens on port 8082 for /jarvis/command endpoints
 */
public class JarvisApiServer {
    
    private static final Logger logger = LoggerFactory.getLogger(JarvisApiServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private HttpServer server;
    private final JarvisController controller;
    private volatile boolean running = false;
    
    public JarvisApiServer(JarvisController controller) {
        this.controller = controller;
    }
    
    /**
     * Start the API server
     */
    public CompletableFuture<Boolean> startServer() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8082), 0);
                
                // Add command endpoint
                server.createContext("/jarvis/command", new CommandHandler());

                // Add target detection endpoint
                server.createContext("/jarvis/target", exchange -> {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes());
                        JsonNode node = objectMapper.readTree(body);
                        String target = node.path("target").asText("UNKNOWN");
                        controller.handleTargetDetection(target);
                        exchange.sendResponseHeaders(200, 0);
                        exchange.close();
                    }
                });

                // Endpoint for modules to report their status (e.g. OFFLINE, READY, ERROR)
                server.createContext("/jarvis/status", exchange -> {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        try {
                            String body = new String(exchange.getRequestBody().readAllBytes());
                            JsonNode node = objectMapper.readTree(body);
                            String module = node.path("module").asText();
                            String status = node.path("status").asText();
                            String message = node.path("message").asText("");
                            controller.reportModuleStatus(module, status, message);
                            exchange.sendResponseHeaders(200, 0);
                        } catch (Exception e) {
                            logger.error("Error processing status update", e);
                            exchange.sendResponseHeaders(500, 0);
                        }
                        exchange.close();
                    }
                });

                // Add health check endpoint for the Core Reactor status
                server.createContext("/health", exchange -> {
                    com.sun.management.OperatingSystemMXBean osBean = 
                        (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                    double cpu = Math.max(0, osBean.getProcessCpuLoad() * 100);
                    long ram = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                    double ramMb = ram / (1024.0 * 1024.0);

                    String jsonResponse = String.format(
                        "{\"status\":\"ok\",\"metrics\":{\"cpu\":%.1f,\"ram_mb\":%.1f,\"gpu\":0.0}}", cpu, ramMb);
                    byte[] response = jsonResponse.getBytes();
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                });
                
                server.setExecutor(null); // Use default executor
                server.start();
                running = true;
                
                logger.info("✅ [API] Server online at http://127.0.0.1:8082/jarvis/command");
                return true;
                
            } catch (IOException e) {
                logger.error("❌ [API] Failed to start server: " + e.getMessage());
                return false;
            }
        });
    }
    
    /**
     * Stop the API server
     */
    public void stopServer() {
        if (server != null && running) {
            server.stop(0);
            running = false;
            logger.info("JARVIS API server stopped");
        }
    }
    
    /**
     * HTTP handler for command endpoints
     */
    private class CommandHandler implements HttpHandler {
        
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                
                logger.info("🌐 [API] " + method + " request to " + path);
                logger.info("🌐 [API] Remote: " + exchange.getRemoteAddress());
                logger.info("🌐 [API] Headers: " + exchange.getRequestHeaders());
                
                // Handle OPTIONS for CORS preflight
                if ("OPTIONS".equals(method)) {
                    sendResponse(exchange, 200, "");
                    return;
                }
                
                // Only handle POST requests for commands
                if (!"POST".equals(method)) {
                    sendResponse(exchange, 405, "Method Not Allowed");
                    return;
                }
                
                // Read request body
                String requestBody = readRequestBody(exchange);
                logger.info("📥 [API] Body length: " + (requestBody != null ? requestBody.length() : 0));
                logger.info("📥 [API] Received body: " + requestBody);
                
                // Parse command from JSON
                String command = extractCommandFromJson(requestBody);
                
                if (command != null && !command.isEmpty()) {
                    logger.info("📝 [API] Extracted command: " + command);
                    
                    // Update ear input display in UI
                    controller.updateEarInputDisplay(command);
                    
                    // Process the command
                    controller.processVoiceCommand(command);
                    
                    // Send success response
                    sendResponse(exchange, 200, "Command received and processed");
                    logger.info("✅ [API] Command processed successfully");
                } else {
                    logger.warn("⚠️ [API] Invalid or empty command received");
                    sendResponse(exchange, 400, "Invalid command");
                }
                
            } catch (Exception e) {
                logger.error("❌ [API] Error handling command request", e);
                sendResponse(exchange, 500, "Internal Server Error");
            }
        }
        
        private String readRequestBody(HttpExchange exchange) throws IOException {
            InputStream inputStream = exchange.getRequestBody();
            StringBuilder body = new StringBuilder();
            
            byte[] buffer = new byte[1024];
            int bytesRead;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                body.append(new String(buffer, 0, bytesRead));
            }
            
            inputStream.close();
            return body.toString();
        }
        
        private String extractCommandFromJson(String json) {
            try {
                if (json == null || json.trim().isEmpty()) {
                    logger.warn(" [API] Empty JSON body");
                    return null;
                }

                JsonNode root = objectMapper.readTree(json);
                JsonNode commandNode = root.get("command");
                if (commandNode == null || commandNode.isNull()) {
                    logger.warn(" [API] Missing 'command' field in JSON");
                    return null;
                }

                String command = commandNode.asText("").trim();
                if (command.isEmpty()) {
                    logger.warn(" [API] 'command' field is empty");
                    return null;
                }

                logger.info(" [API] Successfully extracted command: '" + command + "'");
                return command;
            } catch (Exception e) {
                logger.error(" [API] Error parsing command JSON", e);
                return null;
            }
        }
        
        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            byte[] responseBytes = response.getBytes("UTF-8");
            
            // Set proper response headers
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            
            OutputStream outputStream = exchange.getResponseBody();
            outputStream.write(responseBytes);
            outputStream.close();
        }
    }
}
