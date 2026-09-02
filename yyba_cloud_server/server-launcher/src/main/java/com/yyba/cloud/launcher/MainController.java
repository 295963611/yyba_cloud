package com.yyba.cloud.launcher;

import com.yyba.cloud.dto.ConfigUpdateRequest;
import com.yyba.cloud.dto.RegisterRequest;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.security.AdminGuard;
import com.yyba.cloud.service.ConfigService;
import com.yyba.cloud.service.MdnsRegistrar;
import com.yyba.cloud.service.UserService;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

@Component
@RequiredArgsConstructor
public class MainController {

    @FXML private TextField portField;
    @FXML private TextField storagePathField;
    @FXML private TextField backupPathField;
    @FXML private CheckBox backupEnabledCheck;
    @FXML private Label statusLabel;
    @FXML private Label urlLabel;
    @FXML private javafx.scene.image.ImageView headerLogo;
    @FXML private TextField regUsernameField;
    @FXML private PasswordField regPasswordField;
    @FXML private PasswordField regConfirmField;
    @FXML private TextField regDisplayNameField;
    @FXML private Button startBtn;
    @FXML private Button stopBtn;
    @FXML private TableView<UserRow> userTable;
    @FXML private Button enableUserBtn;
    @FXML private Button disableUserBtn;
    @FXML private Button resetPasswordBtn;

    private final ObservableList<UserRow> userRows = FXCollections.observableArrayList();

    private final ConfigService configService;
    private final UserService userService;
    private final WebServerLifecycleService webServerLifecycleService;
    private final MdnsRegistrar mdnsRegistrar;
    private final TrayManager trayManager;

    private Stage mainStage;
    private Timer statusTimer;

    @FXML
    public void initialize() {
        // FXML 加载时 Spring 尚未完成 UI 初始化，实际加载在 onApplicationStart
    }

    public boolean isTrayInstalled() {
        return trayManager.isInstalled();
    }

    public void bindStage(Stage stage) {
        this.mainStage = stage;
        if (!trayManager.install(stage, this)) {
            Platform.runLater(() -> showInfo("当前系统不支持托盘，关闭窗口将退出程序。"));
        }
    }

    public void onApplicationStart() {
        loadHeaderLogo();
        loadConfigFromService();
        setupUserTable();
        refreshServerStatus();
        refreshUsers();
        startStatusTimer();
    }

    private void loadHeaderLogo() {
        if (headerLogo == null) {
            return;
        }
        var stream = getClass().getResourceAsStream("/images/logo/48x48.png");
        if (stream != null) {
            headerLogo.setImage(new Image(stream));
        }
    }

    @FXML
    public void onSaveConfig() {
        try {
            int port = parsePort();
            ConfigUpdateRequest request = new ConfigUpdateRequest();
            request.setPort(port);
            request.setStorageRootPath(storagePathField.getText().trim());
            request.setBackupEnabled(backupEnabledCheck.isSelected());
            request.setBackupPath(backupPathField.getText().trim());
            configService.update(request);

            if (port != webServerLifecycleService.getPort()) {
                showInfo("配置已保存。端口变更需重启应用后生效。");
            } else {
                showInfo("配置已保存");
            }
        } catch (Exception e) {
            showAlert("保存配置失败: " + e.getMessage());
        }
    }

    @FXML
    public void onStartServer() {
        startServer(true);
    }

    public void startServerFromTray() {
        startServer(false);
    }

    private void startServer(boolean notify) {
        try {
            if (webServerLifecycleService.isRunning()) {
                if (notify) {
                    showInfo("服务已在运行中");
                }
                return;
            }
            webServerLifecycleService.start();
            mdnsRegistrar.register(webServerLifecycleService.getPort());
            if (statusTimer == null) {
                startStatusTimer();
            }
            refreshServerStatus();
            if (notify) {
                showInfo("服务已启动");
            }
        } catch (Exception e) {
            showAlert("启动失败: " + e.getMessage());
            refreshServerStatus();
        }
    }

    @FXML
    public void onStopServer() {
        stopServer(true);
    }

    public void stopServerFromTray() {
        stopServer(false);
    }

    private void stopServer(boolean notify) {
        try {
            stopStatusTimer();
            if (webServerLifecycleService.isRunning()) {
                webServerLifecycleService.stop();
            }
            mdnsRegistrar.unregister();
            updateButtonState(false);
            statusLabel.setText("已停止");
            urlLabel.setText("-");
            syncTrayState();
            if (notify) {
                showInfo("服务已停止");
            }
        } catch (Exception e) {
            showAlert("停止失败: " + e.getMessage());
        }
    }

    public void onCloseRequest() {
        if (trayManager.isInstalled()) {
            minimizeToTray();
        } else {
            requestExit();
        }
    }

    public void minimizeToTray() {
        if (mainStage != null) {
            mainStage.hide();
        }
    }

    public void showMainWindow() {
        if (mainStage == null) {
            return;
        }
        mainStage.show();
        mainStage.toFront();
        mainStage.setIconified(false);
        refreshServerStatus();
    }

    public void requestExit() {
        if (webServerLifecycleService.isRunning()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setHeaderText(null);
            confirm.setContentText("退出将停止家庭云盘服务，家人将无法访问。确定退出？");
            Optional<ButtonType> answer = confirm.showAndWait();
            if (answer.isEmpty() || answer.get() != ButtonType.OK) {
                return;
            }
        }
        performExit();
    }

    private void performExit() {
        try {
            stopServer(false);
        } catch (Exception ignored) {
        }
        stopStatusTimer();
        trayManager.dispose();
        LauncherApp.requestShutdown();
    }

    @FXML
    public void onChooseStorage() {
        chooseDirectory(storagePathField);
    }

    @FXML
    public void onChooseBackup() {
        chooseDirectory(backupPathField);
    }

    @FXML
    public void onRegister() {
        if (!webServerLifecycleService.isRunning()) {
            showAlert("请先启动服务");
            return;
        }
        String username = regUsernameField.getText().trim();
        String password = regPasswordField.getText();
        String confirm = regConfirmField.getText();
        if (username.isEmpty() || password.isEmpty()) {
            showAlert("用户名和密码不能为空");
            return;
        }
        if (!password.equals(confirm)) {
            showAlert("两次密码不一致");
            return;
        }
        if (storagePathField.getText().trim().isEmpty()) {
            showAlert("请先在「服务配置」中设置并保存主存储目录");
            return;
        }

        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setDisplayName(
                regDisplayNameField.getText().trim().isEmpty()
                        ? username : regDisplayNameField.getText().trim());

        // 注册在后台线程执行，避免阻塞 JavaFX 线程并正确展示业务异常
        new Thread(() -> {
            try {
                persistUiConfigQuietly();
                userService.register(request);
                Platform.runLater(() -> {
                    regUsernameField.clear();
                    regPasswordField.clear();
                    regConfirmField.clear();
                    regDisplayNameField.clear();
                    showInfo("账号注册成功，已创建用户目录");
                });
            } catch (Exception e) {
                Platform.runLater(() -> showAlert("注册失败: " + resolveMessage(e)));
            }
        }, "register-user").start();
    }

    @FXML
    public void onRefreshUsers() {
        refreshUsers();
    }

    @FXML
    public void onAddUser() {
        if (storagePathField.getText().trim().isEmpty()) {
            showAlert("请先在「服务配置」中设置并保存主存储目录");
            return;
        }

        Dialog<RegisterRequest> dialog = new Dialog<>();
        dialog.setTitle("添加账号");
        dialog.setHeaderText("为家庭成员创建登录账号（普通成员）");

        ButtonType createBtn = new ButtonType("创建", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createBtn, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 16, 8, 16));

        TextField usernameField = new TextField();
        TextField displayNameField = new TextField();
        PasswordField passwordField = new PasswordField();
        PasswordField confirmField = new PasswordField();

        grid.add(new Label("用户名:"), 0, 0);
        grid.add(usernameField, 1, 0);
        grid.add(new Label("显示名称:"), 0, 1);
        grid.add(displayNameField, 1, 1);
        grid.add(new Label("密码:"), 0, 2);
        grid.add(passwordField, 1, 2);
        grid.add(new Label("确认密码:"), 0, 3);
        grid.add(confirmField, 1, 3);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != createBtn) {
                return null;
            }
            String username = usernameField.getText().trim();
            String password = passwordField.getText();
            String confirm = confirmField.getText();
            if (username.isEmpty() || password.isEmpty()) {
                showAlert("用户名和密码不能为空");
                return null;
            }
            if (!password.equals(confirm)) {
                showAlert("两次密码不一致");
                return null;
            }
            RegisterRequest request = new RegisterRequest();
            request.setUsername(username);
            request.setPassword(password);
            String displayName = displayNameField.getText().trim();
            request.setDisplayName(displayName.isEmpty() ? username : displayName);
            return request;
        });

        Optional<RegisterRequest> result = dialog.showAndWait();
        result.ifPresent(request -> runUserTask(() -> {
            persistUiConfigQuietly();
            userService.createByAdmin(request);
            Platform.runLater(() -> {
                showInfo("账号创建成功");
                refreshUsers();
            });
        }));
    }

    @FXML
    public void onEnableUser() {
        UserRow selected = userTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        setUserEnabled(selected, true);
    }

    @FXML
    public void onDisableUser() {
        UserRow selected = userTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setHeaderText(null);
        confirm.setContentText("确定要禁用用户「" + selected.getUsername() + "」吗？");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }
        setUserEnabled(selected, false);
    }

    @FXML
    public void onResetPassword() {
        UserRow selected = userTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }

        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("重置密码");
        dialog.setHeaderText("为用户「" + selected.getUsername() + "」设置新密码");

        ButtonType okBtn = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 16, 8, 16));

        PasswordField passwordField = new PasswordField();
        PasswordField confirmField = new PasswordField();
        grid.add(new Label("新密码:"), 0, 0);
        grid.add(passwordField, 1, 0);
        grid.add(new Label("确认密码:"), 0, 1);
        grid.add(confirmField, 1, 1);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != okBtn) {
                return null;
            }
            String password = passwordField.getText();
            String confirm = confirmField.getText();
            if (password.isEmpty()) {
                showAlert("密码不能为空");
                return null;
            }
            if (password.length() < 6) {
                showAlert("密码至少 6 个字符");
                return null;
            }
            if (!password.equals(confirm)) {
                showAlert("两次密码不一致");
                return null;
            }
            return password;
        });

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(password -> {
            Long operatorId = resolveLauncherOperatorId();
            if (operatorId == null) {
                showAlert("未找到可用的管理员账号");
                return;
            }
            runUserTask(() -> {
                userService.resetPassword(selected.getId(), password, operatorId);
                Platform.runLater(() -> showInfo("密码已重置"));
            });
        });
    }

    private void setUserEnabled(UserRow row, boolean enabled) {
        Long operatorId = resolveLauncherOperatorId();
        if (operatorId == null) {
            showAlert("未找到可用的管理员账号");
            return;
        }
        runUserTask(() -> {
            userService.setEnabled(row.getId(), enabled, operatorId);
            Platform.runLater(() -> {
                showInfo(enabled ? "账号已启用" : "账号已禁用");
                refreshUsers();
            });
        });
    }

    private void refreshUsers() {
        runUserTask(() -> {
            List<User> users = userService.listAll();
            Platform.runLater(() -> {
                userRows.setAll(users.stream().map(UserRow::new).toList());
            });
        });
    }

    private void setupUserTable() {
        userTable.setItems(userRows);
        userTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<UserRow, String> usernameCol = new TableColumn<>("用户名");
        usernameCol.setCellValueFactory(data -> data.getValue().usernameProperty());

        TableColumn<UserRow, String> displayNameCol = new TableColumn<>("显示名称");
        displayNameCol.setCellValueFactory(data -> data.getValue().displayNameProperty());

        TableColumn<UserRow, String> roleCol = new TableColumn<>("角色");
        roleCol.setCellValueFactory(data -> data.getValue().roleLabelProperty());
        roleCol.setPrefWidth(80);

        TableColumn<UserRow, String> statusCol = new TableColumn<>("状态");
        statusCol.setCellValueFactory(data -> data.getValue().statusProperty());
        statusCol.setPrefWidth(80);

        TableColumn<UserRow, String> createdCol = new TableColumn<>("创建时间");
        createdCol.setCellValueFactory(data -> data.getValue().createdAtProperty());

        userTable.getColumns().setAll(usernameCol, displayNameCol, roleCol, statusCol, createdCol);

        var selection = userTable.getSelectionModel().selectedItemProperty();
        enableUserBtn.disableProperty().bind(Bindings.createBooleanBinding(
                () -> {
                    UserRow row = selection.get();
                    return row == null || row.getEnabled() == 1;
                },
                selection));
        disableUserBtn.disableProperty().bind(Bindings.createBooleanBinding(
                () -> {
                    UserRow row = selection.get();
                    return row == null || row.getEnabled() != 1;
                },
                selection));
        resetPasswordBtn.disableProperty().bind(Bindings.createBooleanBinding(
                () -> selection.get() == null,
                selection));
    }

    private Long resolveLauncherOperatorId() {
        return userService.listAll().stream()
                .filter(u -> u.getEnabled() != null && u.getEnabled() == 1
                        && AdminGuard.isAdmin(u))
                .map(User::getId)
                .findFirst()
                .orElse(null);
    }

    private void runUserTask(Runnable task) {
        new Thread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Platform.runLater(() -> showAlert(resolveMessage(e)));
            }
        }, "launcher-user-task").start();
    }

    private void persistUiConfigQuietly() {
        ConfigUpdateRequest request = new ConfigUpdateRequest();
        request.setPort(parsePort());
        request.setStorageRootPath(storagePathField.getText().trim());
        request.setBackupEnabled(backupEnabledCheck.isSelected());
        request.setBackupPath(backupPathField.getText().trim());
        configService.update(request);
    }

    private String resolveMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message != null && !message.isBlank() ? message : root.getClass().getSimpleName();
    }

    private void loadConfigFromService() {
        try {
            Map<String, String> config = configService.getAll();
            portField.setText(config.getOrDefault(ConfigService.KEY_PORT, "8080"));
            storagePathField.setText(config.getOrDefault(ConfigService.KEY_STORAGE_ROOT, ""));
            backupPathField.setText(config.getOrDefault(ConfigService.KEY_BACKUP_PATH, ""));
            backupEnabledCheck.setSelected(
                    "true".equalsIgnoreCase(config.getOrDefault(ConfigService.KEY_BACKUP_ENABLED, "false")));
        } catch (Exception ignored) {
            portField.setText(String.valueOf(webServerLifecycleService.getPort()));
        }
    }

    private void refreshServerStatus() {
        boolean running = webServerLifecycleService.isRunning();
        updateButtonState(running);
        if (running) {
            statusLabel.setText("运行中");
            int port = webServerLifecycleService.getPort();
            String ip = NetworkUtil.getLanIp();
            urlLabel.setText("http://" + ip + ":" + port);
        } else {
            statusLabel.setText("已停止");
            urlLabel.setText("-");
        }
        syncTrayState();
    }

    private void syncTrayState() {
        boolean running = webServerLifecycleService.isRunning();
        String accessUrl = running ? urlLabel.getText() : null;
        if ("-".equals(accessUrl)) {
            accessUrl = null;
        }
        trayManager.updateState(running, accessUrl);
    }

    private void startStatusTimer() {
        stopStatusTimer();
        statusTimer = new Timer(true);
        statusTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(MainController.this::refreshServerStatus);
            }
        }, 3000, 3000);
    }

    private void stopStatusTimer() {
        if (statusTimer != null) {
            statusTimer.cancel();
            statusTimer = null;
        }
    }

    private void chooseDirectory(TextField field) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择目录");
        var dir = chooser.showDialog(portField.getScene().getWindow());
        if (dir != null) {
            field.setText(dir.getAbsolutePath());
        }
    }

    private int parsePort() {
        String text = portField.getText().trim();
        int port = Integer.parseInt(text);
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("端口范围 1024-65535");
        }
        return port;
    }

    private void updateButtonState(boolean running) {
        startBtn.setDisable(running);
        stopBtn.setDisable(!running);
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
