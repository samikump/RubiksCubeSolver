package none.rubikscubesolver;

public class ColorLogArea extends javafx.scene.control.TextArea {
    
    private final javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow();
    private final javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(flow);

    public ColorLogArea() {
        setEditable(false);
        scroll.setPrefHeight(300);
        scroll.setFitToWidth(true);

        // 1. STYLING: Restore the true black background and apply the classic JavaFX focused look
        flow.setStyle("-fx-background-color: #000000; -fx-padding: 3px;");
        
        // This adds a clean 1px deep cyan/blue border line around the viewport to restore that look
        scroll.setStyle(
            "-fx-background: #000000; " +
            "-fx-background-color: #000000; " +
            "-fx-viewport-background: #000000; " +
            "-fx-border-color: #1a73e8; " + // Standard focus blue/cyan accent hex color
            "-fx-border-width: 1px; "
        );
       
        // 2. AUTOSCROLL: Force the layout container to follow text line additions actively
        flow.getChildren().addListener((javafx.collections.ListChangeListener<javafx.scene.Node>) c -> {
            flow.layout();
            // Wait until the ScrollPane finishes its internal layout calculations, then scroll
            javafx.application.Platform.runLater(() -> scroll.setVvalue(1.0));
        });
        
    }

    public javafx.scene.control.ScrollPane getPane() { return scroll; }

    @Override
    public void appendText(String text) {
        javafx.scene.text.Text node = new javafx.scene.text.Text(text);

        // 3. TYPOGRAPHY: Consolas font
        node.setFont(javafx.scene.text.Font.font("Consolas", 11));
        
        // 4. Color routing logic
        if (text != null && text.contains("pruning table...")) {
            node.setFill(javafx.scene.paint.Color.CYAN); // Cyan for pruning table headlines
        } else if (text != null && (text.contains("Searching for") || text.contains("Solution complete!"))) {
            node.setFill(javafx.scene.paint.Color.valueOf("#ff55ff")); // Magenta for critical search events
        } else if (text != null && (text.contains("NOTE!"))) {
            node.setFill(javafx.scene.paint.Color.YELLOW); // Yellow for important notes
        } else {
            node.setFill(javafx.scene.paint.Color.valueOf("#00ff00")); // Classic green text fill for everything else
        }
        
        flow.getChildren().add(node);
    }
}
