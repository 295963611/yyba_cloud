package com.yyba.cloud.sync;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** 前台同步进度：Service 发布，首页 UI 订阅 */
public final class SyncProgressBus {

    public enum Event {
        STARTED,
        PROGRESS,
        COMPLETED,
        CANCELLED,
        ERROR
    }

    public static final class Snapshot {
        public final boolean running;
        public final int current;
        public final int total;
        public final String fileName;

        Snapshot(boolean running, int current, int total, String fileName) {
            this.running = running;
            this.current = current;
            this.total = total;
            this.fileName = fileName != null ? fileName : "";
        }

        static Snapshot idle() {
            return new Snapshot(false, 0, 0, "");
        }
    }

    public interface Listener {
        void onSyncEvent(Event event, Snapshot snapshot, int synced, int skipped, int failed,
                         String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile Snapshot snapshot = Snapshot.idle();

    private SyncProgressBus() {
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static Snapshot current() {
        return snapshot;
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static void notifyStarted(int total) {
        RUNNING.set(true);
        snapshot = new Snapshot(true, 0, total, "");
        dispatch(Event.STARTED, snapshot, 0, 0, 0, null);
    }

    static void notifyProgress(int current, int total, String fileName) {
        snapshot = new Snapshot(true, current, total, fileName);
        dispatch(Event.PROGRESS, snapshot, 0, 0, 0, null);
    }

    static void notifyCompleted(int synced, int skipped, int failed, String message) {
        RUNNING.set(false);
        snapshot = Snapshot.idle();
        dispatch(Event.COMPLETED, snapshot, synced, skipped, failed, message);
    }

    static void notifyCancelled(int synced, int skipped, int failed) {
        RUNNING.set(false);
        snapshot = Snapshot.idle();
        dispatch(Event.CANCELLED, snapshot, synced, skipped, failed, null);
    }

    static void notifyError(String message) {
        RUNNING.set(false);
        snapshot = Snapshot.idle();
        dispatch(Event.ERROR, snapshot, 0, 0, 0, message);
    }

    private static void dispatch(Event event, Snapshot snap, int synced, int skipped, int failed,
                                 String message) {
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onSyncEvent(event, snap, synced, skipped, failed, message);
            }
        });
    }
}
