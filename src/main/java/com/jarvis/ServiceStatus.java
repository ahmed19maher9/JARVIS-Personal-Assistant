package com.jarvis;

public record ServiceStatus(
        JarvisModule module,
        ServiceState state,
        long latencyMs,
        String detail,
        double cpuUsage,
        double ramUsageMb,
        double gpuUsage,
        String modelName
) {
    public ServiceStatus(JarvisModule module, ServiceState state, long latencyMs, String detail) {
        this(module, state, latencyMs, detail, 0, 0, 0, null);
    }
}
