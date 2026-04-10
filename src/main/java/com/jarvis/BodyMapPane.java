package com.jarvis;

import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Point2D;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Glow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.paint.*;
import javafx.scene.shape.SVGPath;
import javafx.scene.control.Label;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

public class BodyMapPane extends Pane {
    private static final String CYAN_GLOW = "#00FBFF";
    private static final String BACKGROUND_IMAGE_RESOURCE = "/iron-man.jpg";
    private static final String POSITIONS_FILE = "module_positions.properties";

    private final Map<JarvisModule, List<SVGPath>> parts = new EnumMap<>(JarvisModule.class);
    private final Map<JarvisModule, Label> labels = new EnumMap<>(JarvisModule.class);
    private final Properties positions = new Properties();
    private Consumer<JarvisModule> onSelect;
    private BiConsumer<JarvisModule, Boolean> onHover;

    private double dragAnchorX;
    private double dragAnchorY;

    public BodyMapPane() {
        setPrefSize(400, 600);
        setPadding(new Insets(20));
        setStyle("-fx-background-color: rgba(10, 15, 20, 0.8); -fx-background-radius: 15; -fx-border-color: #00FBFF33; -fx-border-width: 2;");

        loadPositions();

        // --- LAYER 1: HIGH-DEFINITION ARTWORK ---
        // Use resource loading to allow the application to find the image relative to the project structure
        java.net.URL imageUrl = getClass().getResource(BACKGROUND_IMAGE_RESOURCE);
        Image ironManImage = (imageUrl != null) ? new Image(imageUrl.toExternalForm(), true) : null;
        
        ImageView armorView = new ImageView(ironManImage);
        armorView.setFitWidth(400);
        armorView.setFitHeight(600);
        armorView.setPreserveRatio(true);
        
        // Center the image in the pane
        armorView.layoutXProperty().bind(widthProperty().subtract(armorView.fitWidthProperty()).divide(2));
        
        // Apply a subtle blue tint and glow to the artwork to match the UI
        DropShadow imageGlow = new DropShadow(20, Color.web(CYAN_GLOW, 0.3));
        armorView.setEffect(imageGlow);

        // --- LAYER 2: INTERACTIVE MODULE NODES ---
        // These are the "Selectable" parts linked to your Python services
        
        // BRAIN (Cortex) - Length reduced
        SVGPath brainNode = createNode(JarvisModule.CORTEX_BRAIN, "M190 45 Q200 35 210 45 L208 65 Q200 70 192 65 Z", 0, 0);
            
        // MEMORY (Long Term Memory - Vector Search) - Custom storage drive shape
        SVGPath memoryNode = createNode(JarvisModule.LONG_TERM_MEMORY, "M192 75 L208 75 L208 89 L192 89 Z M194 78 L206 78 L206 80 L194 80 Z M194 82 L206 82 L206 84 L194 84 Z M194 86 L206 86 L206 88 L194 88 Z", 0, 0);

        // EYES (Vision - Linked to jarvis_eye.py)
        SVGPath eyeNode = createNode(JarvisModule.OCULAR_EYES, "M178 140 L194 146 L194 150 L178 148 Z M206 146 L222 140 L222 148 L206 150 Z", 0, 0);

        // EARS (Audio - Linked to jarvis_ear.py)
        SVGPath earNode = createNode(JarvisModule.AURAL_EARS, "M168 135 L175 145 L175 160 L168 170 Z M232 135 L225 145 L225 160 L232 170 Z", 0, 0);

        // MOUTH (Speech - Linked to jarvis_mouth.py)
        SVGPath mouthNode = createNode(JarvisModule.VOCAL_MOUTH, "M190 165 L210 165 L210 169 L190 169 Z", 0, 0);

        // ARC REACTOR (The Core/Heart - System Status)
        SVGPath arcReactor = createNode(JarvisModule.CORE_REACTOR, "M185 310 L215 310 L200 340 Z", 0, 0);

        // HANDS (Repulsors) - Re-placed on the actual palm locations
        SVGPath leftHand = createNode(JarvisModule.REPULSORS, "M85 320 m-15 0 a15 15 0 1 0 30 0 a15 15 0 1 0 -30 0", 0, 0);
        SVGPath rightHand = createNode(JarvisModule.REPULSORS, "M315 320 m-15 0 a15 15 0 1 0 30 0 a15 15 0 1 0 -30 0", 0, 0);

        // Use a Pane with fixed bounds to stabilize the scaling pivot
        Pane bodyGroup = new Pane();
        bodyGroup.setPrefSize(400, 600);
        bodyGroup.getChildren().addAll(armorView, brainNode, memoryNode, eyeNode, earNode, mouthNode, arcReactor, leftHand, rightHand);
        labels.values().forEach(label -> bodyGroup.getChildren().add(label));
        
        // Scale down the body map to fit better within the UI frame (85% of original size)
        bodyGroup.setScaleX(0.85);
        bodyGroup.setScaleY(0.85);
        
        // Lift the assembly higher in the frame
        bodyGroup.setTranslateY(-35);

        getChildren().add(bodyGroup);
        
        // Add a breathing animation to the Arc Reactor
        applyPulseAnimation(arcReactor);
    }

    private SVGPath createPath(String data, String fill, String stroke, double width) {
        SVGPath p = new SVGPath();
        p.setContent(data);
        p.setFill(Color.web(fill));
        p.setStroke(Color.web(stroke));
        p.setStrokeWidth(width);
        return p;
    }

    private void loadPositions() {
        File file = new File(POSITIONS_FILE);
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                positions.load(in);
            } catch (IOException e) {
                System.err.println("Failed to load module positions: " + e.getMessage());
            }
        }
    }

    private void savePositions() {
        try (FileOutputStream out = new FileOutputStream(POSITIONS_FILE)) {
            positions.store(out, "Jarvis Module Positions");
        } catch (IOException e) {
            System.err.println("Failed to save module positions: " + e.getMessage());
        }
    }

    private SVGPath createNode(JarvisModule module, String data, double tx, double ty) {
        SVGPath node = new SVGPath();
        node.setContent(data);
        node.setFill(Color.web(CYAN_GLOW, 0.15));
        node.setStroke(Color.web(CYAN_GLOW, 0.8));
        node.setStrokeWidth(2);

        // Apply saved position, or use default if not found
        double savedTx = Double.parseDouble(positions.getProperty(module.name() + ".tx", String.valueOf(tx)));
        double savedTy = Double.parseDouble(positions.getProperty(module.name() + ".ty", String.valueOf(ty)));
        node.setTranslateX(savedTx);
        node.setTranslateY(savedTy);
        
        DropShadow glow = new DropShadow(15, Color.web(CYAN_GLOW));
        node.setEffect(glow);

        Label hoverLabel = new Label(module.getDisplayName());
        hoverLabel.setFont(Font.font("Arial", FontWeight.BOLD, 11));
        hoverLabel.setTextFill(Color.web(CYAN_GLOW));
        hoverLabel.setStyle("-fx-background-color: rgba(0,0,0,0.75); -fx-background-radius: 4; -fx-padding: 2 6; -fx-border-color: " + CYAN_GLOW + "; -fx-border-radius: 4;");
        hoverLabel.setVisible(false);
        hoverLabel.setMouseTransparent(true); // Prevents label from flickering when mouse is over it
        labels.put(module, hoverLabel); // Store for later addition to ensure top Z-order

        node.setOnMouseEntered(e -> {
            node.setFill(Color.web(CYAN_GLOW, 0.4));
            glow.setRadius(25);
            Point2D p = node.localToParent(e.getX(), e.getY());
            hoverLabel.setLayoutX(p.getX() + 15);
            hoverLabel.setLayoutY(p.getY() + 15);
            hoverLabel.setVisible(true);
            if (onHover != null) onHover.accept(module, true);
        });
        
        node.setOnMouseMoved(e -> {
            Point2D p = node.localToParent(e.getX(), e.getY());
            hoverLabel.setLayoutX(p.getX() + 15);
            hoverLabel.setLayoutY(p.getY() + 15);
        });
        
        node.setOnMouseExited(e -> {
            node.setFill(Color.web(CYAN_GLOW, 0.15));
            glow.setRadius(15);
            hoverLabel.setVisible(false);
            if (onHover != null) onHover.accept(module, false);
        });

        // Drag and Drop Logic
        node.setOnMousePressed(e -> {
            dragAnchorX = e.getSceneX() - node.getTranslateX();
            dragAnchorY = e.getSceneY() - node.getTranslateY();
            node.setCursor(javafx.scene.Cursor.CLOSED_HAND);
        });

        node.setOnMouseDragged(e -> {
            node.setTranslateX(e.getSceneX() - dragAnchorX);
            node.setTranslateY(e.getSceneY() - dragAnchorY);
        });

        node.setOnMouseReleased(e -> {
            node.setCursor(javafx.scene.Cursor.DEFAULT);
            positions.setProperty(module.name() + ".tx", String.valueOf(node.getTranslateX()));
            positions.setProperty(module.name() + ".ty", String.valueOf(node.getTranslateY()));
            savePositions();
            System.out.println("LOG: Module " + module.getDisplayName() + " saved at TranslateX: " + node.getTranslateX() + ", TranslateY: " + node.getTranslateY());
        });

        node.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && onSelect != null) {
                onSelect.accept(module);
            }
        });

        parts.computeIfAbsent(module, k -> new ArrayList<>()).add(node);
        return node;
    }

    private void applyPulseAnimation(SVGPath node) {
        Timeline pulse = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(node.opacityProperty(), 0.6)),
            new KeyFrame(Duration.seconds(1.5), new KeyValue(node.opacityProperty(), 1.0)),
            new KeyFrame(Duration.seconds(3), new KeyValue(node.opacityProperty(), 0.6))
        );
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();
    }

    public void setOnSelect(Consumer<JarvisModule> onSelect) {
        this.onSelect = onSelect;
    }

    public void setOnHover(BiConsumer<JarvisModule, Boolean> onHover) {
        this.onHover = onHover;
    }

    public void update(ServiceStatus status) {
        List<SVGPath> partNodes = parts.get(status.module());
        if (partNodes == null) return;

        Color stroke;
        Color fill;
        double glowRadius;

        switch (status.state()) {
            case READY -> {
                stroke = Color.web("#00FF88");
                fill = Color.web("#00FF88", 0.25);
                glowRadius = 20;
            }
            case DEGRADED -> {
                stroke = Color.web("#FFB000");
                fill = Color.web("#FFB000", 0.25);
                glowRadius = 22;
            }
            case OFFLINE -> {
                stroke = Color.web("#FF4444");
                fill = Color.web("#FF4444", 0.2);
                glowRadius = 18;
            }
            case BUSY -> {
                stroke = Color.web("#FF00FF"); // Neural/Secondary color for processing
                fill = Color.web("#FF00FF", 0.35);
                glowRadius = 24;
            }
            case STARTING -> {
                stroke = Color.web("#66AAFF");
                fill = Color.web("#66AAFF", 0.25);
                glowRadius = 20;
            }
            default -> {
                stroke = Color.web(CYAN_GLOW);
                fill = Color.web(CYAN_GLOW, 0.15);
                glowRadius = 15;
            }
        }

        for (SVGPath node : partNodes) {
            node.setStroke(stroke);
            node.setFill(fill);
            node.setEffect(new DropShadow(glowRadius, stroke));
        }
    }
}
