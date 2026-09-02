package com.yyba.cloud.launcher;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 托盘右键菜单：无边框 JavaFX 窗口 + 按钮模拟菜单项。
 */
final class TrayPopupMenu {

    private static final double MENU_WIDTH = 196;
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

    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final Button openBtn = createMenuButton("打开管理界面");
    private final Button startBtn = createMenuButton("启动服务");
    private final Button stopBtn = createMenuButton("停止服务");
    private final Button exitBtn = createMenuButton("退出");

    private Runnable onOpen = () -> {};
    private Runnable onStart = () -> {};
    private Runnable onStop = () -> {};
    private Runnable onExit = () -> {};

    TrayPopupMenu() {
        VBox root = new VBox(2);
        root.setFillWidth(true);
        root.setPadding(new Insets(6, 0, 6, 0));
        root.setStyle("-fx-background-color: white; -fx-border-color: #d1d5db; -fx-border-width: 1; "
                + "-fx-background-radius: 6; -fx-border-radius: 6; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 10, 0, 0, 2);");

        openBtn.setOnAction(e -> runAndHide(onOpen));
        startBtn.setOnAction(e -> runAndHide(onStart));
        stopBtn.setOnAction(e -> runAndHide(onStop));
        exitBtn.setOnAction(e -> runAndHide(onExit));

        root.getChildren().addAll(
                openBtn,
                new Separator(),
                startBtn,
                stopBtn,
                new Separator(),
                exitBtn);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused && stage.isShowing()) {
                stage.hide();
            }
        });
    }

    void setActions(Runnable onOpen, Runnable onStart, Runnable onStop, Runnable onExit) {
        this.onOpen = onOpen != null ? onOpen : () -> {};
        this.onStart = onStart != null ? onStart : () -> {};
        this.onStop = onStop != null ? onStop : () -> {};
        this.onExit = onExit != null ? onExit : () -> {};
    }

    void show(double screenX, double screenY, boolean serviceRunning) {
        applyRunningState(serviceRunning);
        if (stage.isShowing()) {
            stage.hide();
        }
        stage.sizeToScene();
        double width = stage.getWidth() > 0 ? stage.getWidth() : MENU_WIDTH;
        double height = stage.getHeight() > 0 ? stage.getHeight() : 180;
        double x = screenX - width + 8;
        double y = screenY - height - 4;
        if (x < 0) {
            x = 4;
        }
        if (y < 0) {
            y = 4;
        }
        stage.setX(x);
        stage.setY(y);
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    void updateRunningState(boolean serviceRunning) {
        if (stage.isShowing()) {
            applyRunningState(serviceRunning);
        }
    }

    void hide() {
        if (stage.isShowing()) {
            stage.hide();
        }
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    private void applyRunningState(boolean serviceRunning) {
        startBtn.setDisable(serviceRunning);
        stopBtn.setDisable(!serviceRunning);
    }

    private void runAndHide(Runnable action) {
        stage.hide();
        action.run();
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
