package com.yyba.cloud.launcher;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统托盘：AWT {@link TrayIcon} 显示图标，右键弹出 JavaFX 无边框菜单。
 */
@Component
public class TrayManager {

    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);

    private final AtomicBoolean installed = new AtomicBoolean(false);

    private TrayPopupMenu popupMenu;
    private TrayIcon trayIcon;
    private boolean serviceRunning;

    public boolean isInstalled() {
        return installed.get();
    }

    public boolean install(Stage stage, MainController controller) {
        if (installed.get()) {
            return true;
        }
        if (!SystemTray.isSupported()) {
            log.warn("当前系统不支持 SystemTray，无法最小化到托盘");
            return false;
        }

        Image image = loadTrayImage();
        if (image == null) {
            log.warn("托盘图标加载失败，无法安装托盘");
            return false;
        }

        this.serviceRunning = false;
        initPopupMenuOnFxThread(controller);

        try {
            EventQueue.invokeAndWait(() -> installTrayIconOnAwt(image, controller));
        } catch (Exception e) {
            log.warn("在 AWT 线程安装托盘失败", e);
            return false;
        }
        if (installed.get()) {
            log.info("系统托盘已安装");
        }
        return installed.get();
    }

    private void initPopupMenuOnFxThread(MainController controller) {
        Runnable init = () -> {
            try {
                popupMenu = new TrayPopupMenu();
                popupMenu.setActions(
                        controller::showMainWindow,
                        controller::startServerFromTray,
                        controller::stopServerFromTray,
                        controller::requestExit);
            } catch (Exception e) {
                popupMenu = null;
                log.warn("托盘 JavaFX 菜单初始化失败，托盘图标仍可用", e);
            }
        };
        if (Platform.isFxApplicationThread()) {
            init.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                init.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            popupMenu = null;
            log.warn("等待托盘 JavaFX 菜单初始化被中断", e);
        }
    }

    private void installTrayIconOnAwt(Image image, MainController controller) {
        // 不使用 AWT PopupMenu，右键由 MouseListener 触发 JavaFX 菜单
        trayIcon = new TrayIcon(image, TrayTexts.TOOLTIP_STOPPED);
        trayIcon.setImageAutoSize(true);
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int x = e.getXOnScreen();
                    int y = e.getYOnScreen();
                    Platform.runLater(() -> {
                        if (popupMenu != null) {
                            popupMenu.show(x, y, serviceRunning);
                        }
                    });
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() >= 2) {
                    Platform.runLater(controller::showMainWindow);
                }
            }
        });

        try {
            SystemTray.getSystemTray().add(trayIcon);
            installed.set(true);
        } catch (AWTException e) {
            trayIcon = null;
            log.warn("SystemTray.add 失败", e);
        }
    }

    public void updateState(boolean running, String accessUrl) {
        serviceRunning = running;
        if (!installed.get() || trayIcon == null) {
            return;
        }
        String tooltip = buildTooltip(running, accessUrl);
        EventQueue.invokeLater(() -> trayIcon.setToolTip(tooltip));
        if (popupMenu != null) {
            Platform.runLater(() -> popupMenu.updateRunningState(running));
        }
    }

    public void dispose() {
        if (popupMenu != null) {
            Platform.runLater(popupMenu::hide);
        }
        if (!installed.get()) {
            return;
        }
        Runnable remove = () -> {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
                trayIcon = null;
            }
            installed.set(false);
        };
        if (EventQueue.isDispatchThread()) {
            remove.run();
        } else {
            try {
                EventQueue.invokeAndWait(remove);
            } catch (Exception ignored) {
                EventQueue.invokeLater(remove);
            }
        }
    }

    private static String buildTooltip(boolean running, String accessUrl) {
        if (running && accessUrl != null && !accessUrl.isBlank()) {
            return TrayTexts.TOOLTIP_RUNNING + " (" + accessUrl + ")";
        }
        if (running) {
            return TrayTexts.TOOLTIP_RUNNING;
        }
        return TrayTexts.TOOLTIP_STOPPED;
    }

    private Image loadTrayImage() {
        try (InputStream stream = getClass().getResourceAsStream("/images/logo/48x48.png")) {
            if (stream == null) {
                log.warn("未找到托盘图标资源 /images/logo/48x48.png，使用内置占位图");
                return createFallbackTrayImage();
            }
            BufferedImage source = ImageIO.read(stream);
            if (source == null) {
                return createFallbackTrayImage();
            }
            return toTrayIconImage(source, 16, 16);
        } catch (Exception e) {
            log.warn("读取托盘图标失败，使用内置占位图", e);
            return createFallbackTrayImage();
        }
    }

    private static Image createFallbackTrayImage() {
        BufferedImage buffered = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buffered.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(79, 70, 229));
        g.fillRoundRect(1, 1, 14, 14, 4, 4);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.drawString("Y", 4, 12);
        g.dispose();
        return buffered;
    }

    private static Image toTrayIconImage(Image source, int width, int height) {
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buffered.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return buffered;
    }
}
