package com.yyba.cloud.discovery;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.util.PrefsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 冷启动连接编排：先 health 再 mDNS，整进程最多一次自动发现。
 */
public class ServerConnectCoordinator {

    public enum ConnectSource {
        SAVED,
        DISCOVERED,
        NONE
    }

    public static final class ConnectResult {
        public final String url;
        public final ConnectSource source;
        public final boolean healthOk;
        public final boolean savedAddressUnreachable;
        public final List<NsdServerDiscovery.DiscoveredServer> servers;
        public final boolean multiServerBackground;
        public final boolean mdnsPerformed;

        public ConnectResult(String url,
                             ConnectSource source,
                             boolean healthOk,
                             boolean savedAddressUnreachable,
                             List<NsdServerDiscovery.DiscoveredServer> servers,
                             boolean multiServerBackground,
                             boolean mdnsPerformed) {
            this.url = url != null ? url : "";
            this.source = source;
            this.healthOk = healthOk;
            this.savedAddressUnreachable = savedAddressUnreachable;
            this.servers = servers != null ? servers : Collections.emptyList();
            this.multiServerBackground = multiServerBackground;
            this.mdnsPerformed = mdnsPerformed;
        }
    }

    public interface Callback {
        void onComplete(ConnectResult result);
    }

    private final Context appContext;
    private final PrefsManager prefs;
    private final ServerDiscoveryManager discoveryManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "server-connect");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean coldStartDone = new AtomicBoolean(false);
    private final AtomicBoolean coldStartInProgress = new AtomicBoolean(false);
    private ConnectResult cachedColdStartResult;
    private final List<Callback> pendingColdStartCallbacks = new ArrayList<>();

    public ServerConnectCoordinator(Context context, ServerDiscoveryManager discoveryManager) {
        this.appContext = context.getApplicationContext();
        this.prefs = new PrefsManager(appContext);
        this.discoveryManager = discoveryManager;
    }

    public void connectOnColdStart(Activity activity, Callback callback) {
        if (coldStartDone.get()) {
            deliverOnMain(callback, cachedColdStartResult);
            return;
        }
        synchronized (pendingColdStartCallbacks) {
            if (callback != null) {
                pendingColdStartCallbacks.add(callback);
            }
            if (!coldStartInProgress.compareAndSet(false, true)) {
                return;
            }
        }
        connectExecutor.execute(() -> runColdStart(activity));
    }

    public void connectManualRediscover(Activity activity, Callback callback) {
        if (!prefs.isAutoDiscover()) {
            connectExecutor.execute(() -> {
                ConnectResult result = buildResult(
                        prefs.getServerUrl(),
                        ConnectSource.SAVED,
                        HealthChecker.probe(prefs.getServerUrl()),
                        false,
                        Collections.emptyList(),
                        false,
                        false);
                deliverOnMain(callback, result);
            });
            return;
        }
        discoveryManager.discoverWithPicker(activity, true, (servers, url, flags) ->
                connectExecutor.execute(() -> {
                    ConnectResult result = buildResult(
                            url,
                            servers.isEmpty() ? ConnectSource.SAVED : ConnectSource.DISCOVERED,
                            !url.isEmpty() && HealthChecker.probe(url),
                            false,
                            servers,
                            flags.multiServerBackground,
                            true);
                    deliverOnMain(callback, result);
                }));
    }

    public ConnectResult getCachedColdStartResult() {
        return cachedColdStartResult;
    }

    public String getResolvedUrl() {
        ConnectResult cached = cachedColdStartResult;
        if (cached != null && !cached.url.isEmpty()) {
            return cached.url;
        }
        return prefs.getServerUrl();
    }

    public boolean validateToken(PrefsManager targetPrefs) {
        if (targetPrefs.getToken() == null) {
            return false;
        }
        ApiClient.TokenValidation validation = new ApiClient(targetPrefs).validateToken();
        return validation == ApiClient.TokenValidation.VALID;
    }

    private void runColdStart(Activity activity) {
        String savedUrl = prefs.getServerUrl();
        boolean hasSaved = !savedUrl.isEmpty();

        if (hasSaved && HealthChecker.probe(savedUrl)) {
            finishColdStart(buildResult(savedUrl, ConnectSource.SAVED, true, false,
                    Collections.emptyList(), false, false));
            return;
        }

        boolean savedUnreachable = hasSaved;
        if (!prefs.isAutoDiscover()) {
            finishColdStart(buildResult(savedUrl, ConnectSource.SAVED, false, savedUnreachable,
                    Collections.emptyList(), false, false));
            return;
        }

        mainHandler.post(() -> startDiscovery(activity, savedUnreachable));
    }

    private void startDiscovery(Activity activity, boolean savedAddressUnreachable) {
        discoveryManager.discoverWithPicker(activity, false, (servers, url, flags) ->
                connectExecutor.execute(() -> {
                    ConnectSource source = servers.isEmpty() ? ConnectSource.SAVED : ConnectSource.DISCOVERED;
                    String resolvedUrl = url;
                    if (resolvedUrl.isEmpty()) {
                        resolvedUrl = prefs.getServerUrl();
                        source = ConnectSource.NONE;
                    }
                    boolean healthOk = !resolvedUrl.isEmpty() && HealthChecker.probe(resolvedUrl);
                    finishColdStart(buildResult(resolvedUrl, source, healthOk, savedAddressUnreachable,
                            servers, flags.multiServerBackground, true));
                }));
    }

    private void finishColdStart(ConnectResult result) {
        cachedColdStartResult = result;
        coldStartDone.set(true);
        coldStartInProgress.set(false);
        List<Callback> callbacks;
        synchronized (pendingColdStartCallbacks) {
            callbacks = new ArrayList<>(pendingColdStartCallbacks);
            pendingColdStartCallbacks.clear();
        }
        mainHandler.post(() -> {
            for (Callback cb : callbacks) {
                deliverOnMain(cb, result);
            }
        });
    }

    private ConnectResult buildResult(String url,
                                      ConnectSource source,
                                      boolean healthOk,
                                      boolean savedAddressUnreachable,
                                      List<NsdServerDiscovery.DiscoveredServer> servers,
                                      boolean multiServerBackground,
                                      boolean mdnsPerformed) {
        return new ConnectResult(url, source, healthOk, savedAddressUnreachable,
                servers, multiServerBackground, mdnsPerformed);
    }

    private void deliverOnMain(Callback callback, ConnectResult result) {
        if (callback == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback.onComplete(result);
        } else {
            mainHandler.post(() -> callback.onComplete(result));
        }
    }
}
