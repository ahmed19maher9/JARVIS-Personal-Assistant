package com.jarvis;

public enum JarvisModule {
    CORTEX_BRAIN("Cortex (Brain)", "Central Logic"),
    OCULAR_EYES("Ocular (Eyes)", "Computer Vision"),
    AURAL_EARS("Aural (Ears)", "Speech-to-Text"),
    VOCAL_MOUTH("Vocal (Mouth)", "Text-to-Speech"),
    CORE_REACTOR("Core (Reactor)", "System Health"),
    REPULSORS("Repulsors (Hands)", "Actuation"),
    LONG_TERM_MEMORY("Cortex (Memory)", "Vector Semantic Search");

    private final String displayName;
    private final String description;

    JarvisModule(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
