package com.jarvis;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.collections.ListChangeListener;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.Image;
import javafx.scene.layout.Priority;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Glow;
import javafx.scene.CacheHint;
import javafx.scene.Node;
import javafx.animation.*;
import javafx.util.Duration;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * JARVIS Personal Assistant - Premium Cyberpunk UI
 * A sophisticated AI assistant with metallic cyberpunk interface
 */
public class JarvisApplication extends Application {

    private static final String PRIMARY_COLOR = "#00FFFF";
    private static final String SECONDARY_COLOR = "#FF00FF";
    private static final String BACKGROUND_COLOR = "#0A0A0A";
    private static final String METALLIC_COLOR = "#1A1A1A";
    
    private Label statusLabel;
    private Label listeningLabel;
    private Label responseLabel;
    private Label earInputDisplay;
    private Label mouthResponseDisplay;
    private ComboBox<String> voiceComboBox;
    private ComboBox<String> brainModelComboBox;
    private ImageView voiceImageView;
    private AudioVisualizer leftVisualizer;
    private AudioVisualizer rightVisualizer;
    private StackPane earIndicator;
    private StackPane mouthIndicator;
    private Circle earBulb;
    private Circle mouthBulb;
    private Random random = new Random();
    private JarvisController controller;
    private ServiceHealthMonitor healthMonitor;
    private StackPane shutdownOverlay;
    private Label shutdownStatusLabel;
    private BodyMapPane bodyMapPane;
    private ModuleRegistryPane moduleRegistryPane;
    private JarvisGymTab gymTab;
    private final Map<JarvisModule, ServiceStatus> moduleStatuses = new EnumMap<>(JarvisModule.class);
    private Timeline reactorPulseTimer;
    private RotateTransition reactorRotateTransition;
    private boolean pulseSoundPlaying = false;
    private TabPane consoleSubTabPane;
    private final Map<String, TextArea> consoleOutputMap = new ConcurrentHashMap<>();
    private boolean initialStatusReported = false; // Track if initial status has been reported

    @Override
    public void start(Stage primaryStage) {
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setTitle("JARVIS - Personal Assistant");
        
        // Instantiate controller first so it can be passed to GymTab during UI creation
        controller = new JarvisController(this);

        // Create main container
        VBox mainLayout = createMainContainer();
        
        // Setup Shutdown Overlay
        shutdownOverlay = createShutdownOverlay();
        StackPane root = new StackPane(mainLayout, shutdownOverlay);
        
        // Create scene optimized for Dell 7577 (GTX 1060)
        Scene scene = new Scene(root, 1200, 800, true); // Enable depth buffer for better performance
        scene.setFill(Color.TRANSPARENT);
        
        // Performance optimizations for GTX 1060
        scene.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        Platform.runLater(() -> {
            // Apply performance settings after scene is ready
            scene.getRoot().setCache(true);
            scene.getRoot().setCacheHint(CacheHint.SPEED);
        });
        
        // Apply custom styles
        applyStyles(scene);
        
        primaryStage.setScene(scene);
        primaryStage.centerOnScreen();
        primaryStage.show();
        
        // Start animations
        startAnimations();
        
        // Optimize startup sequence for Dell 7577
        Platform.runLater(() -> {
            // Start JARVIS systems after UI is ready
            controller.initialize();
            
            // Inject UI components into controller
            controller.setUIComponents(statusLabel, listeningLabel, responseLabel, leftVisualizer, rightVisualizer, earInputDisplay, mouthResponseDisplay);

            // Bind module registry controls to controller actions
            if (moduleRegistryPane != null) {
                moduleRegistryPane.setControlListener(new ModuleRegistryPane.ModuleControlListener() {
                    @Override public void onStart(JarvisModule m) { controller.startModule(m); }
                    @Override public void onStop(JarvisModule m) { controller.stopModule(m); }
                });
            }

            // Start service health monitor with delay for stability
            CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() -> {
                healthMonitor = new ServiceHealthMonitor(controller::onServiceStatusUpdate, this::updateBrainModelList);
                controller.setHealthMonitor(healthMonitor);
                healthMonitor.start();
            });
        });
        
        // Set up shutdown hook
        primaryStage.setOnCloseRequest(event -> {
            event.consume(); // Intercept closing to allow for background cleanup tasks
            controller.shutdown(); // Refactored to handle its own lifecycle
        });
    }

    /**
     * Updates the UI components and internal state with a new service status
     */
    public void onServiceStatusUpdate(ServiceStatus status) {
        ServiceStatus previous = moduleStatuses.get(status.module());
        moduleStatuses.put(status.module(), status);
        
        Platform.runLater(() -> {
            if (moduleRegistryPane != null) {
                moduleRegistryPane.update(status);
            }
            if (bodyMapPane != null) {
                bodyMapPane.update(status);
            }
            updateGlobalStatus();

            // Trigger immediate model discovery if Cortex just moved to READY state
            if (status.module() == JarvisModule.CORTEX_BRAIN && 
                status.state() == ServiceState.READY && 
                (previous == null || previous.state() != ServiceState.READY)) {
                CompletableFuture.supplyAsync(() -> controller.getPythonService().getAvailableBrainModels())
                    .thenAccept(this::updateBrainModelList);
            }
        });
    }

    public void updateGymProgress(double progress) {
        if (gymTab != null) {
            gymTab.setProgress(progress);
        }
    }

    /**
     * Updates the Brain Model ComboBox with a new list of models while preserving selection.
     */
    public void updateBrainModelList(java.util.List<String> models) {
        Platform.runLater(() -> {
            if (brainModelComboBox == null) return;
            
            String currentSelection = brainModelComboBox.getSelectionModel().getSelectedItem();
            java.util.List<String> sortedModels = new java.util.ArrayList<>(models);
            java.util.Collections.sort(sortedModels);
            
            // Use List.equals to check if items changed (order matters)
            if (!brainModelComboBox.getItems().equals(sortedModels)) {
                brainModelComboBox.getItems().setAll(sortedModels);
                
                // Restore selection if the model still exists
                if (currentSelection != null && sortedModels.contains(currentSelection)) {
                    brainModelComboBox.getSelectionModel().select(currentSelection);
                } else if (currentSelection == null && !sortedModels.isEmpty()) {
                    // Fallback to active model if selection was lost
                    String active = controller.getPythonService().getSelectedBrainModel();
                    
                    // Enhanced matching: Try to match base name (e.g., 'jarvis' should match 'jarvis:latest')
                    String bestMatch = null;
                    for (String model : sortedModels) {
                        if (model.equals(active)) {
                            bestMatch = model;
                            break;
                        }
                        if (model.startsWith(active + ":")) {
                            bestMatch = model;
                        }
                    }
                    
                    if (bestMatch != null) {
                        brainModelComboBox.getSelectionModel().select(bestMatch);
                        controller.getPythonService().setSelectedBrainModel(bestMatch);
                    } else {
                        brainModelComboBox.getSelectionModel().select(sortedModels.get(0));
                    }
                }
            }
                            
            // Mandatory Synchronization: Ensure Gym tab always has latest Ollama models
            if (this.gymTab != null) {
                this.gymTab.updateModels(sortedModels);
            }
        });
    }

    private void updateGlobalStatus() {
        if (controller == null || statusLabel == null) return;
        
        // Priority: If the AI is actively listening or processing, keep that status visible
        if (controller.isListening() || controller.isProcessing()) {
            return;
        }

        int offline = 0;
        int degraded = 0;
        int starting = 0;
        StringBuilder offlineModules = new StringBuilder();

        for (JarvisModule m : JarvisModule.values()) {
            ServiceStatus s = moduleStatuses.get(m);
            if (s == null) continue;
            
            if (s.state() == ServiceState.OFFLINE) {
                offline++;
                offlineModules.append(m.getDisplayName()).append(", ");
            } else if (s.state() == ServiceState.DEGRADED) {
                degraded++;
            } else if (s.state() == ServiceState.STARTING) {
                starting++;
            }
        }

        String text;
        String color;

        if (offline > 0) {
            String list = offlineModules.substring(0, offlineModules.length() - 2);
            text = "CRITICAL: " + offline + " MODULES OFFLINE [" + list + "]";
            color = "#FF4444";
        } else if (starting > 0 || moduleStatuses.isEmpty()) {
            text = "SYSTEM INITIALIZING... STANDBY";
            color = "#66AAFF";
        } else if (degraded > 0) {
            text = "WARNING: SYSTEM PERFORMANCE DEGRADED";
            color = "#FFB000";
        } else {
            text = "SYSTEMS NOMINAL - ALL MODULES ONLINE";
            color = "#00FF00";
        }

        final String finalStatus = text;
        final String finalColor = color;
        Platform.runLater(() -> {
            statusLabel.setText("System Status: " + finalStatus);
            statusLabel.setTextFill(Color.web(finalColor));
        });
    }

    private StackPane createShutdownOverlay() {
        StackPane overlay = new StackPane();
        overlay.setVisible(false);
        overlay.setStyle("-fx-background-color: rgba(0, 0, 0, 0.85);");
        
        VBox content = new VBox(25);
        content.setAlignment(Pos.CENTER);
        
        Node reactor = createArcReactor();
        
        shutdownStatusLabel = new Label("SYSTEM SHUTDOWN INITIATED");
        shutdownStatusLabel.setFont(Font.font("Arial", FontWeight.BOLD, 22));
        shutdownStatusLabel.setTextFill(Color.web(PRIMARY_COLOR));
        shutdownStatusLabel.setStyle("-fx-effect: dropshadow(gaussian, " + PRIMARY_COLOR + ", 15, 0.5, 0, 0);");
        
        content.getChildren().addAll(reactor, shutdownStatusLabel);
        overlay.getChildren().add(content);
        
        return overlay;
    }

    private Node createArcReactor() {
        StackPane reactor = new StackPane();
        reactor.setPrefSize(160, 160);
        reactor.setMaxSize(160, 160);

        // Outer Halo
        Circle outerRing = new Circle(75);
        outerRing.setFill(Color.TRANSPARENT);
        outerRing.setStroke(Color.web(PRIMARY_COLOR, 0.3));
        outerRing.setStrokeWidth(2);
        outerRing.setEffect(new Glow(0.5));

        // Rotating Power Cells (Dashed ring)
        Circle powerCells = new Circle(58);
        powerCells.setFill(Color.TRANSPARENT);
        powerCells.setStroke(Color.web(PRIMARY_COLOR, 0.9));
        powerCells.setStrokeWidth(14);
        powerCells.getStrokeDashArray().addAll(18.0, 12.0);
        powerCells.setEffect(new DropShadow(15, Color.web(PRIMARY_COLOR)));

        reactorRotateTransition = new RotateTransition(Duration.seconds(3), powerCells);
        reactorRotateTransition.setByAngle(360);
        reactorRotateTransition.setCycleCount(Animation.INDEFINITE);
        reactorRotateTransition.setInterpolator(Interpolator.LINEAR);

        // Pulsing Central Core
        Circle core = new Circle(30);
        core.setFill(Color.web(PRIMARY_COLOR, 0.5));
        core.setStroke(Color.web(PRIMARY_COLOR));
        core.setStrokeWidth(3);
        core.setEffect(new Glow(1.0));

        reactorPulseTimer = new Timeline(
            new KeyFrame(Duration.ZERO, event -> {
                if (controller != null && !pulseSoundPlaying) {
                    pulseSoundPlaying = true;
                    String soundPath = System.getProperty("user.dir") + "/scripts/resources/sounds/reactor_pulse.mp3";
                    controller.playAudioFile(soundPath, () -> {
                        pulseSoundPlaying = false;
                    });
                }
            }, new KeyValue(core.opacityProperty(), 0.4, Interpolator.EASE_BOTH)),
            new KeyFrame(Duration.seconds(1), new KeyValue(core.opacityProperty(), 1.0, Interpolator.EASE_BOTH)),
            new KeyFrame(Duration.seconds(2), new KeyValue(core.opacityProperty(), 0.4, Interpolator.EASE_BOTH))
        );
        reactorPulseTimer.setCycleCount(Animation.INDEFINITE);

        reactor.getChildren().addAll(outerRing, powerCells, core);
        return reactor;
    }

    public void showShutdownProgress(String message) {
        Platform.runLater(() -> {
            if (shutdownOverlay != null) {
                shutdownOverlay.setVisible(true);
                shutdownStatusLabel.setText(message.toUpperCase());

                // Start animations only when shutdown begins
                if (reactorPulseTimer != null && reactorPulseTimer.getStatus() != Animation.Status.RUNNING) {
                    reactorPulseTimer.play();
                }
                if (reactorRotateTransition != null && reactorRotateTransition.getStatus() != Animation.Status.RUNNING) {
                    reactorRotateTransition.play();
                }
            }
        });
    }

    public void stopShutdownAnimations() {
        Platform.runLater(() -> {
            if (reactorPulseTimer != null) {
                reactorPulseTimer.stop();
            }
            if (reactorRotateTransition != null) {
                reactorRotateTransition.stop();
            }
        });
    }

    private VBox createMainContainer() {
        VBox root = new VBox(0); // Set spacing to 0 to bring tabs flush with header
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20, 40, 10, 40)); // Reduced top and bottom padding
        root.setBackground(createMetallicBackground());
        
        // Create header with JARVIS emblem
        VBox header = createHeader();
        
        TabPane tabs = createTabs();
        
        // Create status bar
        VBox statusBar = createStatusBar();
        
        root.getChildren().addAll(header, tabs);
        
        // Maintain spacing only for the status bar at the bottom
        VBox.setMargin(statusBar, new Insets(10, 0, 0, 0));
        root.getChildren().add(statusBar);
        return root;
    }

    private TabPane createTabs() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-border-color: #00FBFF33;" +
            "-fx-border-width: 0 0 2 0;" +
            "-fx-padding: 0;" // Removed top padding to raise the layout
        );

        Tab chatTab = new Tab("Chat");
        chatTab.setContent(createContentArea());
        styleTab(chatTab, false);

        Tab bodyMapTab = new Tab("Body Map");
        bodyMapTab.setContent(createBodyMapArea());
        styleTab(bodyMapTab, false);

        Tab sensorsTab = new Tab("Sensors");
        sensorsTab.setContent(createSensorsArea());
        styleTab(sensorsTab, false);

        Tab consoleTab = new Tab("Console");
        consoleSubTabPane = new TabPane();
        consoleSubTabPane.getStyleClass().add("console-subtabs");
        consoleSubTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        consoleTab.setContent(consoleSubTabPane);
        styleTab(consoleTab, false);

        Tab gymTabItem = new Tab("Gym");
        this.gymTab = new JarvisGymTab(controller);
        gymTabItem.setContent(this.gymTab);
        styleTab(gymTabItem, false);

        tabPane.getTabs().addAll(chatTab, bodyMapTab, sensorsTab, gymTabItem, consoleTab);
        tabPane.setPrefHeight(620);
        tabPane.setMaxWidth(Double.MAX_VALUE);
        
        // Apply tab-specific styling after tabs are added
        applyTabStyling(tabPane);
        applyTabStyling(consoleSubTabPane);
        
        return tabPane;
    }

    private void styleTab(Tab tab, boolean selected) {
        String baseStyle = 
            "-fx-background-color: rgba(20, 30, 40, 0.8);" +
            "-fx-background-radius: 8 8 0 0;" +
            "-fx-border-color: #00FBFF44;" +
            "-fx-border-width: 1 1 0 1;" +
            "-fx-border-radius: 8 8 0 0;" +
            "-fx-text-fill: " + PRIMARY_COLOR + ";" +
            "-fx-font-family: 'Arial';" +
            "-fx-font-size: 14px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 10 20 8 20;" +
            "-fx-background-insets: 0 0 0 0;" +
            "-fx-effect: dropshadow(gaussian, rgba(0, 251, 255, 0.3), 4, 0.5, 0, 0);";
            
        String selectedStyle = 
            "-fx-background-color: rgba(0, 251, 255, 0.15);" +
            "-fx-background-radius: 8 8 0 0;" +
            "-fx-border-color: #00FBFF;" +
            "-fx-border-width: 2 2 0 2;" +
            "-fx-border-radius: 8 8 0 0;" +
            "-fx-text-fill: " + PRIMARY_COLOR + ";" +
            "-fx-font-family: 'Arial';" +
            "-fx-font-size: 14px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 10 20 8 20;" +
            "-fx-background-insets: 0 0 0 0;" +
            "-fx-effect: dropshadow(gaussian, rgba(0, 251, 255, 0.6), 8, 0.8, 0, 0);";
            
        tab.setStyle(selected ? selectedStyle : baseStyle);
    }

    private void applyTabStyling(TabPane tabPane) {
        // Apply styling after the UI is fully rendered
        Platform.runLater(() -> {
            try {
                // Aggressively set all header and pane backgrounds to transparent to remove the 'white row'
                tabPane.lookupAll(".tab-header-area").forEach(n -> n.setStyle("-fx-background-color: transparent;"));
                tabPane.lookupAll(".tab-header-background").forEach(n -> n.setStyle("-fx-background-color: transparent; -fx-effect: null;"));
                tabPane.lookupAll(".headers-region").forEach(n -> n.setStyle("-fx-background-color: transparent;"));
                tabPane.lookupAll(".content-area").forEach(n -> n.setStyle("-fx-background-color: transparent;"));
                
                // Apply initial styling to all tabs
                refreshTabStyling(tabPane);
                
                // Add selection listener
                tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                    refreshTabStyling(tabPane);
                });
                
                // Add listener for dynamically added tabs (like console subtabs)
                tabPane.getTabs().addListener((ListChangeListener<Tab>) c -> {
                    Platform.runLater(() -> refreshTabStyling(tabPane));
                });
                
            } catch (Exception e) {
                System.err.println("Error applying tab styling: " + e.getMessage());
            }
        });
    }
    
    private void refreshTabStyling(TabPane tabPane) {
        boolean isConsoleSub = tabPane.getStyleClass().contains("console-subtabs");
        boolean isSensorSub = tabPane.getStyleClass().contains("sensor-subtabs");
        String activeColor = isConsoleSub || isSensorSub ? SECONDARY_COLOR : PRIMARY_COLOR;
        String glowColor = isConsoleSub || isSensorSub ? "rgba(255, 0, 255, " : "rgba(0, 251, 255, ";

        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        
        for (Tab tab : tabPane.getTabs()) {
            // Find the tab node using a more robust approach
            Node tabNode = findTabNode(tabPane, tab);
            if (tabNode != null) {
                if (tab.equals(selectedTab)) {
                    // Selected tab style
                    tabNode.setStyle(
                        "-fx-background-color: " + glowColor + "0.25);" +
                        "-fx-background-radius: 8 8 0 0;" +
                        "-fx-border-color: " + activeColor + ";" +
                        "-fx-border-width: 2 2 0 2;" +
                        "-fx-border-radius: 8 8 0 0;" +
                        "-fx-text-fill: " + activeColor + ";" +
                        "-fx-font-family: 'Arial';" +
                        "-fx-font-size: 14px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-padding: 10 20 8 20;" +
                        "-fx-effect: dropshadow(gaussian, " + glowColor + "0.9), 15, 0.4, 0, 0);"
                    );
                } else {
                    // Unselected tab style
                    tabNode.setStyle(
                        "-fx-background-color: rgba(20, 30, 40, 0.8);" +
                        "-fx-background-radius: 8 8 0 0;" +
                        "-fx-border-color: " + activeColor + "44;" +
                        "-fx-border-width: 1 1 0 1;" +
                        "-fx-border-radius: 8 8 0 0;" +
                        "-fx-text-fill: " + activeColor + ";" +
                        "-fx-font-family: 'Arial';" +
                        "-fx-font-size: 14px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-padding: 10 20 8 20;" +
                        "-fx-effect: dropshadow(gaussian, " + glowColor + "0.3), 4, 0.5, 0, 0);"
                    );
                }
                
                // Add hover effect
                tabNode.setOnMouseEntered(e -> {
                    if (!tab.equals(selectedTab)) {
                        tabNode.setStyle(
                            "-fx-background-color: " + glowColor + "0.15);" +
                            "-fx-background-radius: 4 4 0 0;" +
                            "-fx-border-color: " + activeColor + "88;" +
                            "-fx-border-width: 1 1 0 1;" +
                            "-fx-border-radius: 4 4 0 0;" +
                            "-fx-font-family: 'Segoe UI';" +
                            "-fx-font-size: 14px;" +
                            "-fx-text-fill: " + activeColor + ";" +
                            "-fx-padding: 12 30 10 30;" +
                            "-fx-effect: dropshadow(gaussian, " + glowColor + "0.5), 6, 0.7, 0, 0);"
                        );
                    }
                });
                
                tabNode.setOnMouseExited(e -> {
                    if (!tab.equals(selectedTab)) {
                        tabNode.setStyle(
                            "-fx-background-color: rgba(20, 30, 40, 0.8);" +
                            "-fx-background-radius: 8 8 0 0;" +
                        "-fx-border-color: " + activeColor + "44;" +
                            "-fx-border-width: 1 1 0 1;" +
                            "-fx-border-radius: 8 8 0 0;" +
                        "-fx-text-fill: " + activeColor + ";" +
                            "-fx-font-family: 'Arial';" +
                            "-fx-font-size: 14px;" +
                            "-fx-font-weight: bold;" +
                            "-fx-padding: 10 20 8 20;" +
                        "-fx-effect: dropshadow(gaussian, " + glowColor + "0.3), 4, 0.5, 0, 0);"
                        );
                    }
                });
            }
        }
    }
    
    private Node findTabNode(TabPane tabPane, Tab targetTab) {
        // Try to find the tab node by looking up all tab containers
        for (Node node : tabPane.lookupAll(".tab")) {
            // A more robust way to find the tab node: check for the label or text content
            if (node.toString().contains("'" + targetTab.getText() + "'") || 
                node.toString().contains("\"" + targetTab.getText() + "\"")) {
                return node;
            }
        }
        return null;
    }

    private VBox createHeader() {
        VBox headerBox = new VBox(15);
        headerBox.setAlignment(Pos.CENTER);
        
        // Create JARVIS emblem and title at the top
        HBox header = new HBox(30);
        header.setAlignment(Pos.CENTER);
        
        // Create JARVIS emblem with neural network design
        StackPane emblem = createJarvisEmblem();
        
        // Create JARVIS title
        VBox titleBox = new VBox(10);
        titleBox.setAlignment(Pos.CENTER);
        
        Text jarvisText = new Text("JARVIS");
        jarvisText.setFont(Font.font("Arial Black", FontWeight.BOLD, 72));
        jarvisText.setFill(Color.web(PRIMARY_COLOR));
        jarvisText.setStyle("-fx-effect: dropshadow(gaussian, " + PRIMARY_COLOR + " 0.8, 20, 0, 0);");
        
        Text subtitleText = new Text("PERSONAL ASSISTANT");
        subtitleText.setFont(Font.font("Arial", FontWeight.NORMAL, 24));
        subtitleText.setFill(Color.web("#CCCCCC"));
        
        titleBox.getChildren().addAll(jarvisText, subtitleText);
        
        header.getChildren().addAll(emblem, titleBox);
        
        // Voice selection dropdown below
        HBox voiceContainer = new HBox(10);
        voiceContainer.setAlignment(Pos.CENTER);
        Label voiceLabel = new Label("Voice:");
        voiceLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        voiceLabel.setTextFill(Color.web("#CCCCCC"));
        
        voiceComboBox = new ComboBox<>();
        voiceComboBox.setPrefWidth(200);
        voiceComboBox.getStyleClass().add("jarvis-combo");
        // Enhanced Dropdown Styling
        voiceComboBox.setStyle(
            "-fx-background-color: rgba(26, 26, 26, 0.8);" +
            "-fx-border-color: " + SECONDARY_COLOR + ";" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 5;" +
            "-fx-text-fill: " + SECONDARY_COLOR + ";" +
            "-fx-font-weight: bold;" +
            "-fx-effect: dropshadow(gaussian, rgba(255, 0, 255, 0.8), 12, 0.5, 0, 0);");
        
        // Brain model selection dropdown
        VBox brainControlContainer = new VBox(10);
        brainControlContainer.setAlignment(Pos.CENTER);
        Label brainLabel = new Label("Brain Model:");
        brainLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        brainLabel.setTextFill(Color.web("#CCCCCC"));
        
        brainModelComboBox = new ComboBox<>();
        brainModelComboBox.setPrefWidth(200);
        brainModelComboBox.getStyleClass().add("jarvis-combo");
        brainModelComboBox.setStyle(
            "-fx-background-color: rgba(26, 26, 26, 0.8);" +
            "-fx-border-color: " + PRIMARY_COLOR + ";" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 5;" +
            "-fx-text-fill: " + PRIMARY_COLOR + ";" +
            "-fx-font-weight: bold;" +
            "-fx-effect: dropshadow(gaussian, rgba(0, 255, 255, 0.8), 12, 0.5, 0, 0);");
        
        brainControlContainer.getChildren().addAll(brainLabel, brainModelComboBox);
        
        // Voice image display
        voiceImageView = new ImageView();
        voiceImageView.setFitWidth(180);
        voiceImageView.setFitHeight(180);
        voiceImageView.setPreserveRatio(true);
        voiceImageView.setVisible(false); // Initially hidden

        // Create a container for voice controls
        VBox voiceControlContainer = new VBox(10);
        voiceControlContainer.setAlignment(Pos.CENTER);
        voiceControlContainer.getChildren().addAll(voiceLabel, voiceComboBox);
        
        // Combine voice controls and image
        HBox voiceSection = new HBox(30);
        voiceSection.setAlignment(Pos.CENTER);
        voiceSection.getChildren().addAll(brainControlContainer, voiceControlContainer, voiceImageView);
        
        // Load available voices after controller is initialized
        Platform.runLater(() -> {
            if (controller != null) {
                java.util.List<String> voices = controller.getPythonService().getAvailableVoices();
                System.err.println("DEBUG: Available voices found: " + voices);
                java.util.List<String> displayItems = new java.util.ArrayList<>();
                java.util.Map<String, String> cleanToFull = new java.util.HashMap<>();
                
                // Clean names: remove prefix before underscore
                for (String voice : voices) {
                    String cleanName = voice;
                    int underscoreIndex = voice.indexOf('_');
                    if (underscoreIndex != -1) {
                        cleanName = voice.substring(underscoreIndex + 1);
                    }
                    displayItems.add(cleanName);
                    cleanToFull.put(cleanName, voice);
                }
                
                System.err.println("DEBUG: Display items: " + displayItems);
                voiceComboBox.getItems().addAll(displayItems);
                voiceComboBox.getSelectionModel().select("nicole");
                
                // Handle voice selection with preview
                voiceComboBox.setOnAction(e -> {
                    String selected = voiceComboBox.getSelectionModel().getSelectedItem();
                    if (selected != null) {
                        String fullVoiceName = cleanToFull.get(selected);
                        if (fullVoiceName != null) {
                            controller.getPythonService().setSelectedVoice(fullVoiceName);
                            previewVoice(selected);
                            // Update voice image
                            updateVoiceImage(fullVoiceName);
                        }
                    }
                });

                // Load available brain models
                java.util.List<String> brainModels = controller.getPythonService().getAvailableBrainModels();
                brainModelComboBox.getItems().addAll(brainModels);
                brainModelComboBox.getSelectionModel().select(controller.getPythonService().getSelectedBrainModel());
                
                brainModelComboBox.setOnAction(e -> {
                    String selected = brainModelComboBox.getSelectionModel().getSelectedItem();
                    if (selected != null) {
                        controller.getPythonService().setSelectedBrainModel(selected);
                    }
                });
                
                // Set initial voice image for nicole
                String initialVoice = cleanToFull.get("nicole");
                System.err.println("DEBUG: Initial voice: " + initialVoice);
                updateVoiceImage(initialVoice);
                
                // Explicitly set the initial voice in PythonService
                controller.getPythonService().setSelectedVoice(initialVoice);
            }
        });
        
        headerBox.getChildren().addAll(header, voiceSection);
        return headerBox;
    }
    
    private void addVoiceCategory(java.util.List<String> displayItems, java.util.Map<String, String> cleanToFull, 
                               java.util.List<String> allVoices, String categoryLabel, String prefix, String... names) {
        displayItems.add("--- " + categoryLabel + " ---");
        for (String name : names) {
            String fullName = prefix + name;
            if (allVoices.contains(fullName)) {
                String cleanName = name.substring(name.indexOf('_') + 1);
                displayItems.add(cleanName);
                cleanToFull.put(cleanName, fullName);
            }
        }
    }

    private void updateVoiceImage(String voiceName) {
        try {
            // Use relative path from the application working directory
            String appDir = System.getProperty("user.dir");
            java.io.File imageFile = new java.io.File(appDir, "scripts/kokoro/hf_cache/voices/" + voiceName + ".png");
            System.err.println("DEBUG: Looking for image at: " + imageFile.getAbsolutePath());
            System.err.println("DEBUG: File exists: " + imageFile.exists());
            
            if (imageFile.exists()) {
                // Load the voice image
                javafx.scene.image.Image image = new javafx.scene.image.Image(imageFile.toURI().toString());
                voiceImageView.setImage(image);
                voiceImageView.setVisible(true);
                System.err.println("DEBUG: Loaded real image for " + voiceName);
            } else {
                // Create placeholder for missing images
                javafx.scene.image.Image placeholder = createPlaceholderImage(voiceName);
                voiceImageView.setImage(placeholder);
                voiceImageView.setVisible(true);
                System.err.println("DEBUG: Created placeholder for " + voiceName);
            }
        } catch (Exception e) {
            System.err.println("Error loading voice image: " + e.getMessage());
            // Show placeholder on error
            javafx.scene.image.Image placeholder = createPlaceholderImage(voiceName);
            voiceImageView.setImage(placeholder);
            voiceImageView.setVisible(true);
        }
    }
    
    private javafx.scene.image.Image createPlaceholderImage(String voiceName) {
        // Create a simple placeholder with the voice name
        javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(180, 180);
        javafx.scene.canvas.GraphicsContext gc = canvas.getGraphicsContext2D();
        
        // Background
        gc.setFill(javafx.scene.paint.Color.rgb(26, 26, 26));
        gc.fillRect(0, 0, 180, 180);
        
        // Border
        gc.setStroke(javafx.scene.paint.Color.web("#FF00FF"));
        gc.setLineWidth(2);
        gc.strokeRect(1, 1, 178, 178);
        
        // Text (first 3 letters of voice name)
        String shortName = voiceName.replace("af_", "").replace("am_", "").replace("bf_", "").replace("bm_", "")
                              .replace("ef_", "").replace("em_", "").replace("ff_", "").replace("hf_", "")
                              .replace("hm_", "").replace("if_", "").replace("im_", "").replace("jf_", "")
                              .replace("jm_", "").replace("pf_", "").replace("pm_", "").replace("zf_", "")
                              .replace("zm_", "");
        String displayText = shortName.length() >= 3 ? shortName.substring(0, 3).toUpperCase() : shortName.toUpperCase();
        
        gc.setFill(javafx.scene.paint.Color.web("#FF00FF"));
        gc.setFont(javafx.scene.text.Font.font("Arial", javafx.scene.text.FontWeight.BOLD, 48));
        javafx.scene.text.Text text = new javafx.scene.text.Text(displayText);
        double textWidth = text.getLayoutBounds().getWidth();
        double textHeight = text.getLayoutBounds().getHeight();
        gc.fillText(displayText, (180 - textWidth) / 2, (180 + textHeight) / 2 - 5);
        
        return canvas.snapshot(null, null);
    }

    private void previewVoice(String voice) {
        if (controller != null) {
            // Extract clean name: remove prefix before underscore and the underscore itself
            String cleanName = voice;
            int underscoreIndex = voice.indexOf('_');
            if (underscoreIndex != -1) {
                cleanName = voice.substring(underscoreIndex + 1);
            }
            String previewText = cleanName + ". Nice to meet you.";
            controller.getPythonService().generateSpeech(previewText)
                .thenAccept(audioFile -> {
                    if (audioFile != null && !audioFile.isBlank()) {
                        controller.playAudioFile(audioFile);
                    }
                });
        }
    }

    private StackPane createJarvisEmblem() {
        StackPane emblem = new StackPane();
        emblem.setPrefSize(150, 150);
        
        // Create crystalline shield background
        Polygon shield = new Polygon();
        shield.getPoints().addAll(
            75.0, 10.0,   // Top point
            140.0, 40.0,  // Right upper
            140.0, 110.0, // Right lower
            75.0, 140.0,  // Bottom point
            10.0, 110.0,  // Left lower
            10.0, 40.0    // Left upper
        );
        shield.setFill(Color.TRANSPARENT);
        shield.setStroke(Color.web(PRIMARY_COLOR));
        shield.setStrokeWidth(3);
        shield.setStyle("-fx-effect: dropshadow(gaussian, " + PRIMARY_COLOR + " 0.6, 15, 0, 0);");
        
        // Create neural network brain visualization
        Pane brain = createNeuralBrain();
        
        // Create geometric core (octahedron representation)
        Polygon octahedron = createOctahedron();
        
        emblem.getChildren().addAll(shield, brain, octahedron);
        return emblem;
    }

    private Pane createNeuralBrain() {
        Pane brain = new Pane();
        brain.setPrefSize(100, 100);
        
        // Create neural network connections
        for (int i = 0; i < 8; i++) {
            for (int j = i + 1; j < 8; j++) {
                if (random.nextDouble() > 0.6) {
                    double x1 = 20 + random.nextDouble() * 60;
                    double y1 = 20 + random.nextDouble() * 60;
                    double x2 = 20 + random.nextDouble() * 60;
                    double y2 = 20 + random.nextDouble() * 60;
                    
                    javafx.scene.shape.Line connection = new javafx.scene.shape.Line(x1, y1, x2, y2);
                    connection.setStroke(Color.web(PRIMARY_COLOR, 0.3));
                    connection.setStrokeWidth(1);
                    brain.getChildren().add(connection);
                }
            }
        }
        
        // Add neural nodes
        for (int i = 0; i < 12; i++) {
            double x = 15 + random.nextDouble() * 70;
            double y = 15 + random.nextDouble() * 70;
            
            Circle node = new Circle(x, y, 3);
            node.setFill(Color.web(PRIMARY_COLOR));
            node.setStyle("-fx-effect: dropshadow(gaussian, " + PRIMARY_COLOR + " 0.8, 5, 0, 0);");
            brain.getChildren().add(node);
        }
        
        return brain;
    }

    private Polygon createOctahedron() {
        Polygon octahedron = new Polygon();
        octahedron.getPoints().addAll(
            75.0, 55.0,  // Top
            95.0, 75.0,  // Right
            75.0, 95.0,  // Bottom
            55.0, 75.0   // Left
        );
        octahedron.setFill(Color.web(SECONDARY_COLOR, 0.3));
        octahedron.setStroke(Color.web(SECONDARY_COLOR));
        octahedron.setStrokeWidth(2);
        return octahedron;
    }

    private HBox createContentArea() {
        HBox content = new HBox(40);
        content.setAlignment(Pos.TOP_CENTER);
        
        // Left side - Listening visualizer
        VBox leftSection = createListeningSection();
        
        // Right side - Response visualizer
        VBox rightSection = createResponseSection();
        
        content.getChildren().addAll(leftSection, rightSection);
        return content;
    }

    private TabPane createSensorsArea() {
        TabPane sensorsSubTabs = new TabPane();
        sensorsSubTabs.getStyleClass().add("sensor-subtabs");
        sensorsSubTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab spatialTab = new Tab("Spatial");
        spatialTab.setContent(createSensorFeedView("OCULAR ANALYSIS", "http://127.0.0.1:8882/video_feed"));
        
        Tab movementTab = new Tab("Movement");
        movementTab.setContent(createSensorFeedView("ACTUATOR TELEMETRY", "http://127.0.0.1:8084/video_feed"));

        sensorsSubTabs.getTabs().addAll(spatialTab, movementTab);
        applyTabStyling(sensorsSubTabs);
        return sensorsSubTabs;
    }

    private VBox createSensorFeedView(String title, String streamUrl) {
        VBox container = new VBox(15);
        container.setAlignment(Pos.CENTER);
        container.setPadding(new Insets(20));

        Label name = new Label(title);
        name.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        name.setTextFill(Color.web(PRIMARY_COLOR));
        name.setStyle("-fx-effect: dropshadow(gaussian, " + PRIMARY_COLOR + ", 10, 0, 0, 0);");

        ImageView feedView = new ImageView();
        feedView.setPreserveRatio(true);

        StackPane feedContainer = new StackPane(feedView);
        VBox.setVgrow(feedContainer, Priority.ALWAYS);
        // Bind ImageView size to its parent container's size
        feedView.fitWidthProperty().bind(feedContainer.widthProperty());
        feedView.fitHeightProperty().bind(feedContainer.heightProperty());
        // Prevent layout feedback loop where the image content pushes the container size,
        // which would otherwise cause an infinite "zooming" effect.
        feedContainer.setMinSize(0, 0);
        feedContainer.setPrefSize(0, 0);
        feedContainer.setStyle("-fx-background-color: #000; -fx-border-color: #00FBFF33; -fx-border-width: 2; -fx-background-radius: 5;");
        
        container.getChildren().addAll(name, feedContainer);
        startStreamThread(feedView, streamUrl);
        return container;
    }

    private void startStreamThread(ImageView view, String urlString) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlString).openConnection();
                    conn.setReadTimeout(10000);
                    try (java.io.BufferedInputStream is = new java.io.BufferedInputStream(conn.getInputStream())) {
                        while(true) {
                            int b;
                            while ((b = is.read()) != -1) {
                                if (b == 0xFF && is.read() == 0xD8) break;
                            }
                            if (b == -1) break;
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            baos.write(0xFF); baos.write(0xD8);
                            int last = 0;
                            while ((b = is.read()) != -1) {
                                baos.write(b);
                                if (last == 0xFF && b == 0xD9) break;
                                last = b;
                            }
                            byte[] jpeg = baos.toByteArray();
                            Platform.runLater(() -> view.setImage(new Image(new java.io.ByteArrayInputStream(jpeg))));
                        }
                    }
                } catch (Exception e) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }, "Stream-" + urlString);
        t.setDaemon(true);
        t.start();
    }

    private HBox createBodyMapArea() {
        HBox content = new HBox(15);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(10));

        bodyMapPane = new BodyMapPane();
        bodyMapPane.setOnSelect(module -> {
            if (statusLabel != null) {
                statusLabel.setText("System Status: Selected " + module.getDisplayName());
            }
        });
        bodyMapPane.setOnHover((module, isHovered) -> {
            moduleRegistryPane.setHoverHighlight(module, isHovered);
        });

        moduleRegistryPane = new ModuleRegistryPane();
        content.getChildren().addAll(bodyMapPane, moduleRegistryPane);
        return content;
    }

    private VBox createListeningSection() {
        VBox leftSection = new VBox(20);
        leftSection.setAlignment(Pos.TOP_CENTER);
        
        // Create ear indicator and label container
        HBox indicatorContainer = new HBox(15);
        indicatorContainer.setAlignment(Pos.CENTER);
        
        // Ear bulb indicator
        earIndicator = createBulbIndicator("EAR", true);
        
        // Microphone icon and label
        listeningLabel = new Label("🎤 LISTENING... ANALYZING");
        listeningLabel.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        listeningLabel.setTextFill(Color.web(PRIMARY_COLOR));
        
        indicatorContainer.getChildren().addAll(earIndicator, listeningLabel);
        
        // Ear input display area
        VBox earInputContainer = new VBox(10);
        earInputContainer.setAlignment(Pos.CENTER);
        earInputContainer.setPadding(new Insets(10));
        earInputContainer.setPrefHeight(200);
        earInputContainer.setMaxHeight(Double.MAX_VALUE);
        
        Label earInputLabel = new Label("🎤 EAR INPUT:");
        earInputLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        earInputLabel.setTextFill(Color.web("#CCCCCC"));
        
        earInputDisplay = new Label("Waiting for voice input...");
        earInputDisplay.setFont(Font.font("Courier New", FontWeight.NORMAL, 16));
        earInputDisplay.setTextFill(Color.web(SECONDARY_COLOR));
        earInputDisplay.setWrapText(true);
        earInputDisplay.setMaxWidth(400);
        earInputDisplay.setAlignment(Pos.TOP_LEFT);
        earInputDisplay.setStyle("-fx-background-color: transparent; -fx-padding: 10;");

        ScrollPane scrollPane = new ScrollPane(earInputDisplay);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(150);
        scrollPane.setStyle("-fx-background: rgba(0,0,0,0.7); -fx-background-color: transparent; -fx-background-radius: 8; -fx-border-color: #00FBFF33;");
        
        earInputContainer.getChildren().addAll(earInputLabel, scrollPane);
        
        // Audio visualizer
        leftVisualizer = new AudioVisualizer(Color.web(PRIMARY_COLOR), Color.web(SECONDARY_COLOR));
        leftVisualizer.setPrefSize(400, 150);
        
        leftSection.getChildren().addAll(indicatorContainer, earInputContainer, leftVisualizer);
        return leftSection;
    }

    private VBox createVoiceSelectionSection() {
        VBox centerSection = new VBox(20);
        centerSection.setAlignment(Pos.CENTER);
        centerSection.setPadding(new Insets(20));
        
        // Voice selection dropdown
        HBox voiceContainer = new HBox(10);
        voiceContainer.setAlignment(Pos.CENTER);
        Label voiceLabel = new Label("Voice:");
        voiceLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        voiceLabel.setTextFill(Color.web("#CCCCCC"));
        
        voiceComboBox = new ComboBox<>();
        voiceComboBox.setPrefWidth(200);
        voiceComboBox.setStyle("-fx-background-color: #1A1A1A; -fx-text-fill: #FF00FF; -fx-border-color: #FF00FF; -fx-border-radius: 4;");
        
        // Load available voices after controller is initialized
        Platform.runLater(() -> {
            if (controller != null) {
                java.util.List<String> voices = controller.getPythonService().getAvailableVoices();
                voiceComboBox.getItems().addAll(voices);
                voiceComboBox.getSelectionModel().select("af_heart");
                
                // Handle voice selection
                voiceComboBox.setOnAction(e -> {
                    String selected = voiceComboBox.getSelectionModel().getSelectedItem();
                    if (selected != null) {
                        controller.getPythonService().setSelectedVoice(selected);
                    }
                });
            }
        });
        
        voiceContainer.getChildren().addAll(voiceLabel, voiceComboBox);
        
        // Add a title
        Label titleLabel = new Label("VOICE CONTROL");
        titleLabel.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        titleLabel.setTextFill(Color.web("#CCCCCC"));
        
        centerSection.getChildren().addAll(titleLabel, voiceContainer);
        return centerSection;
    }

    private VBox createResponseSection() {
        VBox rightSection = new VBox(20);
        rightSection.setAlignment(Pos.TOP_CENTER);
        
        // Create mouth indicator and label container
        HBox indicatorContainer = new HBox(15);
        indicatorContainer.setAlignment(Pos.CENTER);
        
        // Mouth bulb indicator
        mouthIndicator = createBulbIndicator("MOUTH", false);
        
        // Speaker icon and label
        responseLabel = new Label("🔊 PREPARING RESPONSE");
        responseLabel.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        responseLabel.setTextFill(Color.web(PRIMARY_COLOR));
        
        indicatorContainer.getChildren().addAll(mouthIndicator, responseLabel);
        
        // Mouth response display area
        VBox mouthResponseContainer = new VBox(10);
        mouthResponseContainer.setAlignment(Pos.CENTER);
        mouthResponseContainer.setPadding(new Insets(10));
        mouthResponseContainer.setPrefWidth(600);
        mouthResponseContainer.setMaxWidth(Double.MAX_VALUE);
        mouthResponseContainer.setPrefHeight(200); // Allow height to expand
        mouthResponseContainer.setMaxHeight(Double.MAX_VALUE); // Remove height constraint
        
        Label mouthResponseLabel = new Label("🔊 MOUTH OUTPUT:");
        mouthResponseLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        mouthResponseLabel.setTextFill(Color.web("#CCCCCC"));
        
        mouthResponseDisplay = new Label("🔊 Waiting for response...");
        mouthResponseDisplay.setFont(Font.font("Courier New", FontWeight.NORMAL, 16));
        mouthResponseDisplay.setTextFill(Color.web(SECONDARY_COLOR));
        mouthResponseDisplay.setWrapText(true);
        mouthResponseDisplay.setMaxWidth(Double.MAX_VALUE);
        mouthResponseDisplay.setPrefWidth(600);
        mouthResponseDisplay.setMaxHeight(Double.MAX_VALUE);
        mouthResponseDisplay.setAlignment(Pos.TOP_LEFT);
        mouthResponseDisplay.setStyle("-fx-background-color: transparent; -fx-padding: 10;");

        ScrollPane scrollPane = new ScrollPane(mouthResponseDisplay);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(150);
        scrollPane.setStyle("-fx-background: rgba(0,0,0,0.7); -fx-background-color: transparent; -fx-background-radius: 8; -fx-border-color: #00FBFF33;");
        
        mouthResponseContainer.getChildren().addAll(mouthResponseLabel, scrollPane);
        
        // Audio visualizer
        rightVisualizer = new AudioVisualizer(Color.web(PRIMARY_COLOR), Color.web(PRIMARY_COLOR));
        rightVisualizer.setPrefSize(400, 150);
        
        rightSection.getChildren().addAll(indicatorContainer, mouthResponseContainer, rightVisualizer);
        return rightSection;
    }

    private VBox createStatusBar() {
        VBox statusBar = new VBox(5);
        statusBar.setAlignment(Pos.CENTER);
        
        // System status bar
        HBox statusBarContainer = new HBox(20);
        statusBarContainer.setAlignment(Pos.CENTER);
        statusBarContainer.setPadding(new Insets(8, 15, 8, 15)); // Reduced height padding
        statusBarContainer.setBackground(createStatusBackground());
        
        statusLabel = new Label("System Status: Initializing JARVIS systems...");
        statusLabel.setFont(Font.font("Arial", FontWeight.NORMAL, 13)); // Smaller font for status
        statusLabel.setTextFill(Color.web("#00FF00"));
        
        statusBarContainer.getChildren().add(statusLabel);
        statusBar.getChildren().add(statusBarContainer);
        
        return statusBar;
    }
    
    /**
     * Get controller for external access
     */
    public JarvisController getController() {
        return controller;
    }
    
    /**
     * Create a bulb indicator for ear/mouth status
     */
    private StackPane createBulbIndicator(String label, boolean isEar) {
        Circle bulb = new Circle(15);
        bulb.setFill(Color.web("#333333"));
        bulb.setStroke(Color.web(PRIMARY_COLOR));
        bulb.setStrokeWidth(2);
        
        // Store bulb reference for updates
        if (isEar) {
            earBulb = bulb;
        } else {
            mouthBulb = bulb;
        }
        
        // Add glow effect when active
        bulb.setStyle("-fx-effect: dropshadow(gaussian, " + PRIMARY_COLOR + " 0.8, 15, 0, 0);");
        
        // Create label
        Label indicatorLabel = new Label(label);
        indicatorLabel.setFont(Font.font("Arial", FontWeight.BOLD, 10));
        indicatorLabel.setTextFill(Color.web("#CCCCCC"));
        
        // Create container for bulb and label
        StackPane bulbContainer = new StackPane();
        bulbContainer.getChildren().addAll(bulb, indicatorLabel);
        StackPane.setAlignment(indicatorLabel, Pos.BOTTOM_CENTER);
        StackPane.setMargin(indicatorLabel, new Insets(0, 0, -20, 0));
        
        return bulbContainer;
    }
    
    /**
     * Update ear indicator status
     */
    public void updateEarIndicator(boolean active) {
        Platform.runLater(() -> {
            if (earBulb != null) {
                if (active) {
                    earBulb.setFill(Color.web("#00FF00"));
                    earBulb.setStyle("-fx-effect: dropshadow(gaussian, rgba(0, 255, 0, 1.0), 20, 0.5, 0, 0);");
                } else {
                    earBulb.setFill(Color.web("#333333"));
                    earBulb.setStyle("-fx-effect: dropshadow(gaussian, rgba(0, 251, 255, 0.3), 10, 0.5, 0, 0);");
                }
            }
        });
    }
    
    /**
     * Update mouth indicator status
     */
    public void updateMouthIndicator(boolean active) {
        Platform.runLater(() -> {
            if (mouthBulb != null) {
                if (active) {
                    mouthBulb.setFill(Color.web("#00FF00"));
                    mouthBulb.setStyle("-fx-effect: dropshadow(gaussian, rgba(0, 255, 0, 1.0), 20, 0.5, 0, 0);");
                } else {
                    mouthBulb.setFill(Color.web("#333333"));
                    mouthBulb.setStyle("-fx-effect: dropshadow(gaussian, rgba(0, 251, 255, 0.3), 10, 0.5, 0, 0);");
                }
            }
        });
    }

    public void addConsoleTab(String title) {
        Platform.runLater(() -> {
            if (consoleOutputMap.containsKey(title)) return;
            
            TextArea textArea = new TextArea();
            textArea.setEditable(false);
            textArea.setStyle("-fx-control-inner-background: #000000; -fx-text-fill: " + PRIMARY_COLOR + "; -fx-font-family: 'Courier New'; -fx-font-size: 12px;");
            
            Tab subTab = new Tab(title);
            subTab.setContent(textArea);
            consoleSubTabPane.getTabs().add(subTab);
            // refreshTabStyling is now handled by the ListChangeListener in applyTabStyling
            consoleOutputMap.put(title, textArea);
        });
    }

    public void selectConsoleTab(String title) {
        Platform.runLater(() -> {
            for (javafx.scene.control.Tab tab : consoleSubTabPane.getTabs()) {
                if (tab.getText().equals(title)) {
                    consoleSubTabPane.getSelectionModel().select(tab);
                    break;
                }
            }
        });
    }

    public void appendToConsole(String title, String text) {
        Platform.runLater(() -> {
            TextArea area = consoleOutputMap.get(title);
            if (area != null) {
                area.appendText(text + "\n");
                
                // Memory Management: Keep only the last ~50,000 characters (approx 500-1000 lines)
                if (area.getLength() > 50000) {
                    area.replaceText(0, 10000, "--- LOG TRUNCATED FOR PERFORMANCE ---\n");
                }
                
                area.selectPositionCaret(area.getLength());
                area.deselect();
            }
        });
    }

    private Background createMetallicBackground() {
        LinearGradient gradient = new LinearGradient(
            0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.web(BACKGROUND_COLOR)),
            new Stop(0.5, Color.web(METALLIC_COLOR)),
            new Stop(1, Color.web(BACKGROUND_COLOR))
        );
        return new Background(new BackgroundFill(gradient, CornerRadii.EMPTY, Insets.EMPTY));
    }

    private Background createStatusBackground() {
        LinearGradient gradient = new LinearGradient(
            0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#0A0A0A", 0.8)),
            new Stop(0.5, Color.web("#1A1A1A", 0.9)),
            new Stop(1, Color.web("#0A0A0A", 0.8))
        );
        return new Background(new BackgroundFill(gradient, new CornerRadii(10), Insets.EMPTY));
    }

    private void applyStyles(Scene scene) {
        // Add custom CSS for additional styling
        String css = """
            .tab-pane, .tab-pane .tab-header-area, .tab-pane .tab-header-background, .tab-pane .headers-region, .tab-pane .content-area, .tab-pane .tab-content-area {
                -fx-background-color: transparent;
            }
            
            /* General Tab Styling (Primary) */
            .tab-pane .tab-label { -fx-text-fill: #00FFFF; }
            .tab-pane .tab-label .text { -fx-fill: #00FFFF; }
            .tab-pane .tab:selected .tab-label { -fx-effect: dropshadow(gaussian, rgba(0, 251, 255, 0.8), 10, 0.5, 0, 0) !important; }
            .tab-pane .tab:hover .tab-label { -fx-effect: dropshadow(gaussian, rgba(0, 251, 255, 0.6), 8, 0.4, 0, 0); }

            /* Subtab Overrides (Secondary/Magenta) */
            .console-subtabs .tab-label, .sensor-subtabs .tab-label { -fx-text-fill: #FF00FF !important; }
            .console-subtabs .tab-label .text, .sensor-subtabs .tab-label .text { -fx-fill: #FF00FF !important; }
            .console-subtabs .tab:selected .tab-label, .sensor-subtabs .tab:selected .tab-label { -fx-effect: dropshadow(gaussian, rgba(255, 0, 255, 0.8), 10, 0.5, 0, 0) !important; }
            .console-subtabs .tab:hover .tab-label, .sensor-subtabs .tab:hover .tab-label { -fx-effect: dropshadow(gaussian, rgba(255, 0, 255, 0.6), 8, 0.4, 0, 0) !important; }

            .tab-pane:focused .tab-header-area .headers-region .tab:selected .focus-indicator { -fx-border-color: transparent; -fx-focus-color: transparent; }
            .label:not(.tab-label) {
                -fx-font-family: 'Arial';
                -fx-text-fill: #CCCCCC;
            }
            
            .status-active {
                -fx-text-fill: #00FF00;
                -fx-font-weight: bold;
            }
            
            .status-inactive {
                -fx-text-fill: #FF4444;
                -fx-font-weight: bold;
            }
            
            .combo-box .list-cell {
                -fx-background-color: #1A1A1A;
                -fx-text-fill: #00FFFF;
                -fx-padding: 5 10;
                -fx-effect: dropshadow(gaussian, rgba(0, 251, 255, 0.5), 5, 0.3, 0, 0);
            }
            .combo-box .list-cell:hover {
                -fx-background-color: rgba(0, 251, 255, 0.2);
            }
            .combo-box .list-view {
                -fx-background-color: #1A1A1A;
                -fx-border-color: #00FFFF;
                -fx-border-width: 1;
            }
            .combo-box > .list-cell {
                -fx-background-color: transparent;
                -fx-text-fill: #00FFFF;
                -fx-effect: dropshadow(gaussian, rgba(0, 251, 255, 0.6), 8, 0.4, 0, 0);
            }
            """;
        
        scene.getStylesheets().add("data:text/css," + css.replace("\n", " ").replace("\r", ""));
    }

    private void startAnimations() {
        // Animate audio visualizers
        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(100), e -> {
            leftVisualizer.updateWaveform();
            rightVisualizer.updateWaveform();
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
        
        // Animate neural network
        Timeline neuralTimeline = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            // Add pulsing effect to neural network nodes
            animateNeuralNetwork();
        }));
        neuralTimeline.setCycleCount(Animation.INDEFINITE);
        neuralTimeline.play();
    }

    private void animateNeuralNetwork() {
        // Visual pulsing effects for the emblem can be added here.
        // Textual system status is now handled by live health monitor updates.
    }

    public static void main(String[] args) {
        launch(args);
    }
}
