package com.yyba.cloud.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * JavaFX 界面，由 {@link LauncherApplication} 在 Spring Boot 启动后拉起。
 */
public class LauncherApp extends Application {

    private static LauncherApp instance;
    private MainController mainController;

    public static void requestShutdown() {
        if (instance != null) {
            instance.shutdown();
        } else {
            LauncherApplication.shutdown();
            Platform.exit();
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        instance = this;
        Platform.setImplicitExit(false);

        ConfigurableApplicationContext context = LauncherApplication.getSpringContext();
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/main-view.fxml"));
        loader.setControllerFactory(clazz -> context.getBean(clazz));
        Scene scene = new Scene(loader.load(), 720, 560);
        mainController = loader.getController();
        mainController.bindStage(stage);

        mainController.onApplicationStart();
        stage.setTitle("悠悠爸家庭云盘 - 服务管理");
        stage.setScene(scene);
        loadStageIcon(stage);

        stage.setOnCloseRequest(e -> {
            e.consume();
            mainController.onCloseRequest();
        });

        stage.iconifiedProperty().addListener((obs, wasIconified, iconified) -> {
            if (iconified && mainController.isTrayInstalled()) {
                Platform.runLater(() -> {
                    stage.setIconified(false);
                    mainController.minimizeToTray();
                });
            }
        });

        stage.show();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        LauncherApplication.shutdown();
        Platform.exit();
    }

    private void loadStageIcon(Stage stage) {
        var stream = getClass().getResourceAsStream("/images/logo/48x48.png");
        if (stream != null) {
            stage.getIcons().add(new Image(stream));
        }
    }
}
