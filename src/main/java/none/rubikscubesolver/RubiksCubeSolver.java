package none.rubikscubesolver;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/*
 * Rubik's Cube Solver - Professional 3D Edition
 * Features:
 * 3D interactive cube
 * 2D mini-map
 * Kociemba notation for solutions
 * Background Python solver via GraalVM.
 */

public class RubiksCubeSolver extends Application {

    private final Map<String, Color[]> cubeState = new HashMap<>();
    private final String[] faces = {"U", "L", "F", "R", "B", "D"};
    private final Map<String, Color> faceBaseColors = Map.of(
            "U", Color.WHITE, "D", Color.YELLOW, "L", Color.ORANGE,
            "R", Color.RED, "F", Color.GREEN, "B", Color.BLUE
    );
    
    private Group cubeGroup;
    private final Map<String, Box[]> stickerNodes = new HashMap<>();
    private final Map<String, GridPane> miniMapGrids = new HashMap<>();
    private TextArea logArea;
    private Label solutionLabel;
    private Button solveBtn;
    private Button scrambleBtn;
    private Button resetBtn;
    private Button prevBtn;
    private Button playPauseBtn;
    private Button nextBtn;
    
    private boolean isAnimating = false;
    private boolean isPaused = true;
    
    private List<String> solutionMoves = new ArrayList<>();
    private int currentMoveIndex = -1;
    private Timeline animationTimeline;
    
    private double anchorX, anchorY;
    private double anchorAngleX = 0;
    private double anchorAngleY = 0;
    private final Rotate rotateX = new Rotate(25, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(-45, Rotate.Y_AXIS);

    // Persistent GraalVM Context for performance
    private Context pyContext;
    private Value solveFunc;

    @Override
    public void start(Stage primaryStage) {
        resetState();
        initPython();

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #121212;");

        // --- 3D VIEW (Center) ---
        cubeGroup = new Group();
        build3DCube();
        
        // Enhanced Lighting
        AmbientLight ambient = new AmbientLight(Color.color(0.4, 0.4, 0.4));
        PointLight point1 = new PointLight(Color.WHITE);
        point1.setTranslateZ(-30);
        point1.setTranslateY(-20);
        point1.setTranslateX(-20);
        
        PointLight point2 = new PointLight(Color.color(0.8, 0.8, 0.8));
        point2.setTranslateZ(30);
        point2.setTranslateY(20);
        
        cubeGroup.getChildren().addAll(ambient, point1, point2);

        SubScene subScene = new SubScene(cubeGroup, 750, 650, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.TRANSPARENT);
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(2000.0);
        camera.setTranslateZ(-18);
        subScene.setCamera(camera);

        // Mouse rotation for 3D view
        subScene.setOnMousePressed(event -> {
            anchorX = event.getSceneX();
            anchorY = event.getSceneY();
            anchorAngleX = rotateX.getAngle();
            anchorAngleY = rotateY.getAngle();
        });
        subScene.setOnMouseDragged(event -> {
            rotateX.setAngle(anchorAngleX - (anchorY - event.getSceneY()) * 0.5);
            rotateY.setAngle(anchorAngleY + (anchorX - event.getSceneX()) * 0.5);
        });

        StackPane cubeContainer = new StackPane(subScene);
        cubeContainer.setAlignment(Pos.CENTER);
        root.setCenter(cubeContainer);

        // --- SIDEBAR (Right: Mini-Map & Logs) ---
        VBox sidebar = new VBox(20);
        sidebar.setPadding(new Insets(20));
        sidebar.setPrefWidth(340);
        sidebar.setStyle("-fx-background-color: #1e1e1e; -fx-border-color: #333; -fx-border-width: 0 0 0 1;");

        Label mapTitle = new Label("2D MINI-MAP");
        mapTitle.setTextFill(Color.LIGHTGRAY);
        mapTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-letter-spacing: 2px;");

        GridPane miniMap = new GridPane();
        miniMap.setHgap(12);
        miniMap.setVgap(12);
        miniMap.setAlignment(Pos.CENTER);
        miniMap.add(createMiniFace("U"), 1, 0);
        miniMap.add(createMiniFace("L"), 0, 1);
        miniMap.add(createMiniFace("F"), 1, 1);
        miniMap.add(createMiniFace("R"), 2, 1);
        miniMap.add(createMiniFace("B"), 3, 1);
        miniMap.add(createMiniFace("D"), 1, 2);

        solutionLabel = new Label("Solution: ---");
        solutionLabel.setTextFill(Color.GOLD);
        solutionLabel.setWrapText(true);
        solutionLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-font-family: 'Segoe UI';");

        // Animation Controls
        HBox animControls = new HBox(10);
        animControls.setAlignment(Pos.CENTER);
        prevBtn = createStyledButton("<<", "#34495e");
        playPauseBtn = createStyledButton("PLAY", "#2980b9");
        nextBtn = createStyledButton(">>", "#34495e");
        
        prevBtn.setOnAction(e -> stepBack());
        playPauseBtn.setOnAction(e -> togglePlayPause());
        nextBtn.setOnAction(e -> stepForward());
        
        animControls.getChildren().addAll(prevBtn, playPauseBtn, nextBtn);
        
        ColorLogArea customLog = new ColorLogArea();
        customLog.getPane().setPrefHeight(300);
        logArea = customLog;
        
        sidebar.getChildren().addAll(mapTitle, miniMap, new Separator(), solutionLabel, animControls, customLog.getPane());
        root.setRight(sidebar);
        
        // --- BOTTOM CONTROLS ---
        HBox controls = new HBox(25);
        controls.setPadding(new Insets(25));
        controls.setAlignment(Pos.CENTER);
        controls.setStyle("-fx-background-color: #121212;");

        scrambleBtn = createStyledButton("SCRAMBLE", "#d35400");
        scrambleBtn.setOnAction(e -> scramble());

        solveBtn = createStyledButton("SOLVE CUBE", "#27ae60");
        solveBtn.setOnAction(e -> solve());

        resetBtn = createStyledButton("RESET", "#c0392b");
        resetBtn.setOnAction(e -> {
            resetState();
            updateVisuals();
            solutionLabel.setText("Solution: ---");
            solutionMoves.clear();
            currentMoveIndex = -1;
            stopAnimation();
            log("Cube reset to solved state.");
        });

        controls.getChildren().addAll(scrambleBtn, solveBtn, resetBtn);
        root.setBottom(controls);

        Scene scene = new Scene(root, 1200, 900);
        primaryStage.setTitle("Rubik's Cube Solver - 3D Pro Edition");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        updateVisuals();
    }

    private void initPython() {
        new Thread(() -> {
            try {
                log("Initializing GraalVM Python Engine...");
                pyContext = Context.newBuilder("python")
                        .allowAllAccess(true)
                        .build();
                
                Consumer<String> pythonLogger = msg -> log(msg);
                pyContext.getPolyglotBindings().putMember("log", pythonLogger);
                
                Source source = Source.newBuilder("python", new File("rubik_solver.py")).build();
                pyContext.eval(source);
                
                solveFunc = pyContext.getPolyglotBindings().getMember("solve");
                log("Python Engine Ready.");
                log("--------------------");
                log("NOTE! Finding the first solution may take up to 5-10 minutes.");
                log("--------------------");
                log("NOTE! Click PAUSE during solution animation to enable rewinding and forwarding.");
                log("--------------------");
                log("NOTE! If solution animation ends, it cannot be restarted.");
                log("--------------------");
                log("System Ready. Click 3D stickers to edit colors.");
                log("--------------------");
                Platform.runLater(this::updateButtonStates);
            } catch (Exception e) {
                log("CRITICAL: Python Initialization Failed: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private Button createStyledButton(String text, String color) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 12 30; -fx-background-radius: 5;");
        btn.setOnMouseEntered(e -> {
            if (!btn.isDisable()) btn.setOpacity(0.8);
        });
        btn.setOnMouseExited(e -> {
            if (!btn.isDisable()) btn.setOpacity(1.0);
        });
        return btn;
    }

    private void build3DCube() {
        cubeGroup.getChildren().clear();
        cubeGroup.getTransforms().setAll(rotateX, rotateY);

        double size = 1.0;
        double gap = 0.04;
        Color cubieColor = Color.web("#222222");

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;

                    Box cubie = new Box(size, size, size);
                    cubie.setTranslateX(x * (size + gap));
                    cubie.setTranslateY(y * (size + gap));
                    cubie.setTranslateZ(z * (size + gap));
                    cubie.setMaterial(new PhongMaterial(cubieColor));
                    cubeGroup.getChildren().add(cubie);
                    
                    // Add stickers with corrected mapping for 2D map consistency
                    if (y == -1) addSticker(cubie, "U", x + 1, 1 - z, 0, -size/2 - 0.01, 0, Rotate.X_AXIS, 90, x == 0 && z == 0);
                    if (y == 1)  addSticker(cubie, "D", x + 1, z + 1, 0, size/2 + 0.01, 0, Rotate.X_AXIS, 90, x == 0 && z == 0);
                    if (x == -1) addSticker(cubie, "L", 1 - z, y + 1, -size/2 - 0.01, 0, 0, Rotate.Y_AXIS, 90, y == 0 && z == 0);
                    if (x == 1)  addSticker(cubie, "R", z + 1, y + 1, size/2 + 0.01, 0, 0, Rotate.Y_AXIS, 90, y == 0 && z == 0);
                    if (z == -1) addSticker(cubie, "F", x + 1, y + 1, 0, 0, -size/2 - 0.01, Rotate.Z_AXIS, 0, x == 0 && y == 0);
                    if (z == 1)  addSticker(cubie, "B", 1 - x, y + 1, 0, 0, size/2 + 0.01, Rotate.Z_AXIS, 0, x == 0 && y == 0);
                }
            }
        }
    }

    private void addSticker(Box cubie, String face, int col, int row, double tx, double ty, double tz, javafx.geometry.Point3D axis, double angle, boolean isCenter) {
        Box sticker = new Box(0.88, 0.88, 0.02);
        sticker.setTranslateX(cubie.getTranslateX() + tx);
        sticker.setTranslateY(cubie.getTranslateY() + ty);
        sticker.setTranslateZ(cubie.getTranslateZ() + tz);
        sticker.setRotationAxis(axis);
        sticker.setRotate(angle);
        
        int index = row * 3 + col;
        sticker.setMaterial(new PhongMaterial(cubeState.get(face)[index]));
        
        sticker.setOnMouseClicked(e -> {
            if (isAnimating || solveBtn.getText().equals("SOLVING...")) return;
            if (isCenter || index == 4) {
                log("Center pieces are fixed and cannot be edited.");
                return;
            }
            cubeState.get(face)[index] = nextColor(cubeState.get(face)[index]);
            // Clear solution state when user manually edits the cube
            solutionMoves.clear();
            currentMoveIndex = -1;
            solutionLabel.setText("Solution: ---");
            updateVisuals();
        });

        if (!stickerNodes.containsKey(face)) stickerNodes.put(face, new Box[9]);
        stickerNodes.get(face)[index] = sticker;
        cubeGroup.getChildren().add(sticker);
    }

    private Color nextColor(Color current) {
        Color[] palette = {Color.WHITE, Color.YELLOW, Color.ORANGE, Color.RED, Color.GREEN, Color.BLUE};
        for (int i = 0; i < palette.length; i++) {
            if (palette[i].equals(current)) return palette[(i + 1) % palette.length];
        }
        return Color.WHITE;
    }

    private void scramble() {
        if (isAnimating) return;
        log("Scrambling cube via layer rotations...");
        String[] moves = {"U", "D", "L", "R", "F", "B"};
        String[] mods = {"", "'"};
        for (int i = 0; i < 30; i++) {
            String move = moves[(int) (Math.random() * 6)] + mods[(int) (Math.random() * 2)];
            applyMove(move);
        }
        // Clear solution state after scramble
        solutionMoves.clear();
        currentMoveIndex = -1;
        solutionLabel.setText("Solution: ---");
        updateVisuals();
        log("Scramble complete.");
        log("--------------------");
    }

    private void solve() {
        if (isAnimating) return;
        if (solveFunc == null) {
            log("ERROR: Python Engine is still initializing. Please wait...");
            return;
        }
        
        String state = getKociembaState();
        if (state.startsWith("Error")) {
            log(state);
            return;
        }
        
        log("Invoking solver for state: " + state);
        solveBtn.setText("SOLVING...");
        updateButtonStates();
        
        new Thread(() -> {
            try {
                Value result = solveFunc.execute(state);
                handleSolution(result.asString());
            } catch (Exception e) {
                log("CRITICAL ERROR: Solver failed. " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> {
                    solutionLabel.setText("Error: Solver failed.");
                    solveBtn.setText("SOLVE CUBE");
                    updateButtonStates();
                });
            }
        }).start();
    }
    
    private void handleSolution(String solution) {
        Platform.runLater(() -> {
            solveBtn.setText("SOLVE CUBE");
            if (solution != null && !solution.startsWith("Error")) {
                if (solution.trim().isEmpty()) {
                    solutionLabel.setText("Cube is already solved!");
                    solutionMoves.clear();
                    currentMoveIndex = -1;
                    stopAnimation();
                    log("Cube is already solved.");
                } else {
                    String[] moves = solution.trim().split("\\s+");
                    int moveCount = moves.length;
                    solutionLabel.setText(String.format("Solution (%d moves):\n%s", moveCount, solution));
                    solutionMoves = new ArrayList<>(Arrays.asList(moves));
                    currentMoveIndex = -1;
                    startAnimation();
                    // log("Solution found: " + moveCount + " move(s).");
                }
            } else {
                String cleanError = solution != null ? solution.replaceFirst("^Error:\\s*", "") : "Unknown error";
                log("Solver Error: " + cleanError);
                solutionLabel.setText("Error: " + cleanError);
            }
            updateButtonStates();
        });
    }

    private void startAnimation() {
        isAnimating = true;
        isPaused = false;
        playPauseBtn.setText("PAUSE");
        updateButtonStates();
        
        if (animationTimeline != null) animationTimeline.stop();
        animationTimeline = new Timeline(new KeyFrame(Duration.seconds(0.8), e -> stepForward()));
        animationTimeline.setCycleCount(Timeline.INDEFINITE);
        animationTimeline.play();
    }

    private void stopAnimation() {
        if (animationTimeline != null) animationTimeline.stop();
        isAnimating = false;
        isPaused = true;
        playPauseBtn.setText("PLAY");
        // Clear solution moves when animation finishes to prevent stale state
        if (currentMoveIndex >= solutionMoves.size() - 1) {
            solutionMoves.clear();
            currentMoveIndex = -1;
        }
        updateButtonStates();
    }

    private void togglePlayPause() {
        if (solutionMoves.isEmpty()) return;
        if (isPaused) {
            isPaused = false;
            isAnimating = true;
            playPauseBtn.setText("PAUSE");
            if (animationTimeline != null) animationTimeline.play();
            else startAnimation();
        } else {
            isPaused = true;
            isAnimating = false;
            playPauseBtn.setText("PLAY");
            if (animationTimeline != null) animationTimeline.pause();
        }
        updateButtonStates();
    }
    
    private void stepForward() {
        if (currentMoveIndex < solutionMoves.size() - 1) {
            currentMoveIndex++;
            String move = solutionMoves.get(currentMoveIndex);
            applyMove(move);
            updateVisuals();

            // 1. Add an extra space if it's a single-letter move without a prime mark (')
            // If the move length is 1 (e.g., "R", "U"), append an extra space to match "R'", "U'"
            String alignedMove = move;
            if (move.length() == 1) {
                alignedMove = move + " ";
            }

            // 2. Format numbers with a leading zero if they are single digits (%02d)
            int currentStep = currentMoveIndex + 1;
            int totalSteps = solutionMoves.size();
        
            String logMessage = String.format("Step Forward: %s (%02d/%02d)", 
                alignedMove, currentStep, totalSteps);

            log(logMessage);
        } else {
            stopAnimation();
            log("Solution complete!");
        }
    }
    
    private void stepBack() {
        if (currentMoveIndex >= 0) {
            String move = solutionMoves.get(currentMoveIndex);
            String inverse = getInverseMove(move);
            applyMove(inverse);
            updateVisuals();

            // 1. Add an extra space if the inverse move is a single letter without a prime mark (')
            String alignedInverse = inverse;
            if (inverse.length() == 1) {
                alignedInverse = inverse + " ";
            }

            // 2. Format numbers with a leading zero if they are single digits (%02d)
            int currentStep = currentMoveIndex + 1; // Current step we are undoing
            int totalSteps = solutionMoves.size();

            // 3. Construct the clean, aligned step back log message
            String logMessage = String.format("Step Back:    %s (%02d/%02d) (Undo %s)", 
            alignedInverse, currentStep, totalSteps, move);

            log(logMessage);

            currentMoveIndex--;
            if (!isPaused) togglePlayPause();
            updateButtonStates();
        }
    }

    private String getInverseMove(String move) {
        if (move.endsWith("'")) return move.substring(0, 1);
        return move + "'";
    }

    private void applyMove(String move) {
        int count = 1;
        if (move.endsWith("'")) count = 3;
        
        char face = move.charAt(0);
        for (int i = 0; i < count; i++) {
            executeRotation(String.valueOf(face));
        }
    }

    private void executeRotation(String face) {
        rotateFaceArray(face);
        Color[] u = cubeState.get("U"), d = cubeState.get("D"), l = cubeState.get("L"), 
                r = cubeState.get("R"), f = cubeState.get("F"), b = cubeState.get("B");
        
        switch (face) {
            case "U":
                swap4(f, 0, l, 0, b, 0, r, 0);
                swap4(f, 1, l, 1, b, 1, r, 1);
                swap4(f, 2, l, 2, b, 2, r, 2);
                break;
            case "D":
                swap4(f, 6, r, 6, b, 6, l, 6);
                swap4(f, 7, r, 7, b, 7, l, 7);
                swap4(f, 8, r, 8, b, 8, l, 8);
                break;
            case "L":
                swap4(u, 0, f, 0, d, 0, b, 8);
                swap4(u, 3, f, 3, d, 3, b, 5);
                swap4(u, 6, f, 6, d, 6, b, 2);
                break;
            case "R":
                swap4(u, 8, b, 0, d, 8, f, 8);
                swap4(u, 5, b, 3, d, 5, f, 5);
                swap4(u, 2, b, 6, d, 2, f, 2);
                break;
            case "F":
                swap4(u, 6, r, 0, d, 2, l, 8);
                swap4(u, 7, r, 3, d, 1, l, 5);
                swap4(u, 8, r, 6, d, 0, l, 2);
                break;
            case "B":
                swap4(u, 2, l, 0, d, 6, r, 8);
                swap4(u, 1, l, 3, d, 7, r, 5);
                swap4(u, 0, l, 6, d, 8, r, 2);
                break;
        }
    }

    private void swap4(Color[] a, int i, Color[] b, int j, Color[] c, int k, Color[] d, int l) {
        Color temp = a[i];
        a[i] = d[l];
        d[l] = c[k];
        c[k] = b[j];
        b[j] = temp;
    }

    private void rotateFaceArray(String face) {
        Color[] data = cubeState.get(face);
        Color[] next = new Color[9];
        next[0] = data[6]; next[1] = data[3]; next[2] = data[0];
        next[3] = data[7]; next[4] = data[4]; next[5] = data[1];
        next[6] = data[8]; next[7] = data[5]; next[8] = data[2];
        cubeState.put(face, next);
    }

    private void resetState() {
        for (String face : faces) {
            Color[] data = new Color[9];
            Arrays.fill(data, faceBaseColors.get(face));
            cubeState.put(face, data);
        }
    }

    private void updateVisuals() {
        stickerNodes.forEach((face, stickers) -> {
            Color[] colors = cubeState.get(face);
            for (int i = 0; i < 9; i++) {
                if (stickers[i] != null) {
                    ((PhongMaterial)stickers[i].getMaterial()).setDiffuseColor(colors[i]);
                }
            }
        });
        
        miniMapGrids.forEach((face, grid) -> {
            Color[] data = cubeState.get(face);
            for (int i = 0; i < 9; i++) {
                Region cell = (Region) grid.getChildren().get(i);
                cell.setBackground(new Background(new BackgroundFill(data[i], CornerRadii.EMPTY, Insets.EMPTY)));
            }
        });
        updateButtonStates();
    }

    private void updateButtonStates() {
        if (solveBtn == null || scrambleBtn == null || resetBtn == null) return;

        boolean isCalculating = solveBtn.getText().equals("SOLVING...");
        boolean isPlaying = isAnimating;
        boolean solved = isSolved();
        boolean valid = isValidPieceComposition();

        // Solve: Disabled if calculating, playing, solved (P1), or invalid composition (P2, P3)
        applyButtonStyle(solveBtn, isCalculating || isPlaying || solved || !valid);
        
        // Scramble: Disabled if calculating, playing, or (not solved AND invalid composition)
        applyButtonStyle(scrambleBtn, isCalculating || isPlaying || (!solved && !valid));

        // Reset: Disabled if calculating, playing, or solved
        applyButtonStyle(resetBtn, isCalculating || isPlaying || solved);
        
        if (prevBtn != null) applyButtonStyle(prevBtn, isCalculating || isPlaying || solutionMoves.isEmpty() || currentMoveIndex < 0);
        if (playPauseBtn != null) applyButtonStyle(playPauseBtn, isCalculating || solutionMoves.isEmpty());
        if (nextBtn != null) applyButtonStyle(nextBtn, isCalculating || isPlaying || solutionMoves.isEmpty() || currentMoveIndex >= solutionMoves.size() - 1);
    }

    private void applyButtonStyle(Button btn, boolean disable) {
        btn.setDisable(disable);
        btn.setOpacity(disable ? 0.4 : 1.0);
        btn.setMouseTransparent(disable);
    }

    // pseudo-solvability rule 1 (P1), is cube already in solved state?
    private boolean isSolved() {
        for (String face : faces) {
            Color[] colors = cubeState.get(face);
            Color expected = faceBaseColors.get(face);
            for (Color c : colors) {
                if (!c.equals(expected)) return false;
            }
        }
        return true;
    }

    // pseudo-solvability rules 2 and 3 (P2 and P3), do edge and corner pieces have correct composition?
    private boolean isValidPieceComposition() {
        List<Set<Color>> edges = new ArrayList<>();
        edges.add(createColorSet(cubeState.get("U")[7], cubeState.get("F")[1]));
        edges.add(createColorSet(cubeState.get("U")[5], cubeState.get("R")[1]));
        edges.add(createColorSet(cubeState.get("U")[3], cubeState.get("L")[1]));
        edges.add(createColorSet(cubeState.get("U")[1], cubeState.get("B")[1]));
        edges.add(createColorSet(cubeState.get("D")[1], cubeState.get("F")[7]));
        edges.add(createColorSet(cubeState.get("D")[5], cubeState.get("R")[7]));
        edges.add(createColorSet(cubeState.get("D")[3], cubeState.get("L")[7]));
        edges.add(createColorSet(cubeState.get("D")[7], cubeState.get("B")[7]));
        edges.add(createColorSet(cubeState.get("F")[5], cubeState.get("R")[3]));
        edges.add(createColorSet(cubeState.get("F")[3], cubeState.get("L")[5]));
        edges.add(createColorSet(cubeState.get("B")[3], cubeState.get("R")[5]));
        edges.add(createColorSet(cubeState.get("B")[5], cubeState.get("L")[3]));

        for (Set<Color> edge : edges) {
            if (edge.size() != 2) return false;
        }

        List<Set<Color>> corners = new ArrayList<>();
        corners.add(createColorSet(cubeState.get("U")[8], cubeState.get("F")[2], cubeState.get("R")[0]));
        corners.add(createColorSet(cubeState.get("U")[6], cubeState.get("F")[0], cubeState.get("L")[2]));
        corners.add(createColorSet(cubeState.get("U")[0], cubeState.get("B")[2], cubeState.get("L")[0]));
        corners.add(createColorSet(cubeState.get("U")[2], cubeState.get("B")[0], cubeState.get("R")[2]));
        corners.add(createColorSet(cubeState.get("D")[2], cubeState.get("F")[8], cubeState.get("R")[6]));
        corners.add(createColorSet(cubeState.get("D")[0], cubeState.get("F")[6], cubeState.get("L")[8]));
        corners.add(createColorSet(cubeState.get("D")[6], cubeState.get("B")[8], cubeState.get("L")[6]));
        corners.add(createColorSet(cubeState.get("D")[8], cubeState.get("B")[6], cubeState.get("R")[8]));

        for (Set<Color> corner : corners) {
            if (corner.size() != 3) return false;
        }

        Color W = Color.WHITE, G = Color.GREEN, O = Color.ORANGE, R = Color.RED, B = Color.BLUE, Y = Color.YELLOW;
        
        return countOccurrences(edges, createColorSet(W, G)) == 1 &&
               countOccurrences(edges, createColorSet(W, O)) == 1 &&
               countOccurrences(edges, createColorSet(W, R)) == 1 &&
               countOccurrences(edges, createColorSet(W, B)) == 1 &&
               countOccurrences(edges, createColorSet(G, O)) == 1 &&
               countOccurrences(edges, createColorSet(G, R)) == 1 &&
               countOccurrences(edges, createColorSet(B, R)) == 1 &&
               countOccurrences(edges, createColorSet(B, O)) == 1 &&
               countOccurrences(edges, createColorSet(Y, G)) == 1 &&
               countOccurrences(edges, createColorSet(Y, B)) == 1 &&
               countOccurrences(edges, createColorSet(Y, O)) == 1 &&
               countOccurrences(edges, createColorSet(Y, R)) == 1 &&
               countOccurrences(corners, createColorSet(W, O, G)) == 1 &&
               countOccurrences(corners, createColorSet(W, R, G)) == 1 &&
               countOccurrences(corners, createColorSet(W, O, B)) == 1 &&
               countOccurrences(corners, createColorSet(W, R, B)) == 1 &&
               countOccurrences(corners, createColorSet(Y, O, G)) == 1 &&
               countOccurrences(corners, createColorSet(Y, R, G)) == 1 &&
               countOccurrences(corners, createColorSet(Y, O, B)) == 1 &&
               countOccurrences(corners, createColorSet(Y, R, B)) == 1;
    }

    private Set<Color> createColorSet(Color... colors) {
        return new HashSet<>(Arrays.asList(colors));
    }

    private int countOccurrences(List<Set<Color>> list, Set<Color> target) {
        int count = 0;
        for (Set<Color> s : list) {
            if (s.equals(target)) count++;
        }
        return count;
    }

    private GridPane createMiniFace(String face) {
        GridPane grid = new GridPane();
        grid.setStyle("-fx-border-color: #444; -fx-border-width: 1px;");
        miniMapGrids.put(face, grid);
        for (int i = 0; i < 9; i++) {
            Region cell = new Region();
            cell.setPrefSize(20, 20);
            cell.setStyle("-fx-border-color: rgba(0,0,0,0.5); -fx-border-width: 0.5px;");
            grid.add(cell, i % 3, i / 3);
        }
        return grid;
    }

    private String getKociembaState() {
        StringBuilder sb = new StringBuilder();
        Map<Color, String> invMap = new HashMap<>();
        // Map colors based on the fixed center pieces
        for (String f : faces) {
            invMap.put(cubeState.get(f)[4], f);
        }
        
        // Kociemba order: U, R, F, D, L, B
        for (String f : new String[]{"U", "R", "F", "D", "L", "B"}) {
            for (Color c : cubeState.get(f)) {
                String face = invMap.get(c);
                if (face == null) return "Error: Unknown color detected. Please reset or fix stickers.";
                sb.append(face);
            }
        }
        return sb.toString();
    }
    
    private void log(String message) {
        Platform.runLater(() -> logArea.appendText("[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message + "\n"));
    }
    
    @Override
    public void stop() {
        if (pyContext != null) {
            try {
                pyContext.close(true);
            } catch (Exception e) {
                // Ignore closure errors on exit
            }
        }
    }
}