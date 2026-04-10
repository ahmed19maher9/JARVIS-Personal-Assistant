package com.jarvis;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class JarvisConfig {

    private static volatile JarvisConfig instance;

    private final Properties properties;

    private JarvisConfig(Properties properties) {
        this.properties = properties;
    }

    public static JarvisConfig get() {
        JarvisConfig local = instance;
        if (local != null) {
            return local;
        }
        synchronized (JarvisConfig.class) {
            if (instance == null) {
                instance = new JarvisConfig(loadProperties());
            }
            return instance;
        }
    }

    private static Properties loadProperties() {
        Properties props = new Properties();

        try (InputStream in = JarvisConfig.class.getResourceAsStream("/jarvis.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception ignored) {
        }

        Path overridePath = Paths.get(System.getProperty("user.dir"), "jarvis.properties");
        if (Files.exists(overridePath)) {
            try (InputStream in = Files.newInputStream(overridePath)) {
                Properties override = new Properties();
                override.load(in);
                props.putAll(override);
            } catch (Exception ignored) {
            }
        }

        return props;
    }

    public String getString(String key, String defaultValue) {
        String v = properties.getProperty(key);
        if (v == null) {
            return defaultValue;
        }
        String trimmed = v.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
    }

    public int getInt(String key, int defaultValue) {
        String v = getString(key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public String conversationsDir() {
        return getString("jarvis.conversations.dir", "conversations");
    }

    public String trainingDataDir() {
        return getString("jarvis.training_data.dir", "conversations");
    }

    public String scriptsDir() {
        return getString("jarvis.scripts.dir", Paths.get(System.getProperty("user.dir"), "scripts").toString());
    }

    public String voicesDir() {
        return getString(
            "jarvis.voices.dir",
            Paths.get(scriptsDir(), "kokoro", "hf_cache", "voices").toString()
        );
    }

    public String mouthApiBaseUrl() {
        return getString("jarvis.mouth.api_base_url", "http://127.0.0.1:8880");
    }

    public String ollamaApiBaseUrl() {
        return getString("jarvis.ollama.api_base_url", "http://127.0.0.1:11434");
    }

    public String ocularApiBaseUrl() {
        return getString("jarvis.ocular.api_base_url", "http://127.0.0.1:8882");
    }

    public String earsApiBaseUrl() {
        return getString("jarvis.ears.api_base_url", "http://127.0.0.1:8881");
    }

    public String actuatorsApiBaseUrl() {
        return getString("jarvis.actuators.api_base_url", "http://127.0.0.1:8084");
    }

    public String memoryApiBaseUrl() {
        return getString("jarvis.memory.api_base_url", "http://127.0.0.1:8884");
    }
}
