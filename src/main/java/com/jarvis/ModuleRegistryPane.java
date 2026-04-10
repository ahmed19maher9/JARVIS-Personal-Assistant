package com.jarvis;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.ProgressIndicator;
import javafx.util.Duration;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.EnumMap;
import java.util.Map;

public class ModuleRegistryPane extends VBox {

    private static final String PRIMARY_COLOR = "#00FFFF";

    private final Map<JarvisModule, Row> rows = new EnumMap<>(JarvisModule.class);
    private final Map<JarvisModule, ServiceStatus> latestStatusMap = new EnumMap<>(JarvisModule.class);
    private ModuleControlListener controlListener;
    private TotalRow totalRow;

    public interface ModuleControlListener {
        void onStart(JarvisModule module);
        void onStop(JarvisModule module);
    }

    public void setControlListener(ModuleControlListener listener) {
        this.controlListener = listener;
    }

    public ModuleRegistryPane() {
        setSpacing(8);
        setPadding(new Insets(10));
        setAlignment(Pos.TOP_LEFT);
        setPrefWidth(700); // Widened to accommodate longer metric labels
        setStyle("-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 12; -fx-border-color: rgba(0,255,255,0.35); -fx-border-radius: 12;");

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("MODULE REGISTRY");
        title.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        title.setTextFill(Color.web("#CCCCCC"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button startAll = new Button("START ALL");
        Button stopAll = new Button("STOP ALL");
        String btnStyle = "-fx-background-color: transparent; -fx-border-radius: 4; -fx-font-size: 10; -fx-font-weight: bold; -fx-padding: 4 12;";
        startAll.setStyle(btnStyle + "-fx-text-fill: #00FF88; -fx-border-color: #00FF88;");
        stopAll.setStyle(btnStyle + "-fx-text-fill: #FF4444; -fx-border-color: #FF4444;");

        startAll.setOnAction(e -> {
            for (JarvisModule m : JarvisModule.values()) {
                if (controlListener != null) controlListener.onStart(m);
                if (rows.containsKey(m)) rows.get(m).startStartupTimer();
            }
        });

        stopAll.setOnAction(e -> {
            for (JarvisModule m : JarvisModule.values()) {
                if (controlListener != null) controlListener.onStop(m);
                if (rows.containsKey(m)) rows.get(m).stopStartupTimer();
                update(new ServiceStatus(m, ServiceState.OFFLINE, -1, "Manual Stop"));
            }
        });

        HBox headerButtons = new HBox(15, startAll, stopAll);
        header.getChildren().addAll(title, spacer, headerButtons);
        getChildren().add(header);

        for (JarvisModule module : JarvisModule.values()) {
            Row row = new Row(module, (m, start) -> {
                if (controlListener != null) {
                    if (start) controlListener.onStart(m);
                    else controlListener.onStop(m);
                }
            });
            rows.put(module, row);
            getChildren().add(row.container);
        }

        totalRow = new TotalRow();
        getChildren().add(totalRow.container);
    }

    public void update(ServiceStatus status) {
        Row row = rows.get(status.module());
        if (row != null) {
            row.apply(status);
        }
        latestStatusMap.put(status.module(), status);
        updateTotals();
    }

    public void setHoverHighlight(JarvisModule module, boolean highlight) {
        Row row = rows.get(module);
        if (row != null) row.setHighlight(highlight);
    }

    private void updateTotals() {
        double cpu = 0, ram = 0, gpu = 0;
        for (ServiceStatus s : latestStatusMap.values()) {
            if (s.state() != ServiceState.OFFLINE) {
                cpu += s.cpuUsage();
                ram += s.ramUsageMb();
                gpu += s.gpuUsage();
            }
        }
        if (totalRow != null) totalRow.update(cpu, ram, gpu);
    }

    private static final class Row {
        private final HBox container;
        private final Label name;
        private final Label state;
        private final Label latency;
        private final Label metrics;
        private final ProgressIndicator loading;
        private final Button startBtn;
        private final Button stopBtn;
        private final JarvisModule module; // Store module reference
        private Timeline startupTimer;
        private double elapsedSeconds;
        private String startupTimeInfo = "";
        private boolean isStopping = false;
        private String lastStatusColor = "#CCCCCC";
        private boolean isHighlighted = false;
        private Timeline stoppingTimer; // Timer to force clear stopping flag

        private interface ActionProxy { void run(JarvisModule m, boolean start); }

        private Row(JarvisModule module, ActionProxy proxy) {
            this.module = module; // Initialize module field
            container = new HBox(8);
            container.setAlignment(Pos.CENTER_LEFT);
            container.setPadding(new Insets(6));
            container.setStyle("-fx-background-color: rgba(26,26,26,0.65); -fx-background-radius: 8;");

            loading = new ProgressIndicator(-1);
            loading.setPrefSize(14, 14);
            loading.setMinSize(14, 14);
            loading.setStyle("-fx-progress-color: " + PRIMARY_COLOR + ";");
            loading.managedProperty().bind(loading.visibleProperty());
            loading.setVisible(true);

            name = new Label(module.getDisplayName());
            name.setFont(Font.font("Arial", FontWeight.BOLD, 11));
            name.setTextFill(Color.web(PRIMARY_COLOR));
            name.setMinWidth(110);
            name.setPrefWidth(110);

            state = new Label("UNKNOWN");
            state.setFont(Font.font("Arial", FontWeight.BOLD, 10));
            state.setTextFill(Color.web("#CCCCCC"));

            HBox statusBox = new HBox(6, loading, state);
            statusBox.setAlignment(Pos.CENTER_LEFT);
            statusBox.setMinWidth(180);
            statusBox.setPrefWidth(180);

            latency = new Label("-");
            latency.setFont(Font.font("Courier New", FontWeight.NORMAL, 11));
            latency.setTextFill(Color.web("#CCCCCC"));
            latency.setMinWidth(30);

            metrics = new Label("");
            metrics.setFont(Font.font("Courier New", FontWeight.NORMAL, 9));
            metrics.setTextFill(Color.web("#888888"));
            metrics.setMinWidth(250);

            startBtn = new Button("▶");
            stopBtn = new Button("■");
            startBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #00FF88; -fx-border-color: #00FF88; -fx-border-radius: 4; -fx-font-size: 12; -fx-padding: 4 8;");
            stopBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #FF4444; -fx-border-color: #FF4444; -fx-border-radius: 4; -fx-font-size: 12; -fx-padding: 4 8;");
            
            startBtn.setOnAction(e -> {
                proxy.run(module, true);
                startStartupTimer();
            });
            
            stopBtn.setOnAction(e -> {
                stopStartupTimer(); // Immediate termination of the startup countdown
                isStopping = true;
                state.setText("STOPPING...");
                state.setTextFill(Color.web("#FFB000"));
                loading.setVisible(true);
                
                startStoppingTimer();
                proxy.run(module, false);
            });
            
            stopStartupTimer();

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox controls = new HBox(10, startBtn, stopBtn);

            container.getChildren().addAll(name, statusBox, latency, metrics, spacer, controls);
        }

        private void setHighlight(boolean highlight) {
            this.isHighlighted = highlight;
            refreshStyle();
        }

        private void refreshStyle() {
            String color = isHighlighted ? "#FF00FF" : lastStatusColor;
            double width = isHighlighted ? 2.0 : 1.0;
            String glow = isHighlighted ? "-fx-effect: dropshadow(gaussian, #FF00FF, 15, 0.5, 0, 0);" : "";
            
            container.setStyle("-fx-background-color: rgba(26,26,26,0.65); -fx-background-radius: 8; " +
                               "-fx-border-color: " + color + "; -fx-border-radius: 8; -fx-border-width: " + width + ";" + glow);
        }

        private void startStartupTimer() {
            stopStartupTimer();
            elapsedSeconds = 0.0;
            startupTimeInfo = "";
            
            state.setText("STARTING 0.0s");
            this.lastStatusColor = "#66AAFF";
            loading.setVisible(true);
            state.setTextFill(Color.web(lastStatusColor));
            refreshStyle();

            startupTimer = new Timeline(new KeyFrame(Duration.millis(100), e -> {
                elapsedSeconds += 0.1;
                state.setText(String.format("STARTING %.1fs", elapsedSeconds));
            }));
            startupTimer.setCycleCount(Timeline.INDEFINITE);
            startupTimer.play();
        }

        private void stopStartupTimer() {
            if (startupTimer != null) {
                startupTimer.stop();
                startupTimer = null;
            }
        }

        private String formatDuration(double seconds) {
            if (seconds < 60) return String.format("%.1fs", seconds);
            int totalSeconds = (int) seconds;
            int h = totalSeconds / 3600;
            int m = (totalSeconds % 3600) / 60;
            int s = totalSeconds % 60;
            if (h > 0) return String.format("%dh %dm %ds", h, m, s);
            return String.format("%dm %ds", m, s);
        }

        private void startStoppingTimer() {
            if (stoppingTimer != null) {
                stoppingTimer.stop();
            }
            stoppingTimer = new Timeline(new KeyFrame(Duration.seconds(10), event -> {
                isStopping = false;
                // Force update to current status
                apply(new ServiceStatus(module, ServiceState.OFFLINE, -1, "Timeout"));
            }));
            stoppingTimer.setCycleCount(1);
            stoppingTimer.play();
        }

        private void apply(ServiceStatus status) {
            // Once the monitor confirms the service is unreachable, clear the stopping flag and timer
            if (status.state() == ServiceState.OFFLINE) {
                isStopping = false;
                stopStartupTimer(); // Ensure countdown is aborted if health check fails or module is stopped
                if (stoppingTimer != null) {
                    stoppingTimer.stop();
                    stoppingTimer = null;
                }
            }

            if (startupTimer != null) {
                // While in the startup phase, we ignore 'OFFLINE' reports from the monitor.
                // We keep counting until the service actually heartbeats with an active state.
                if (status.state() == ServiceState.READY || status.state() == ServiceState.DEGRADED || 
                    status.state() == ServiceState.BUSY) {
                    
                    startupTimeInfo = String.format(" (Started in: %s)", formatDuration(elapsedSeconds));
                    stopStartupTimer();
                } else {
                    // Ignore temporary offline/unknown states while the timer is running
                    return;
                }
            }

            // If we are currently stopping, we don't let the monitor's 'READY' reports overwrite the label
            if (isStopping && status.state() != ServiceState.OFFLINE) {
                return;
            }

            // If the module was already online and now fails its health check, set it to OFFLINE
            if (status.state() == ServiceState.OFFLINE) {
                startupTimeInfo = "";
            }

            // Show loading indicator for transitional or unknown states
            loading.setVisible(status.state() == ServiceState.UNKNOWN || 
                            status.state() == ServiceState.STARTING ||
                            startupTimer != null ||
                            isStopping);

            if (startupTimer == null) {
                state.setText(status.state().name() + startupTimeInfo);
            }

            latency.setText(status.latencyMs() >= 0 ? status.latencyMs() + "ms" : "-");
            
            metrics.setText(String.format("CPU:%.1f%% RAM:%.0fMB GPU:%.0f%%", 
                status.cpuUsage(), status.ramUsageMb(), status.gpuUsage()));

            String c;
            switch (status.state()) {
                case READY -> c = "#00FF88";
                case DEGRADED -> c = "#FFB000";
                case OFFLINE -> c = "#FF4444";
                case BUSY -> c = "#FF00FF";
                case STARTING -> c = "#66AAFF";
                default -> c = "#CCCCCC";
            }
            this.lastStatusColor = c;
            state.setTextFill(Color.web(lastStatusColor));
            refreshStyle();
        }
    }

    private static final class TotalRow {
        private final HBox container;
        private final Label metrics;

        private TotalRow() {
            container = new HBox(8);
            container.setAlignment(Pos.CENTER_LEFT);
            container.setPadding(new Insets(6));
            container.setStyle("-fx-background-color: rgba(0,255,255,0.08); -fx-background-radius: 8; -fx-border-color: rgba(0,255,255,0.3); -fx-border-width: 1;");

            Label name = new Label("SYSTEM TOTAL");
            name.setFont(Font.font("Arial", FontWeight.BOLD, 11));
            name.setTextFill(Color.web(PRIMARY_COLOR));
            name.setMinWidth(280); // Align metrics with other rows

            metrics = new Label("CPU:0.0% RAM:0MB GPU:0%");
            metrics.setFont(Font.font("Courier New", FontWeight.BOLD, 10));
            metrics.setTextFill(Color.web(PRIMARY_COLOR));

            container.getChildren().addAll(name, metrics);
        }

        private void update(double cpu, double ram, double gpu) {
            metrics.setText(String.format("CPU:%.1f%% RAM:%.0fMB GPU:%.0f%%", cpu, ram, gpu));
        }
    }
}
