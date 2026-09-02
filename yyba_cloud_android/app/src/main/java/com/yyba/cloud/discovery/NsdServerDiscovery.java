package com.yyba.cloud.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import com.yyba.cloud.util.DiscoveryCrypto;
import com.yyba.cloud.util.ServerAddress;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class NsdServerDiscovery {

    public static final String SERVICE_TYPE = "_yybacloud._tcp.";
    public static final String TXT_ENDPOINT = "ep";
    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private static final long RESOLVE_GRACE_MS = 1500;

    private final AtomicInteger pendingResolves = new AtomicInteger(0);

    public NsdServerDiscovery(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public interface Callback {
        void onComplete(List<DiscoveredServer> servers);
    }

    public static class DiscoveredServer {
        public final String name;
        public final String host;
        public final int port;
        public final String url;
        /** mDNS TXT ep 密文，落盘时原样保存 */
        public final String endpointCipher;

        public DiscoveredServer(String name, String host, int port, String url, String endpointCipher) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.url = url;
            this.endpointCipher = endpointCipher;
        }
    }

    public void discover(long timeoutMs, Callback callback) {
        stopped.set(false);
        nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            deliver(callback, Collections.emptyList());
            return;
        }

        List<DiscoveredServer> verified = new CopyOnWriteArrayList<>();

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) { }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (stopped.get()) return;
                resolveService(serviceInfo, verified);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) { }

            @Override
            public void onDiscoveryStopped(String serviceType) { }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                stopDiscoveryQuietly();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) { }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            deliver(callback, Collections.emptyList());
            return;
        }

        long waitMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        new Thread(() -> {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            stopDiscoveryQuietly();
            long graceEnd = System.currentTimeMillis() + RESOLVE_GRACE_MS;
            while (pendingResolves.get() > 0 && System.currentTimeMillis() < graceEnd) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            stopped.set(true);
            List<DiscoveredServer> result = new ArrayList<>(verified);
            deliver(callback, result);
        }, "nsd-discovery-timeout").start();
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        stopDiscoveryQuietly();
    }

    private void resolveService(NsdServiceInfo serviceInfo, List<DiscoveredServer> verified) {
        if (nsdManager == null || stopped.get()) return;
        pendingResolves.incrementAndGet();
        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                pendingResolves.decrementAndGet();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo resolved) {
                try {
                    if (stopped.get()) return;
                    ParsedEndpoint endpoint = parseEncryptedEndpoint(resolved);
                    if (endpoint == null || !HealthChecker.probe(endpoint.url, 3000)) {
                        return;
                    }
                    String name = resolved.getServiceName() != null
                            ? resolved.getServiceName() : endpoint.display;
                    DiscoveredServer server = new DiscoveredServer(
                            name,
                            endpoint.host,
                            endpoint.port,
                            endpoint.url,
                            endpoint.endpointCipher);
                    if (!containsUrl(verified, endpoint.url)) {
                        verified.add(server);
                    }
                } finally {
                    pendingResolves.decrementAndGet();
                }
            }
        });
    }

    private static final class ParsedEndpoint {
        final String host;
        final int port;
        final String url;
        final String display;
        final String endpointCipher;

        ParsedEndpoint(String host, int port, String url, String display, String endpointCipher) {
            this.host = host;
            this.port = port;
            this.url = url;
            this.display = display;
            this.endpointCipher = endpointCipher;
        }
    }

    private ParsedEndpoint parseEncryptedEndpoint(NsdServiceInfo info) {
        String ep = readTxtAttribute(info, TXT_ENDPOINT);
        if (ep == null || ep.isEmpty()) {
            return null;
        }
        DiscoveryCrypto.Endpoint decrypted = DiscoveryCrypto.decryptEndpoint(ep);
        if (decrypted == null || decrypted.host.isEmpty()) {
            return null;
        }
        String url = ServerAddress.buildUrl(decrypted.host, String.valueOf(decrypted.port));
        String display = ServerAddress.formatDisplay(decrypted.host, decrypted.port);
        return new ParsedEndpoint(decrypted.host, decrypted.port, url, display, ep);
    }

    private static String readTxtAttribute(NsdServiceInfo info, String key) {
        Map<String, byte[]> attrs = info.getAttributes();
        if (attrs == null) {
            return null;
        }
        byte[] value = attrs.get(key);
        if (value == null || value.length == 0) {
            return null;
        }
        return new String(value, StandardCharsets.UTF_8).trim();
    }

    private boolean containsUrl(List<DiscoveredServer> servers, String url) {
        for (DiscoveredServer server : servers) {
            if (url.equals(server.url)) {
                return true;
            }
        }
        return false;
    }

    private void stopDiscoveryQuietly() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
            }
        }
    }

    private void deliver(Callback callback, List<DiscoveredServer> servers) {
        mainHandler.post(() -> callback.onComplete(servers));
    }
}
