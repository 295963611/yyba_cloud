package com.yyba.cloud.discovery;

import android.app.Activity;
import android.content.Context;

import com.yyba.cloud.util.PrefsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerDiscoveryManager {

    private static final long DISCOVERY_TIMEOUT_MS = 5000;

    public static final class DiscoveryFlags {
        public final boolean multiServerBackground;

        DiscoveryFlags(boolean multiServerBackground) {
            this.multiServerBackground = multiServerBackground;
        }
    }

    public interface Listener {
        void onDiscoveryFinished(List<NsdServerDiscovery.DiscoveredServer> servers,
                                 String selectedUrl,
                                 DiscoveryFlags flags);
    }

    private static final class PendingDiscovery {
        final Activity activity;
        final boolean manualRediscover;
        final Listener listener;

        PendingDiscovery(Activity activity, boolean manualRediscover, Listener listener) {
            this.activity = activity;
            this.manualRediscover = manualRediscover;
            this.listener = listener;
        }
    }

    private final Context appContext;
    private final PrefsManager prefs;
    private final AtomicBoolean discovering = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<PendingDiscovery> pendingQueue = new CopyOnWriteArrayList<>();
    private NsdServerDiscovery activeDiscovery;

    public ServerDiscoveryManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = new PrefsManager(appContext);
    }

    public void discover(Listener listener) {
        discoverInternal(null, false, listener);
    }

    public void discoverWithPicker(Activity activity, Listener listener) {
        discoverWithPicker(activity, false, listener);
    }

    public void discoverWithPicker(Activity activity, boolean manualRediscover, Listener listener) {
        discoverInternal(activity, manualRediscover, listener);
    }

    private void discoverInternal(Activity activity, boolean manualRediscover, Listener listener) {
        if (!prefs.isAutoDiscover()) {
            notify(listener, Collections.emptyList(), prefs.getServerUrl(), new DiscoveryFlags(false));
            return;
        }
        PendingDiscovery pending = new PendingDiscovery(activity, manualRediscover, listener);
        if (!discovering.compareAndSet(false, true)) {
            pendingQueue.add(pending);
            return;
        }
        runDiscovery(pending);
    }

    private void runDiscovery(PendingDiscovery request) {
        stopActive();
        activeDiscovery = new NsdServerDiscovery(appContext);
        activeDiscovery.discover(DISCOVERY_TIMEOUT_MS, servers -> {
            boolean allowPicker = request.activity != null;
            // pending 回调：弹窗用户确认后再通知外层 listener
            ServerDiscoveryHelper.PendingListener pending = finalUrl -> {
                ServerDiscoveryHelper.ApplyResult finalResult = new ServerDiscoveryHelper.ApplyResult(
                        finalUrl, false);
                finishDiscovery(request, servers, finalResult);
            };
            ServerDiscoveryHelper.ApplyContext ctx = new ServerDiscoveryHelper.ApplyContext(
                    prefs.getServerUrl(),
                    request.manualRediscover,
                    allowPicker && request.activity != null,
                    pending);
            ServerDiscoveryHelper.ApplyResult applyResult = ServerDiscoveryHelper.applyDiscoveryResult(
                    request.activity, prefs, servers, ctx);
            if (applyResult.pendingUserConfirm) {
                // 弹窗进行中；外层 listener 暂缓，等 pending 回调
                return;
            }
            finishDiscovery(request, servers, applyResult);
        });
    }

    private void finishDiscovery(PendingDiscovery request,
                                 List<NsdServerDiscovery.DiscoveredServer> servers,
                                 ServerDiscoveryHelper.ApplyResult applyResult) {
        notify(request.listener, servers, applyResult.selectedUrl,
                new DiscoveryFlags(applyResult.multiServerBackground));

        List<PendingDiscovery> queued = new ArrayList<>(pendingQueue);
        pendingQueue.clear();
        discovering.set(false);

        for (PendingDiscovery next : queued) {
            if (!discovering.compareAndSet(false, true)) {
                pendingQueue.add(next);
                continue;
            }
            runDiscovery(next);
            break;
        }
    }

    public void stop() {
        discovering.set(false);
        pendingQueue.clear();
        stopActive();
    }

    private void stopActive() {
        if (activeDiscovery != null) {
            activeDiscovery.stop();
            activeDiscovery = null;
        }
    }

    private void notify(Listener listener,
                        List<NsdServerDiscovery.DiscoveredServer> servers,
                        String selectedUrl,
                        DiscoveryFlags flags) {
        if (listener != null) {
            listener.onDiscoveryFinished(servers, selectedUrl, flags);
        }
    }
}
