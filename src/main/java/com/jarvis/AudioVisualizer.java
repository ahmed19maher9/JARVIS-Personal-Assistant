package com.jarvis;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import java.util.Random;

/**
 * Audio visualizer component for JARVIS cyberpunk interface
 * Creates dynamic waveform visualizations
 */
public class AudioVisualizer extends Pane {
    
    private final Canvas canvas;
    private final GraphicsContext gc;
    private final Color primaryColor;
    private final Color secondaryColor;
    private final Random random = new Random();
    private final double[] waveform;
    private final int bars = 50;
    
    public AudioVisualizer(Color primaryColor, Color secondaryColor) {
        this.primaryColor = primaryColor;
        this.secondaryColor = secondaryColor;
        this.waveform = new double[bars];
        
        // Initialize canvas
        canvas = new Canvas();
        gc = canvas.getGraphicsContext2D();
        
        // Bind canvas size to pane size
        canvas.widthProperty().bind(this.widthProperty());
        canvas.heightProperty().bind(this.heightProperty());
        
        // Initialize waveform data
        for (int i = 0; i < bars; i++) {
            waveform[i] = Math.random() * 0.3;
        }
        
        this.getChildren().add(canvas);
        
        // Initial draw
        drawWaveform();
    }
    
    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        drawWaveform();
    }
    
    public void updateWaveform() {
        // Update waveform with smooth transitions
        for (int i = 0; i < bars; i++) {
            // Create smooth wave-like motion
            double target = 0.1 + Math.abs(Math.sin((System.currentTimeMillis() / 1000.0 + i * 0.2))) * 0.4;
            double current = waveform[i];
            waveform[i] = current + (target - current) * 0.1;
            
            // Add some randomness for realistic effect
            waveform[i] += (random.nextDouble() - 0.5) * 0.05;
            waveform[i] = Math.max(0.05, Math.min(0.9, waveform[i]));
        }
        
        drawWaveform();
    }
    
    private void drawWaveform() {
        if (canvas.getWidth() <= 0 || canvas.getHeight() <= 0) {
            return;
        }
        
        // Clear canvas
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        
        // Set up drawing parameters
        double barWidth = canvas.getWidth() / bars;
        double maxHeight = canvas.getHeight() * 0.8;
        double centerY = canvas.getHeight() / 2;
        
        // Draw waveform bars with gradient effect
        for (int i = 0; i < bars; i++) {
            double barHeight = waveform[i] * maxHeight;
            double x = i * barWidth;
            
            // Create gradient for each bar
            Color barColor = interpolateColor(primaryColor, secondaryColor, (double) i / bars);
            
            // Draw top bar (above center)
            gc.setFill(barColor);
            gc.fillRect(x + 1, centerY - barHeight, barWidth - 2, barHeight);
            
            // Draw bottom bar (below center) - mirror effect
            gc.setFill(Color.color(barColor.getRed(), barColor.getGreen(), barColor.getBlue(), barColor.getOpacity() * 0.6));
            gc.fillRect(x + 1, centerY, barWidth - 2, barHeight * 0.7);
            
            // Add glow effect for active bars
            if (waveform[i] > 0.6) {
                gc.setStroke(Color.color(barColor.getRed(), barColor.getGreen(), barColor.getBlue(), 0.8));
                gc.setLineWidth(2);
                gc.strokeRect(x + 1, centerY - barHeight - 2, barWidth - 2, barHeight + 2);
            }
        }
        
        // Draw center line
        gc.setStroke(Color.color(primaryColor.getRed(), primaryColor.getGreen(), primaryColor.getBlue(), 0.3));
        gc.setLineWidth(1);
        gc.strokeLine(0, centerY, canvas.getWidth(), centerY);
    }
    
    private Color interpolateColor(Color color1, Color color2, double factor) {
        factor = Math.max(0, Math.min(1, factor));
        
        double red = color1.getRed() + (color2.getRed() - color1.getRed()) * factor;
        double green = color1.getGreen() + (color2.getGreen() - color1.getGreen()) * factor;
        double blue = color1.getBlue() + (color2.getBlue() - color1.getBlue()) * factor;
        double opacity = color1.getOpacity() + (color2.getOpacity() - color1.getOpacity()) * factor;
        
        return Color.color(red, green, blue, opacity);
    }
    
    public void setActive(boolean active) {
        if (active) {
            // Boost activity when active
            for (int i = 0; i < bars; i++) {
                waveform[i] = Math.min(0.9, waveform[i] + 0.2);
            }
        } else {
            // Calm down when inactive
            for (int i = 0; i < bars; i++) {
                waveform[i] = Math.max(0.1, waveform[i] - 0.1);
            }
        }
    }
}
