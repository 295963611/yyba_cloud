package com.yyba.cloud.util;

import com.google.android.material.textfield.TextInputEditText;

/**
 * 服务器地址：UI 仅填 IP/主机名与端口，内部统一拼接为 http:// URL。
 */
public final class ServerAddress {

    public static final int DEFAULT_PORT = 8080;

    private ServerAddress() {
    }

    public static final class Parsed {
        public final String host;
        public final int port;

        public Parsed(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public static Parsed parse(String input) {
        if (input == null) {
            return new Parsed("", DEFAULT_PORT);
        }
        String raw = input.trim();
        if (raw.isEmpty()) {
            return new Parsed("", DEFAULT_PORT);
        }

        String schemeStripped = raw;
        if (schemeStripped.regionMatches(true, 0, "http://", 0, 7)) {
            schemeStripped = schemeStripped.substring(7);
        } else if (schemeStripped.regionMatches(true, 0, "https://", 0, 8)) {
            schemeStripped = schemeStripped.substring(8);
        }

        int slash = schemeStripped.indexOf('/');
        if (slash >= 0) {
            schemeStripped = schemeStripped.substring(0, slash);
        }

        String host;
        int port = DEFAULT_PORT;
        if (schemeStripped.startsWith("[")) {
            int close = schemeStripped.indexOf(']');
            if (close > 0) {
                host = schemeStripped.substring(1, close);
                if (close + 1 < schemeStripped.length() && schemeStripped.charAt(close + 1) == ':') {
                    port = parsePort(schemeStripped.substring(close + 2), DEFAULT_PORT);
                }
            } else {
                host = normalizeHost(schemeStripped);
            }
        } else {
            int colon = schemeStripped.lastIndexOf(':');
            if (colon > 0 && schemeStripped.indexOf(':') == colon) {
                host = schemeStripped.substring(0, colon);
                port = parsePort(schemeStripped.substring(colon + 1), DEFAULT_PORT);
            } else {
                host = normalizeHost(schemeStripped);
            }
        }
        return new Parsed(normalizeHost(host), port);
    }

    public static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim();
        if (h.regionMatches(true, 0, "http://", 0, 7)) {
            h = h.substring(7);
        } else if (h.regionMatches(true, 0, "https://", 0, 8)) {
            h = h.substring(8);
        }
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        int slash = h.indexOf('/');
        if (slash >= 0) {
            h = h.substring(0, slash);
        }
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(':') == colon && h.indexOf('.') >= 0) {
            h = h.substring(0, colon);
        }
        return h.trim();
    }

    public static int parsePort(String portText, int fallback) {
        if (portText == null || portText.trim().isEmpty()) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(portText.trim());
            if (port < 1 || port > 65535) {
                return fallback;
            }
            return port;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String buildUrl(String host, String portText) {
        String h = normalizeHost(host);
        if (h.isEmpty()) {
            return "";
        }
        int port = parsePort(portText, DEFAULT_PORT);
        if (h.contains(":")) {
            return "http://[" + h + "]:" + port;
        }
        return "http://" + h + ":" + port;
    }

    public static String formatDisplay(String host, int port) {
        String h = normalizeHost(host);
        if (h.isEmpty()) {
            return "";
        }
        if (h.contains(":")) {
            return "[" + h + "]:" + port;
        }
        return h + ":" + port;
    }

    public static void loadInto(PrefsManager prefs, TextInputEditText etHost, TextInputEditText etPort) {
        etHost.setText(prefs.getServerHost());
        int port = prefs.getServerPort();
        etPort.setText(port == DEFAULT_PORT && prefs.getServerHost().isEmpty()
                ? "" : String.valueOf(port));
    }

    public static void loadFromUrl(TextInputEditText etHost, TextInputEditText etPort, String url) {
        Parsed parsed = parse(url);
        etHost.setText(parsed.host);
        etPort.setText(String.valueOf(parsed.port));
    }

    public static boolean saveFrom(PrefsManager prefs, TextInputEditText etHost, TextInputEditText etPort) {
        String hostInput = etHost.getText() != null ? etHost.getText().toString() : "";
        String portInput = etPort.getText() != null ? etPort.getText().toString() : "";
        Parsed parsed = parse(hostInput);
        if (parsed.host.isEmpty()) {
            return false;
        }
        int port = portInput.trim().isEmpty()
                ? parsed.port
                : parsePort(portInput, parsed.port);
        if (!prefs.setServerAddress(parsed.host, String.valueOf(port))) {
            return false;
        }
        ServerAddress.applyParsed(etHost, etPort, new Parsed(parsed.host, port));
        return true;
    }

    public static void applyParsed(TextInputEditText etHost, TextInputEditText etPort, Parsed parsed) {
        etHost.setText(parsed.host);
        etPort.setText(String.valueOf(parsed.port));
    }
}
