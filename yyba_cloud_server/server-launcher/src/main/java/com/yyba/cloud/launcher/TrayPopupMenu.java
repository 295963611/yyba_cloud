package com.yyba.cloud.launcher;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.List;

/**
 * 托盘右键菜单：无边框 JavaFX 窗口 + 按钮模拟菜单项。
 */
final class TrayPopupMenu {

    private static final double MENU_WIDTH = 196;
    private static final String APP_VERSION = "1.0.0";
    private static final String BUTTON_STYLE = """
            -fx-background-color: transparent;
            -fx-text-fill: #1f2937;
            -fx-font-size: 13px;
            -fx-alignment: CENTER_LEFT;
            -fx-padding: 8 14 8 14;
            -fx-cursor: hand;
            """;
    private static final String BUTTON_HOVER_STYLE = BUTTON_STYLE + """
            -fx-background-color: #eef2ff;
            """;

    private Runnable onOpen = () -> {};
    private Runnable onStart = () -> {};
    private Runnable onStop = () -> {};
    private Runnable onExit = () -> {};

    private Stage currentStage;

    TrayPopupMenu() {
    }

    void setActions(Runnable onOpen, Runnable onStart, Runnable onStop, Runnable onExit) {
        this.onOpen = onOpen != null ? onOpen : () -> {};
        this.onStart = onStart != null ? onStart : () -> {};
        this.onStop = onStop != null ? onStop : () -> {};
        this.onExit = onExit != null ? onExit : () -> {};
    }

    void show(double screenX, double screenY, boolean serviceRunning) {
        hide();
        Stage stage = buildStage(serviceRunning);
        currentStage = stage;

        System.out.println("[TrayPopupMenu] AWT click=(" + screenX + "," + screenY + ")");
        double[] fx = convertAwtToFx(screenX, screenY);
        double fxX = fx[0];
        double fxY = fx[1];
        System.out.println("[TrayPopupMenu] FX coord=(" + fxX + "," + fxY + ")");

        stage.setOpacity(0);
        stage.show();

        Platform.runLater(() -> {
            double width = stage.getWidth();
            double height = stage.getHeight();
            if (width <= 0) width = MENU_WIDTH;
            if (height <= 0) height = 180;
            stage.setWidth(width);
            stage.setHeight(height);

            Screen screen = findScreen(fxX, fxY);
            Rectangle2D bounds = screen.getVisualBounds();
            System.out.println("[TrayPopupMenu] size=" + width + "x" + height
                    + " screenBounds=" + bounds);

            double x = fxX - width + 8;
            double y = fxY - height - 4;

            if (x + width > bounds.getMaxX()) {
                x = bounds.getMaxX() - width;
            }
            if (x < bounds.getMinX()) {
                x = bounds.getMinX();
            }
            if (y < bounds.getMinY()) {
                y = fxY + 4;
                if (y + height > bounds.getMaxY()) {
                    y = bounds.getMaxY() - height;
                }
            }
            if (y + height > bounds.getMaxY()) {
                y = bounds.getMaxY() - height;
            }
            if (y < bounds.getMinY()) {
                y = bounds.getMinY();
            }

            System.out.println("[TrayPopupMenu] final pos=(" + x + "," + y + ")");
            stage.setX(x);
            stage.setY(y);
            stage.setOpacity(1);
            stage.toFront();
            stage.requestFocus();
        });
    }

    void updateRunningState(boolean serviceRunning) {
    }

    void hide() {
        if (currentStage != null) {
            if (currentStage.isShowing()) {
                currentStage.hide();
            }
            currentStage = null;
        }
    }

    boolean isShowing() {
        return currentStage != null && currentStage.isShowing();
    }

    private Stage buildStage(boolean serviceRunning) {
        Stage stage = new Stage(StageStyle.UNDECORATED);
        stage.setAlwaysOnTop(true);

        Button openBtn = createMenuButton("打开管理界面");
        Button startBtn = createMenuButton("启动服务");
        Button stopBtn = createMenuButton("停止服务");
        Button aboutBtn = createMenuButton("关于");
        Button exitBtn = createMenuButton("退出");
        startBtn.setDisable(serviceRunning);
        stopBtn.setDisable(!serviceRunning);

        VBox root = new VBox(2);
        root.setFillWidth(true);
        root.setPadding(new Insets(6, 0, 6, 0));
        root.setStyle("-fx-background-color: white; -fx-border-color: #d1d5db; -fx-border-width: 1; "
                + "-fx-background-radius: 6; -fx-border-radius: 6; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 10, 0, 0, 2);");

        openBtn.setOnAction(e -> { hide(); onOpen.run(); });
        startBtn.setOnAction(e -> { hide(); onStart.run(); });
        stopBtn.setOnAction(e -> { hide(); onStop.run(); });
        aboutBtn.setOnAction(e -> { hide(); showAbout(); });
        exitBtn.setOnAction(e -> { hide(); onExit.run(); });

        root.getChildren().addAll(
                openBtn,
                new Separator(),
                startBtn,
                stopBtn,
                new Separator(),
                aboutBtn,
                exitBtn);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused && stage.isShowing()) {
                stage.hide();
            }
        });
        return stage;
    }

    private static double[] convertAwtToFx(double awtX, double awtY) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] devices = ge.getScreenDevices();
        List<Screen> fxScreens = Screen.getScreens();
        for (int i = 0; i < devices.length; i++) {
            Rectangle awtBounds = devices[i].getDefaultConfiguration().getBounds();
            if (awtBounds.contains(awtX, awtY) && i < fxScreens.size()) {
                Rectangle2D fxBounds = fxScreens.get(i).getBounds();
                double scaleX = fxBounds.getWidth() / awtBounds.width;
                double scaleY = fxBounds.getHeight() / awtBounds.height;
                double fxX = fxBounds.getMinX() + (awtX - awtBounds.x) * scaleX;
                double fxY = fxBounds.getMinY() + (awtY - awtBounds.y) * scaleY;
                return new double[]{fxX, fxY};
            }
        }
        Dimension awtScreen = Toolkit.getDefaultToolkit().getScreenSize();
        Rectangle2D fxBounds = Screen.getPrimary().getBounds();
        double scaleX = fxBounds.getWidth() / awtScreen.width;
        double scaleY = fxBounds.getHeight() / awtScreen.height;
        return new double[]{awtX * scaleX, awtY * scaleY};
    }

    private Screen findScreen(double x, double y) {
        for (Screen s : Screen.getScreens()) {
            if (s.getBounds().contains(x, y)) {
                return s;
            }
        }
        Screen best = Screen.getPrimary();
        double bestDist = Double.MAX_VALUE;
        for (Screen s : Screen.getScreens()) {
            Rectangle2D b = s.getBounds();
            double cx = b.getMinX() + b.getWidth() / 2;
            double cy = b.getMinY() + b.getHeight() / 2;
            double d = Math.hypot(cx - x, cy - y);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    private static void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("关于");
        alert.setHeaderText("yyba网盘");
        alert.setContentText("版本 " + APP_VERSION);
        alert.showAndWait();
    }

    private static Button createMenuButton(String text) {
        Button button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setStyle(BUTTON_STYLE);
        button.setOnMouseEntered(e -> button.setStyle(BUTTON_HOVER_STYLE));
        button.setOnMouseExited(e -> button.setStyle(BUTTON_STYLE));
        return button;
    }
}
