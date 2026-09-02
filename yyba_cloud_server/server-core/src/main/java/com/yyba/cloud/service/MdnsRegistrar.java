package com.yyba.cloud.service;

import com.yyba.cloud.util.DiscoveryCrypto;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class MdnsRegistrar {

    public static final String TXT_ENDPOINT = "ep";

    @Value("${yyba.mdns.enabled:true}")
    private boolean enabled;

    @Value("${yyba.mdns.service-name:YybaCloud}")
    private String serviceName;

    @Value("${yyba.mdns.service-type:_yybacloud._tcp.local.}")
    private String serviceType;

    private JmDNS jmdns;
    private ServiceInfo registeredService;

    public synchronized void register(int port) {
        if (!enabled) {
            return;
        }
        unregisterInternal();
        try {
            String advertiseHost = resolveLanHost();
            String hostLabel = sanitizeHostLabel(InetAddress.getLocalHost().getHostName());
            String instanceName = serviceName + "-" + hostLabel;
            String ep = DiscoveryCrypto.encryptEndpoint(advertiseHost, port);
            jmdns = JmDNS.create();
            Map<String, String> props = new HashMap<>();
            props.put(TXT_ENDPOINT, ep);
            registeredService = ServiceInfo.create(serviceType, instanceName, port, 0, 0, props);
            jmdns.registerService(registeredService);
            log.info("mDNS registered: {} on port {} (ep cipher len={})", instanceName, port, ep.length());
        } catch (Exception e) {
            log.warn("mDNS register failed: {}", e.getMessage());
            unregisterInternal();
        }
    }

    public synchronized void unregister() {
        unregisterInternal();
    }

    public synchronized boolean isRegistered() {
        return jmdns != null && registeredService != null;
    }

    @PreDestroy
    public void destroy() {
        unregister();
    }

    private void unregisterInternal() {
        if (jmdns == null) {
            return;
        }
        try {
            if (registeredService != null) {
                jmdns.unregisterService(registeredService);
            }
        } catch (Exception e) {
            log.debug("mDNS unregister ignored: {}", e.getMessage());
        } finally {
            try {
                jmdns.close();
            } catch (Exception ignored) {
            }
            jmdns = null;
            registeredService = null;
        }
    }

    static String resolveLanHost() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private static String sanitizeHostLabel(String hostName) {
        if (hostName == null || hostName.isBlank()) {
            return "host";
        }
        String cleaned = hostName.replaceAll("[^a-zA-Z0-9-]", "");
        return cleaned.isEmpty() ? "host" : cleaned;
    }
}
