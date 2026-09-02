package com.yyba.cloud.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * 偏好存储：服务器地址以 Discovery 密文存于普通 prefs；登录凭证存 EncryptedSharedPreferences。
 */
public class PrefsManager {

    private static final String PREFS_NAME = "yyba_cloud_prefs";
    private static final String SECURE_PREFS_NAME = "yyba_cloud_secure_prefs";
    private static final String KEY_ENDPOINT_CIPHER = "server_endpoint_cipher";

    private final SharedPreferences prefs;
    private final SharedPreferences securePrefs;
    private final SharedPreferences legacySecurePrefs;
    private final boolean secureStorageAvailable;

    public PrefsManager(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences secure = null;
        SharedPreferences legacySecure = null;
        boolean secureOk = false;
        try {
            MasterKey masterKey = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            legacySecure = EncryptedSharedPreferences.create(
                    app, PREFS_NAME, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            secure = EncryptedSharedPreferences.create(
                    app, SECURE_PREFS_NAME, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            secureOk = true;
        } catch (Exception ignored) {
        }
        this.legacySecurePrefs = legacySecure;
        this.securePrefs = secure;
        this.secureStorageAvailable = secureOk && secure != null;
        migrateLegacySecureCredentialsIfNeeded();
        migrateLegacyPlaintextIfNeeded();
    }

    public boolean isSecureStorageAvailable() {
        return secureStorageAvailable;
    }

    public boolean hasServerEndpoint() {
        String cipher = getServerEndpointCipher();
        return cipher != null && !cipher.isEmpty();
    }

    public String getServerEndpointCipher() {
        return prefs.getString(KEY_ENDPOINT_CIPHER, "");
    }

    /** 自动发现：原样保存 mDNS TXT ep 密文 */
    public boolean setServerEndpointCipher(String cipher) {
        if (cipher == null || cipher.trim().isEmpty()) {
            return false;
        }
        prefs.edit()
                .putString(KEY_ENDPOINT_CIPHER, cipher.trim())
                .remove("server_host")
                .remove("server_port")
                .remove("server_url")
                .apply();
        return true;
    }

    /** 手动填写：加密后写入 cipher */
    public boolean setServerAddress(String host, String portText) {
        String normalizedHost = ServerAddress.normalizeHost(host);
        if (normalizedHost.isEmpty()) {
            return false;
        }
        int port = ServerAddress.parsePort(portText, ServerAddress.DEFAULT_PORT);
        try {
            String cipher = DiscoveryCrypto.encryptEndpoint(normalizedHost, port);
            return setServerEndpointCipher(cipher);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean setServerUrl(String url) {
        ServerAddress.Parsed parsed = ServerAddress.parse(url);
        if (parsed.host.isEmpty()) {
            return false;
        }
        return setServerAddress(parsed.host, String.valueOf(parsed.port));
    }

    public String getServerHost() {
        DiscoveryCrypto.Endpoint endpoint = getDecryptedEndpoint();
        return endpoint != null ? endpoint.host : "";
    }

    public int getServerPort() {
        DiscoveryCrypto.Endpoint endpoint = getDecryptedEndpoint();
        return endpoint != null ? endpoint.port : ServerAddress.DEFAULT_PORT;
    }

    public String getServerUrl() {
        DiscoveryCrypto.Endpoint endpoint = getDecryptedEndpoint();
        if (endpoint == null || endpoint.host.isEmpty()) {
            return "";
        }
        return ServerAddress.buildUrl(endpoint.host, String.valueOf(endpoint.port));
    }

    public String getServerDisplay() {
        DiscoveryCrypto.Endpoint endpoint = getDecryptedEndpoint();
        if (endpoint == null || endpoint.host.isEmpty()) {
            return "";
        }
        return ServerAddress.formatDisplay(endpoint.host, endpoint.port);
    }

    private DiscoveryCrypto.Endpoint getDecryptedEndpoint() {
        String cipher = getServerEndpointCipher();
        if (cipher.isEmpty()) {
            return null;
        }
        return DiscoveryCrypto.decryptEndpoint(cipher);
    }

    private void migrateLegacySecureCredentialsIfNeeded() {
        if (securePrefs == null || legacySecurePrefs == null) {
            return;
        }
        if (securePrefs.getString("token", null) != null) {
            return;
        }
        String token = legacySecurePrefs.getString("token", null);
        String username = legacySecurePrefs.getString("username", null);
        String role = legacySecurePrefs.getString("role", null);
        if (token == null && username == null && role == null) {
            return;
        }
        SharedPreferences.Editor editor = securePrefs.edit();
        if (token != null) {
            editor.putString("token", token);
        }
        if (username != null) {
            editor.putString("username", username);
        }
        if (role != null) {
            editor.putString("role", role);
        }
        editor.apply();
    }

    private void migrateLegacyPlaintextIfNeeded() {
        if (!getServerEndpointCipher().isEmpty()) {
            return;
        }
        String host = readLegacyHost();
        int port = readLegacyPort(host);
        if (host.isEmpty()) {
            return;
        }
        if (setServerAddress(host, String.valueOf(port))) {
            clearLegacyServerKeys();
        }
    }

    private String readLegacyHost() {
        String host = prefs.getString("server_host", "");
        if (!host.isEmpty()) {
            return host;
        }
        if (legacySecurePrefs != null) {
            host = legacySecurePrefs.getString("server_host", "");
            if (!host.isEmpty()) {
                return host;
            }
        }
        if (securePrefs != null) {
            host = securePrefs.getString("server_host", "");
            if (!host.isEmpty()) {
                return host;
            }
        }
        String legacyUrl = prefs.getString("server_url", "");
        if (legacyUrl == null || legacyUrl.trim().isEmpty()) {
            if (legacySecurePrefs != null) {
                legacyUrl = legacySecurePrefs.getString("server_url", "");
            }
        }
        if (legacyUrl == null || legacyUrl.trim().isEmpty()) {
            if (securePrefs != null) {
                legacyUrl = securePrefs.getString("server_url", "");
            }
        }
        if (legacyUrl != null && !legacyUrl.trim().isEmpty()) {
            return ServerAddress.parse(legacyUrl).host;
        }
        return "";
    }

    private int readLegacyPort(String host) {
        if (prefs.contains("server_port")) {
            return prefs.getInt("server_port", ServerAddress.DEFAULT_PORT);
        }
        if (legacySecurePrefs != null && legacySecurePrefs.contains("server_port")) {
            return legacySecurePrefs.getInt("server_port", ServerAddress.DEFAULT_PORT);
        }
        if (securePrefs != null && securePrefs.contains("server_port")) {
            return securePrefs.getInt("server_port", ServerAddress.DEFAULT_PORT);
        }
        String legacyUrl = prefs.getString("server_url", "");
        if (legacyUrl == null || legacyUrl.trim().isEmpty()) {
            if (legacySecurePrefs != null) {
                legacyUrl = legacySecurePrefs.getString("server_url", "");
            }
        }
        if (legacyUrl == null || legacyUrl.trim().isEmpty()) {
            if (securePrefs != null) {
                legacyUrl = securePrefs.getString("server_url", "");
            }
        }
        if (legacyUrl != null && !legacyUrl.trim().isEmpty()) {
            return ServerAddress.parse(legacyUrl).port;
        }
        return ServerAddress.DEFAULT_PORT;
    }

    private void clearLegacyServerKeys() {
        prefs.edit()
                .remove("server_host")
                .remove("server_port")
                .remove("server_url")
                .apply();
        if (legacySecurePrefs != null) {
            legacySecurePrefs.edit()
                    .remove("server_host")
                    .remove("server_port")
                    .remove("server_url")
                    .apply();
        }
        if (securePrefs != null) {
            securePrefs.edit()
                    .remove("server_host")
                    .remove("server_port")
                    .remove("server_url")
                    .apply();
        }
    }

    public void setAutoDiscover(boolean v) {
        prefs.edit().putBoolean("auto_discover", v).apply();
    }

    public boolean isAutoDiscover() {
        return prefs.getBoolean("auto_discover", true);
    }

    public void setToken(String token) {
        if (!ensureSecureWritable()) {
            return;
        }
        securePrefs.edit().putString("token", token).apply();
    }

    public String getToken() {
        return securePrefs != null ? securePrefs.getString("token", null) : null;
    }

    public void setUsername(String username) {
        if (!ensureSecureWritable()) {
            return;
        }
        securePrefs.edit().putString("username", username).apply();
    }

    public String getUsername() {
        return securePrefs != null ? securePrefs.getString("username", null) : null;
    }

    public void setRole(String role) {
        if (!ensureSecureWritable()) {
            return;
        }
        securePrefs.edit().putString("role", role).apply();
    }

    public String getRole() {
        return securePrefs != null ? securePrefs.getString("role", "MEMBER") : "MEMBER";
    }

    public void setWifiOnly(boolean v) {
        prefs.edit().putBoolean("wifi_only", v).apply();
    }

    public boolean isWifiOnly() {
        return prefs.getBoolean("wifi_only", true);
    }

    public void setAutoSync(boolean v) {
        prefs.edit().putBoolean("auto_sync", v).apply();
    }

    public boolean isAutoSync() {
        return prefs.getBoolean("auto_sync", false);
    }

    public void setSyncIndexServerTime(long epochMs) {
        prefs.edit().putLong("sync_index_server_time", epochMs).apply();
    }

    public long getSyncIndexServerTime() {
        return prefs.getLong("sync_index_server_time", 0L);
    }

    public String getLastIndexEnvironmentKey() {
        return prefs.getString("last_index_env_key", "");
    }

    public void setLastIndexEnvironmentKey(String key) {
        prefs.edit().putString("last_index_env_key", key == null ? "" : key).apply();
    }

    public void clearToken() {
        if (securePrefs != null) {
            securePrefs.edit().remove("token").apply();
        }
    }

    public void clear() {
        prefs.edit().clear().apply();
        if (securePrefs != null) {
            securePrefs.edit().clear().apply();
        }
    }

    private boolean ensureSecureWritable() {
        return secureStorageAvailable;
    }
}
