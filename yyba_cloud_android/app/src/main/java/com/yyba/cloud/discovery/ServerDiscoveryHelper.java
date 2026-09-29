package com.yyba.cloud.discovery;

import android.app.Activity;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.yyba.cloud.R;
import com.yyba.cloud.util.PrefsManager;
import com.yyba.cloud.util.ServerAddress;

import java.util.ArrayList;
import java.util.List;

public final class ServerDiscoveryHelper {

    public interface UrlCallback {
        void onUrlSelected(String url);
    }

    public static final class ApplyContext {
        public final String savedUrl;
        public final boolean manualRediscover;
        public final boolean allowPicker;
        /** 弹窗确认完成后的回调；非 null 时由 helper 在弹窗按钮中触发 */
        public final PendingListener pendingListener;

        public ApplyContext(String savedUrl, boolean manualRediscover, boolean allowPicker) {
            this(savedUrl, manualRediscover, allowPicker, null);
        }

        public ApplyContext(String savedUrl, boolean manualRediscover, boolean allowPicker,
                            PendingListener pendingListener) {
            this.savedUrl = savedUrl != null ? savedUrl : "";
            this.manualRediscover = manualRediscover;
            this.allowPicker = allowPicker;
            this.pendingListener = pendingListener;
        }
    }

    public static final class ApplyResult {
        public final String selectedUrl;
        public final boolean multiServerBackground;
        /** 弹窗正在等待用户确认；外层应暂缓通知 listener，由 setPendingListener 回调真正完成 */
        public final boolean pendingUserConfirm;

        ApplyResult(String selectedUrl, boolean multiServerBackground, boolean pendingUserConfirm) {
            this.selectedUrl = selectedUrl != null ? selectedUrl : "";
            this.multiServerBackground = multiServerBackground;
            this.pendingUserConfirm = pendingUserConfirm;
        }

        ApplyResult(String selectedUrl, boolean multiServerBackground) {
            this(selectedUrl, multiServerBackground, false);
        }
    }

    /** 弹窗回调：用户最终选择 URL 后通知外层 listener */
    public interface PendingListener {
        void onUserConfirmed(String finalUrl);
    }

    private ServerDiscoveryHelper() {
    }

    public static ApplyResult applyDiscoveryResult(Activity activity,
                                                   PrefsManager prefs,
                                                   List<NsdServerDiscovery.DiscoveredServer> servers,
                                                   ApplyContext ctx) {
        if (servers == null || servers.isEmpty()) {
            return new ApplyResult(ctx.savedUrl, false);
        }
        if (servers.size() == 1) {
            NsdServerDiscovery.DiscoveredServer server = servers.get(0);
            if (ctx.savedUrl.isEmpty()) {
                if (applyServer(prefs, server)) {
                    return new ApplyResult(prefs.getServerUrl(), false);
                }
                return new ApplyResult("", false);
            }
            if (sameEndpoint(ctx.savedUrl, server.url)) {
                return new ApplyResult(ctx.savedUrl, false);
            }
            if (ctx.allowPicker && activity != null && !activity.isFinishing()) {
                // 弹窗异步；同步返回 pending 标志，外层暂缓通知 listener，
                // 由弹窗按钮回调真正触发 PendingListener.onUserConfirmed(finalUrl)。
                confirmSwitch(activity, prefs, server, ctx.savedUrl, ctx.pendingListener);
                return new ApplyResult(ctx.savedUrl, false, true);
            }
            return new ApplyResult(ctx.savedUrl, false);
        }
        if (ctx.allowPicker && activity != null && !activity.isFinishing()) {
            showPicker(activity, prefs, servers, ctx.pendingListener);
            return new ApplyResult(prefs.getServerUrl(), false, true);
        }
        if (activity != null && !activity.isFinishing()) {
            Toast.makeText(activity, R.string.discover_multi_go_settings, Toast.LENGTH_LONG).show();
        }
        return new ApplyResult(ctx.savedUrl, true);
    }

    /** 兼容旧调用：自动发现完成后回调 URL */
    public static void applyDiscoveryResult(Activity activity,
                                            PrefsManager prefs,
                                            List<NsdServerDiscovery.DiscoveredServer> servers,
                                            boolean allowPicker,
                                            UrlCallback callback) {
        ApplyContext ctx = new ApplyContext(prefs.getServerUrl(), false, allowPicker);
        ApplyResult result = applyDiscoveryResult(activity, prefs, servers, ctx);
        if (callback != null) {
            callback.onUrlSelected(result.selectedUrl);
        }
    }

    private static boolean sameEndpoint(String savedUrl, String discoveredUrl) {
        if (savedUrl == null || discoveredUrl == null) {
            return false;
        }
        return savedUrl.equals(discoveredUrl)
                || ServerAddress.parse(savedUrl).host.equals(ServerAddress.parse(discoveredUrl).host)
                && ServerAddress.parse(savedUrl).port == ServerAddress.parse(discoveredUrl).port;
    }

    private static void confirmSwitch(Activity activity,
                                      PrefsManager prefs,
                                      NsdServerDiscovery.DiscoveredServer server,
                                      String savedUrl,
                                      PendingListener pendingListener) {
        String message = activity.getString(R.string.discover_switch_confirm,
                ServerAddress.formatDisplay(server.host, server.port),
                ServerAddress.formatDisplay(
                        ServerAddress.parse(savedUrl).host,
                        ServerAddress.parse(savedUrl).port));
        new AlertDialog.Builder(activity)
                .setTitle(R.string.discover_switch_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String finalUrl = savedUrl;
                    if (applyServer(prefs, server)) {
                        Toast.makeText(activity, prefs.getServerDisplay(), Toast.LENGTH_SHORT).show();
                        finalUrl = prefs.getServerUrl();
                    } else {
                        Toast.makeText(activity, R.string.discovery_endpoint_save_failed, Toast.LENGTH_LONG).show();
                    }
                    if (pendingListener != null) {
                        pendingListener.onUserConfirmed(finalUrl);
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (pendingListener != null) {
                        pendingListener.onUserConfirmed(savedUrl);
                    }
                })
                .setOnCancelListener(d -> {
                    if (pendingListener != null) {
                        pendingListener.onUserConfirmed(savedUrl);
                    }
                })
                .show();
    }

    private static boolean applyServer(PrefsManager prefs, NsdServerDiscovery.DiscoveredServer server) {
        if (server.endpointCipher == null || server.endpointCipher.isEmpty()) {
            return false;
        }
        return prefs.setServerEndpointCipher(server.endpointCipher);
    }

    private static void showPicker(Activity activity,
                                   PrefsManager prefs,
                                   List<NsdServerDiscovery.DiscoveredServer> servers,
                                   PendingListener pendingListener) {
        List<String> labels = new ArrayList<>();
        for (NsdServerDiscovery.DiscoveredServer server : servers) {
            labels.add(server.name + "\n" + ServerAddress.formatDisplay(server.host, server.port));
        }
        CharSequence[] items = labels.toArray(new CharSequence[0]);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.discover_select_server)
                .setItems(items, (dialog, which) -> {
                    NsdServerDiscovery.DiscoveredServer server = servers.get(which);
                    String finalUrl = prefs.getServerUrl();
                    if (applyServer(prefs, server)) {
                        Toast.makeText(activity,
                                ServerAddress.formatDisplay(server.host, server.port),
                                Toast.LENGTH_SHORT).show();
                        finalUrl = prefs.getServerUrl();
                    } else {
                        Toast.makeText(activity, R.string.discovery_endpoint_save_failed, Toast.LENGTH_LONG).show();
                    }
                    if (pendingListener != null) {
                        pendingListener.onUserConfirmed(finalUrl);
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (pendingListener != null) {
                        pendingListener.onUserConfirmed(prefs.getServerUrl());
                    }
                })
                .setOnCancelListener(d -> {
                    if (pendingListener != null) {
                        pendingListener.onUserConfirmed(prefs.getServerUrl());
                    }
                })
                .show();
    }
}
