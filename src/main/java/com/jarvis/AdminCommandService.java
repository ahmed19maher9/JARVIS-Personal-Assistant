package com.jarvis;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Service for executing administrative commands with elevated privileges
 * Provides secure command execution with validation and logging
 */
public class AdminCommandService {
    
    private static final Logger logger = LoggerFactory.getLogger(AdminCommandService.class);
    
    // Dangerous command patterns to block
    private static final String[] DANGEROUS_COMMANDS = {
        "format", "del ", "rmdir", "rd ", "shutdown", "reboot", 
        "reg delete", "net user", "net localgroup", "cipher",
        "sdelete", "format.com", "del.com"
    };
    
    // File system patterns that should be protected
    private static final String[] PROTECTED_PATHS = {
        "C:\\Windows", "C:\\Program Files", "C:\\Program Files (x86)",
        "C:\\Users", "System32", "SysWOW64"
    };
    
    /**
     * Execute an administrative command with elevated privileges
     * 
     * @param command The command to execute
     * @param timeoutSeconds Maximum execution time
     * @return CompletableFuture with command result
     */
    public CompletableFuture<CommandResult> executeAdminCommand(String command, int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Executing admin command: " + sanitizeLogOutput(command));
                
                // Validate command safety
                CommandValidation validation = validateCommand(command);
                if (!validation.isValid()) {
                    logger.warn("Command blocked by safety validation: " + validation.getReason());
                    return new CommandResult(-1, "", "Command blocked: " + validation.getReason(), false);
                }
                
                // Execute with elevated privileges
                CommandResult result = executeElevatedCommand(command, timeoutSeconds);
                
                if (result.isSuccess()) {
                    logger.info("Admin command executed successfully");
                } else {
                    logger.error("Admin command failed: " + result.getError());
                }
                
                return result;
                
            } catch (Exception e) {
                logger.error("Error executing admin command", e);
                return new CommandResult(-1, "", e.getMessage(), false);
            }
        });
    }
    
    /**
     * Validate command for safety
     */
    private CommandValidation validateCommand(String command) {
        String lowerCommand = command.toLowerCase().trim();
        
        // Check for dangerous commands
        for (String dangerous : DANGEROUS_COMMANDS) {
            if (lowerCommand.contains(dangerous.toLowerCase())) {
                return new CommandValidation(false, "Contains dangerous command: " + dangerous);
            }
        }
        
        // Check for protected paths
        for (String protectedPath : PROTECTED_PATHS) {
            if (lowerCommand.contains(protectedPath.toLowerCase())) {
                return new CommandValidation(false, "Attempts to access protected path: " + protectedPath);
            }
        }
        
        // Check for suspicious patterns
        if (Pattern.compile("[<>|&;`]").matcher(command).find()) {
            return new CommandValidation(false, "Contains suspicious characters");
        }
        
        return new CommandValidation(true, "Command is safe");
    }
    
    /**
     * Execute command with elevated privileges using PowerShell
     */
    private CommandResult executeElevatedCommand(String command, int timeoutSeconds) {
        try {
            // Create PowerShell command for elevated execution
            String powershellCommand = String.format(
                "powershell -Command \"Start-Process cmd -ArgumentList '/k color 0B && prompt [JARVIS] $G && %s' -Verb RunAs\"",
                escapePowerShellString(command)
            );
            
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            
            CommandLine cmdLine = CommandLine.parse(powershellCommand);
            DefaultExecutor executor = new DefaultExecutor();
            executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));
            ExecuteWatchdog watchdog = new ExecuteWatchdog(timeoutSeconds * 1000L);
            executor.setWatchdog(watchdog);
            
            int exitCode = executor.execute(cmdLine);
            
            String output = stdout.toString();
            String error = stderr.toString();
            
            return new CommandResult(exitCode, output, error, exitCode == 0);
            
        } catch (ExecuteException e) {
            logger.error("Command execution failed", e);
            return new CommandResult(e.getExitValue(), "", e.getMessage(), false);
        } catch (IOException e) {
            logger.error("IO error during command execution", e);
            return new CommandResult(-1, "", e.getMessage(), false);
        }
    }
    
    /**
     * Execute a command silently in the background without creating a new window
     * 
     * @param command The command to execute
     * @param timeoutSeconds Maximum execution time
     * @return CompletableFuture with command result
     */
    public CompletableFuture<CommandResult> executeSilentCommand(String command, int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Executing silent command: " + sanitizeLogOutput(command));
                CommandResult result = executeCommandSync(command, timeoutSeconds);
                return result;
            } catch (Exception e) {
                logger.error("Error executing silent command", e);
                return new CommandResult(-1, "", e.getMessage(), false);
            }
        });
    }

    /**
     * Execute regular (non-elevated) command
     */
    public CompletableFuture<CommandResult> executeCommand(String command, int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Executing command: " + sanitizeLogOutput(command));
                
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                
                String title = "JARVIS-TASK: " + (command.length() > 20 ? command.substring(0, 20) : command);
                CommandLine cmdLine = CommandLine.parse("cmd.exe /c start \"" + title + "\" cmd /k \"color 0B && prompt [JARVIS] $G && " + command + "\"");
                DefaultExecutor executor = new DefaultExecutor();
                executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));
                ExecuteWatchdog watchdog = new ExecuteWatchdog(timeoutSeconds * 1000L);
                executor.setWatchdog(watchdog);
                
                int exitCode = executor.execute(cmdLine);
                
                String output = stdout.toString();
                String error = stderr.toString();
                
                return new CommandResult(exitCode, output, error, exitCode == 0);
                
            } catch (Exception e) {
                logger.error("Error executing command", e);
                return new CommandResult(-1, "", e.getMessage(), false);
            }
        });
    }
    
    /**
     * Get system information
     */
    public CompletableFuture<SystemInfo> getSystemInfo() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Gathering system information...");
                
                StringBuilder info = new StringBuilder();
                
                // Get system information using system commands
                String[] commands = {
                    "systeminfo | findstr /B /C:\"OS Name\" /C:\"OS Version\" /C:\"System Type\" /C:\"Total Physical Memory\"",
                    "wmic cpu get name",
                    "wmic diskdrive get size,model",
                    "ipconfig | findstr IPv4"
                };
                
                for (String cmd : commands) {
                    CommandResult result = executeCommandSync(cmd, 30);
                    if (result.isSuccess()) {
                        info.append(result.getOutput()).append("\n");
                    }
                }
                
                return new SystemInfo(info.toString(), true);
                
            } catch (Exception e) {
                logger.error("Error getting system info", e);
                return new SystemInfo("Error: " + e.getMessage(), false);
            }
        });
    }
    
    /**
     * Execute command synchronously
     */
    private CommandResult executeCommandSync(String command, int timeoutSeconds) {
        try {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            
            CommandLine cmdLine = CommandLine.parse("cmd.exe /c " + command);
            DefaultExecutor executor = new DefaultExecutor();
            executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));
            ExecuteWatchdog watchdog = new ExecuteWatchdog(timeoutSeconds * 1000L);
            executor.setWatchdog(watchdog);
            
            int exitCode = executor.execute(cmdLine);
            
            return new CommandResult(exitCode, stdout.toString(), stderr.toString(), exitCode == 0);
            
        } catch (Exception e) {
            return new CommandResult(-1, "", e.getMessage(), false);
        }
    }
    
    /**
     * Escape string for PowerShell
     */
    private String escapePowerShellString(String input) {
        return input.replace("\"", "`\"").replace("'", "`'").replace("`", "``");
    }
    
    /**
     * Sanitize command output for logging
     */
    private String sanitizeLogOutput(String command) {
        // Remove sensitive information from logs
        return command.replaceAll("(password|pwd|token|key)\\s*=\\s*\\S+", "$1=***");
    }
    
    /**
     * Command validation result
     */
    private static class CommandValidation {
        private final boolean valid;
        private final String reason;
        
        CommandValidation(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }
        
        boolean isValid() { return valid; }
        String getReason() { return reason; }
    }
    
    /**
     * Command execution result
     */
    public static class CommandResult {
        private final int exitCode;
        private final String output;
        private final String error;
        private final boolean success;
        
        CommandResult(int exitCode, String output, String error, boolean success) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
            this.success = success;
        }
        
        public int getExitCode() { return exitCode; }
        public String getOutput() { return output; }
        public String getError() { return error; }
        public boolean isSuccess() { return success; }
    }
    
    /**
     * System information holder
     */
    public static class SystemInfo {
        private final String info;
        private final boolean success;
        
        SystemInfo(String info, boolean success) {
            this.info = info;
            this.success = success;
        }
        
        public String getInfo() { return info; }
        public boolean isSuccess() { return success; }
    }
}
