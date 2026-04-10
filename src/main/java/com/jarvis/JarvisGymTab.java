package com.jarvis;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.application.Platform;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JARVIS GYM - Professional JavaFX Training Interface
 * Optimized for deep-neural refinement of the Llama-3.2 architecture.
 */
public class JarvisGymTab extends VBox {

    private final TextField dataPathField = new TextField();
    private final Map<String, TextField> paramFields = new HashMap<>();
    private final Map<String, ComboBox<String>> paramCombos = new HashMap<>();
    private final ComboBox<String> baseModelComboBox = new ComboBox<>();
    private final ComboBox<String> quantBox = new ComboBox<>();
    private final Button trainButton = new Button("START TRAINING");
    private final Button cancelButton = new Button("CANCEL TRAINING");
    private final CheckBox resumeCheckBox = new CheckBox("RESUME FROM CHECKPOINT");
    private final TextField modelNameField = new TextField("jarvis");
    private final ProgressBar progressBar = new ProgressBar(0);
    private Process trainingProcess;
    private final JarvisController controller;

    public JarvisGymTab(JarvisController controller) {
        this.controller = controller;
        
        // Root styling
        setStyle("-fx-background-color: #0A0A0A;");
        setSpacing(0);
        setPadding(Insets.EMPTY);

        // Main scrollable container
        VBox scrollContent = new VBox(20);
        scrollContent.setPadding(new Insets(20, 30, 20, 30));
        scrollContent.setStyle("-fx-background-color: #0A0A0A;");

        ScrollPane scrollPane = new ScrollPane(scrollContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #0A0A0A; -fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        getChildren().add(scrollPane);

        // 1. HEADER
        Label fxHeader = new Label("🏋️ JARVIS GYM: NEURAL ARCHITECTURE REFINEMENT");
        fxHeader.setFont(Font.font("Arial", FontWeight.BOLD, 22));
        fxHeader.setTextFill(Color.web("#00FBFF"));
        scrollContent.getChildren().add(fxHeader);

        // 3. ACTION PANEL
        trainButton.setPrefHeight(50);
        trainButton.setPrefWidth(160);
        trainButton.setStyle("-fx-background-color: #005096; -fx-text-fill: white; -fx-font-weight: bold;");
        trainButton.setOnAction(e -> startTraining());

        Button resetBtn = new Button("RESTORE DEFAULTS");
        resetBtn.setPrefHeight(50);
        resetBtn.setPrefWidth(160);
        resetBtn.setStyle("-fx-background-color: #1A1A1A; -fx-text-fill: #FF00FF; -fx-border-color: #FF00FF; -fx-font-weight: bold;");

        cancelButton.setPrefHeight(50);
        cancelButton.setPrefWidth(160);
        cancelButton.setDisable(true);
        cancelButton.setStyle("-fx-background-color: #1A1A1A; -fx-text-fill: #FF4444; -fx-border-color: #FF4444; -fx-font-weight: bold;");
        cancelButton.setOnAction(e -> cancelTraining());

        resumeCheckBox.setTextFill(Color.web("#00FBFF"));
        resumeCheckBox.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        resumeCheckBox.setTooltip(new Tooltip("If enabled, JARVIS will pick up from the last saved checkpoint or jump to merging if training is complete."));

        resetBtn.setOnAction(e -> {
            paramFields.get("lr").setText("2e-4");
            paramFields.get("steps").setText("60");
            paramFields.get("lora_r").setText("16");
            paramFields.get("lora_alpha").setText("32");
            paramFields.get("batch_size").setText("2");
            paramFields.get("grad_acc").setText("4");
            paramFields.get("max_seq_length").setText("2048");
            paramFields.get("weight_decay").setText("0.01");
            paramFields.get("warmup").setText("10");
            paramCombos.get("scheduler").getSelectionModel().select("linear");
            paramCombos.get("optimizer").getSelectionModel().select("adamw_8bit");
            paramFields.get("seed").setText("3407");
            modelNameField.setText("jarvis");
            quantBox.getSelectionModel().select("q4_k_m");
            resumeCheckBox.setSelected(false);
        });

        // 2. MAIN CONFIGURATION PANEL (Dual-Column Layout)
        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(12);
        grid.setAlignment(Pos.TOP_LEFT);

        // Column constraints: Significantly increasing width of first two columns
        ColumnConstraints col0 = new ColumnConstraints(); col0.setPrefWidth(180);
        ColumnConstraints col1 = new ColumnConstraints(); col1.setPrefWidth(320); 
        ColumnConstraints col2 = new ColumnConstraints(); col2.setPrefWidth(140);
        ColumnConstraints col3 = new ColumnConstraints(); col3.setPrefWidth(220);
        grid.getColumnConstraints().addAll(col0, col1, col2, col3);

        int lRow = 0;
        int rRow = 0;

        // --- LEFT COLUMN CONFIGURATION ---
        grid.add(createLabelWithTooltip("Base Model:", "The foundation neural architecture to be fine-tuned."), 0, lRow);
        baseModelComboBox.setMaxWidth(Double.MAX_VALUE);
        baseModelComboBox.setEditable(false);
        baseModelComboBox.setStyle("-fx-base: #1A1A1A; -fx-text-fill: white;");
        updateModels(controller.getPythonService().getAvailableBrainModels());
        baseModelComboBox.setOnAction(e -> {
            String selected = baseModelComboBox.getSelectionModel().getSelectedItem();
            if (selected != null) {
                controller.getPythonService().setSelectedTrainingBaseModel(selected);
            }
        });
        grid.add(baseModelComboBox, 1, lRow++);

        addParamRow(grid, lRow++, 0, "Learning Rate", "lr", "2e-4", 
            "Scale of intelligence updates. \nIncrease: Faster learning but risks model collapse. \nDecrease: Higher stability.");
        addParamRow(grid, lRow++, 0, "Training Steps", "steps", "60", 
            "Total iterations through the data. \nIncrease: Deepens knowledge but risks robotic repetition (overfitting).");
        addParamRow(grid, lRow++, 0, "LoRA Rank (r)", "lora_r", "16", 
            "Complexity of neural changes. \nIncrease: Better reasoning but uses significantly more VRAM.");
        addParamRow(grid, lRow++, 0, "LoRA Alpha", "lora_alpha", "32",
            "Scaling factor for LoRA updates. \nIncrease: High impact on model weights. \nDecrease: Subtle changes.");
        addParamRow(grid, lRow++, 0, "Batch Size", "batch_size", "2", 
            "Samples processed per step. \nIncrease: Stable learning but requires heavy GPU memory.");
        addParamRow(grid, lRow++, 0, "Gradient Accumulation", "grad_acc", "4",
            "Steps to accumulate gradients before update. \nSimulates larger batch sizes on low VRAM GPUs.");
        addParamRow(grid, lRow++, 0, "Max Seq Length", "max_seq_length", "2048", 
            "Allows Jarvis to learn from longer conversation histories.");

        // --- RIGHT COLUMN CONFIGURATION ---
        addParamRow(grid, rRow++, 2, "Weight Decay", "weight_decay", "0.01",
            "L2 regularization factor. \nPrevents overfitting by penalizing large weights.");
        addParamRow(grid, rRow++, 2, "Warmup Steps", "warmup", "10",
            "Initial steps with reduced learning rate to prevent early training instability.");
        addComboParamRow(grid, rRow++, 2, "LR Scheduler", "scheduler", "linear",
            List.of("linear", "cosine", "constant", "constant_with_warmup", "cosine_with_restarts", "polynomial"),
            "Pattern of learning rate reduction over time.");
        addComboParamRow(grid, rRow++, 2, "Optimizer", "optimizer", "adamw_8bit",
            List.of("adamw_8bit", "paged_adamw_8bit", "adamw_32bit", "paged_adamw_32bit", "rmsprop", "sgd"),
            "Optimization algorithm. 8-bit versions are recommended for low VRAM.");
        addParamRow(grid, rRow++, 2, "Random Seed", "seed", "3407",
            "Seed for randomness. Use same seed to reproduce specific training results.");

        grid.add(createLabelWithTooltip("Result Model Name:", "The custom identifier for the final model in Ollama."), 2, rRow);
        modelNameField.setStyle("-fx-control-inner-background: #1A1A1A; -fx-text-fill: #00FBFF; -fx-border-color: #333;");
        modelNameField.setMaxWidth(Double.MAX_VALUE);
        grid.add(modelNameField, 3, rRow++);

        grid.add(createLabelWithTooltip("Training Data:", "The dataset in ShareGPT format used for fine-tuning."), 2, rRow);
        HBox browserPanel = new HBox(10);
        dataPathField.setPrefWidth(140);
        dataPathField.setStyle("-fx-control-inner-background: #1A1A1A; -fx-text-fill: white;");
        Button browseBtn = new Button("Browse");
        browseBtn.setStyle("-fx-background-color: #333; -fx-text-fill: white;");
        browseBtn.setOnAction(e -> browseFile());
        browserPanel.getChildren().addAll(dataPathField, browseBtn);
        grid.add(browserPanel, 3, rRow++);

        grid.add(createLabelWithTooltip("Quantization:", "Compression level for the final exported model."), 2, rRow);
        quantBox.getItems().addAll("q4_k_m", "q5_k_m", "q8_0", "f16");
        quantBox.getSelectionModel().selectFirst();
        quantBox.setStyle("-fx-base: #1A1A1A;");
        quantBox.setMaxWidth(Double.MAX_VALUE);
        grid.add(quantBox, 3, rRow++);

        // Add progress bar under parameters
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(16);
        progressBar.setStyle("-fx-accent: #00FBFF; -fx-control-inner-background: #1A1A1A; -fx-effect: dropshadow(gaussian, #00FBFF, 12, 0.5, 0, 0);");
        grid.add(progressBar, 0, Math.max(lRow, rRow), 4, 1);

        // 3. SIDE ACTION PANEL (Stacked vertically on the far right)
        VBox sideActionPanel = new VBox(15);
        sideActionPanel.setAlignment(Pos.TOP_CENTER);
        sideActionPanel.setPadding(new Insets(0, 0, 0, 20));
        
        Button generateDataBtn = new Button("GENERATE DATA");
        generateDataBtn.setPrefHeight(50);
        generateDataBtn.setPrefWidth(160);
        generateDataBtn.setStyle("-fx-background-color: #0066CC; -fx-text-fill: white; -fx-font-weight: bold;");
        generateDataBtn.setOnAction(e -> generateTrainingData());
        
        sideActionPanel.getChildren().addAll(trainButton, resumeCheckBox, resetBtn, cancelButton, generateDataBtn);

        // 4. CONTENT LAYOUT
        HBox contentLayout = new HBox(10);
        contentLayout.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(grid, Priority.ALWAYS);
        contentLayout.getChildren().addAll(grid, sideActionPanel);

        scrollContent.getChildren().add(contentLayout);
    }

    private void addParamRow(GridPane grid, int row, int colOffset, String label, String key, String defaultValue, String helpText) {
        grid.add(createLabelWithTooltip(label, helpText), colOffset, row);

        TextField field = new TextField(defaultValue);
        field.setStyle("-fx-control-inner-background: #1A1A1A; -fx-text-fill: white; -fx-border-color: #333;");
        field.setMaxWidth(Double.MAX_VALUE);
        paramFields.put(key, field);
        grid.add(field, colOffset + 1, row);
    }

    private void addComboParamRow(GridPane grid, int row, int colOffset, String label, String key, String defaultValue, List<String> options, String helpText) {
        grid.add(createLabelWithTooltip(label, helpText), colOffset, row);

        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll(options);
        combo.getSelectionModel().select(defaultValue);
        combo.setStyle("-fx-base: #1A1A1A; -fx-text-fill: white; -fx-border-color: #333;");
        combo.setMaxWidth(Double.MAX_VALUE);
        paramCombos.put(key, combo);
        grid.add(combo, colOffset + 1, row);
    }

    private HBox createLabelWithTooltip(String text, String helpText) {
        HBox labelBox = new HBox(5);
        labelBox.setAlignment(Pos.CENTER_LEFT);
        Label l = new Label(text);
        l.setTextFill(Color.LIGHTGRAY);
        
        Label helpIcon = new Label("❓");
        helpIcon.setTextFill(Color.web("#FF00FF"));
        Tooltip tooltip = new Tooltip(helpText);
        tooltip.setStyle("-fx-background-color: rgba(26, 26, 26, 0.95); -fx-text-fill: #00FBFF; -fx-border-color: #00FBFF; -fx-border-radius: 5; -fx-font-size: 12px;");
        tooltip.setShowDelay(javafx.util.Duration.millis(100));
        Tooltip.install(helpIcon, tooltip);
        
        labelBox.getChildren().addAll(l, helpIcon);
        return labelBox;
    }

    private void browseFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Neural Training Data");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Training Data & Textbooks (*.jsonl, *.pdf, *.txt)", "*.jsonl", "*.pdf", "*.txt"),
            new FileChooser.ExtensionFilter("ShareGPT JSONL", "*.jsonl"),
            new FileChooser.ExtensionFilter("Textbooks (PDF, TXT)", "*.pdf", "*.txt")
        );
        File file = fileChooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            dataPathField.setText(file.getAbsolutePath());
        }
    }
 public void setProgress(double progress) {
        Platform.runLater(() -> progressBar.setProgress(progress));
 }
    private void startTraining() {
        if (!validateHyperparameters()) return;

        String data = dataPathField.getText();

        // Industry Safety: Stop ALL GPU-heavy modules before training to maximize VRAM.
        // This prevents the "No negligible GPU memory" error in Unsloth.
        // These calls are asynchronous, so we need to wait for them to complete.
        controller.stopModule(JarvisModule.CORTEX_BRAIN); // Unpins Ollama model
        controller.stopModule(JarvisModule.OCULAR_EYES);  // Shuts down YOLO
        controller.stopModule(JarvisModule.VOCAL_MOUTH);  // Shuts down Kokoro

        // UI updates and actual training launch must happen on the JavaFX Application Thread
        // after the modules have had a chance to shut down.
        trainButton.setDisable(true);
        trainButton.setText("GYM IN SESSION...");
        progressBar.setProgress(-1); // Indeterminate
        
        String selectedBase = baseModelComboBox.getSelectionModel().getSelectedItem();

        new Thread(() -> {
            // Give modules a moment to shut down and release VRAM
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}

            try {
                // Build the command with all hyperparameters
                String cmd = String.format(
                    "conda run --no-capture-output -n jarvis_brain python scripts/jarvis_brain.py %s --model_name \"%s\" --data \"%s\" --model_id \"%s\" --lr %s --steps %s --lora_r %s --lora_alpha %s --batch_size %s --grad_accum %s --max_seq_length %s --weight_decay %s --warmup %s --lr_scheduler %s --optim %s --seed %s --quant %s",
                    resumeCheckBox.isSelected() ? "--resume" : "",
                    modelNameField.getText().trim(),
                    data,
                    selectedBase,
                    paramFields.get("lr").getText(),
                    paramFields.get("steps").getText(),
                    paramFields.get("lora_r").getText(),
                    paramFields.get("lora_alpha").getText(),
                    paramFields.get("batch_size").getText(),
                    paramFields.get("grad_acc").getText(),
                    paramFields.get("max_seq_length").getText(),
                    paramFields.get("weight_decay").getText(),
                    paramFields.get("warmup").getText(),
                    paramCombos.get("scheduler").getValue(),
                    paramCombos.get("optimizer").getValue(),
                    paramFields.get("seed").getText(),
                    quantBox.getValue()
                );
                
                trainingProcess = controller.executeGymTraining(cmd);
                Platform.runLater(() -> cancelButton.setDisable(false));
                int exitCode = trainingProcess.waitFor(); // This may take a long time
                
                Platform.runLater(() -> {
                    cancelButton.setDisable(true);
                    progressBar.setProgress(exitCode == 0 ? 1.0 : 0);
                    if (exitCode == 0) {
                        new Alert(Alert.AlertType.INFORMATION, "Jarvis upgraded successfully!").show();
                    } else {
                        new Alert(Alert.AlertType.ERROR, "Gym session failed. Check logs.").show();
                    }
                    trainButton.setDisable(false);
                    trainButton.setText("START TRAINING");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.ERROR, "Critical Gym Error: " + ex.getMessage()).show();
                    trainButton.setDisable(false);
                });
            }
        }).start();
    }

    private void cancelTraining() {
        if (trainingProcess != null && trainingProcess.isAlive()) {
            try {
                Runtime.getRuntime().exec("taskkill /F /T /PID " + trainingProcess.pid());
            } catch (Exception e) {
                System.err.println("Failed to cancel training process: " + e.getMessage());
            }
        }
    }

    private boolean validateHyperparameters() {
        StringBuilder errors = new StringBuilder();

        if (baseModelComboBox.getSelectionModel().getSelectedItem() == null) errors.append("- Base Model selection is required.\n");
        String data = dataPathField.getText().trim();
        if (data.isEmpty()) {
            errors.append("- Training Data file path is required.\n");
        } else {
            File f = new File(data);
            if (!f.exists() || !f.isFile()) {
                errors.append("- Specified data file does not exist.\n");
            }
        }

        validateDouble(errors, "lr", "Learning Rate");
        validateInt(errors, "steps", "Training Steps");
        validateInt(errors, "lora_r", "LoRA Rank");
        validateInt(errors, "lora_alpha", "LoRA Alpha");
        validateInt(errors, "batch_size", "Batch Size");
        validateInt(errors, "grad_acc", "Gradient Accumulation");
        validateInt(errors, "max_seq_length", "Max Seq Length");
        validateDouble(errors, "weight_decay", "Weight Decay");
        validateInt(errors, "warmup", "Warmup Steps");
        validateInt(errors, "seed", "Random Seed");

        if (modelNameField.getText().trim().isEmpty()) errors.append("- Result Model Name is required.\n");

        if (errors.length() > 0) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Configuration Error");
            alert.setHeaderText("Invalid Hyperparameters Detected");
            alert.setContentText(errors.toString());
            alert.showAndWait();
            return false;
        }
        return true;
    }

    private void validateDouble(StringBuilder errors, String key, String label) {
        try {
            double val = Double.parseDouble(paramFields.get(key).getText());
            if (val < 0) errors.append("- ").append(label).append(" cannot be negative.\n");
            else if (key.equals("lr") && val == 0) errors.append("- ").append(label).append(" must be positive.\n");
        } catch (NumberFormatException e) {
            errors.append("- ").append(label).append(" must be a valid numeric value.\n");
        }
    }

    private void validateInt(StringBuilder errors, String key, String label) {
        try {
            int val = Integer.parseInt(paramFields.get(key).getText());
            if (val < 0) errors.append("- ").append(label).append(" cannot be negative.\n");
            else if ((key.equals("steps") || key.equals("lora_r") || key.equals("batch_size") || key.equals("max_seq_length")) && val == 0) {
                errors.append("- ").append(label).append(" must be positive.\n");
            }
        } catch (NumberFormatException e) {
            errors.append("- ").append(label).append(" must be a valid integer.\n");
        }
    }

    /**
     * Dynamically updates the model list while preserving selection.
     */
    public void updateModels(List<String> models) {
        Platform.runLater(() -> {
            String current = baseModelComboBox.getSelectionModel().getSelectedItem();
            baseModelComboBox.getItems().setAll(models);
            if (current != null && models.contains(current)) {
                baseModelComboBox.getSelectionModel().select(current);
            } else if (!models.isEmpty()) {
                baseModelComboBox.getSelectionModel().select(0);
            }
        });
    }

    private void generateTrainingData() {
        new Thread(() -> {
            try {
                // Set default training data path to conversations/trainingData.jsonl
                String outputPath = "conversations/trainingData.jsonl";
                Platform.runLater(() -> dataPathField.setText(outputPath));
                
                // Run the training data extraction script
                String cmdLine = "python scripts/extract_training_dataset.py --include_memo";
                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", cmdLine);
                pb.directory(new java.io.File(System.getProperty("user.dir")));
                pb.redirectErrorStream(true);
                
                Process process = pb.start();
                
                // Register the process with the controller to show output in the Console tab
                controller.registerProcessOutput("Data Generation", process, cmdLine);
                
                int exitCode = process.waitFor();
                
                Platform.runLater(() -> {
                    if (exitCode == 0) {
                        new Alert(Alert.AlertType.INFORMATION, 
                            "Training data generated successfully!\nOutput: " + outputPath).show();
                    } else {
                        new Alert(Alert.AlertType.ERROR, 
                            "Failed to generate training data.\nCheck console for details.").show();
                    }
                });
                
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.ERROR, 
                        "Error generating training data: " + ex.getMessage()).show();
                });
            }
        }).start();
    }
}