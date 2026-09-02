package com.yyba.cloud;

import android.app.Application;

import com.yyba.cloud.discovery.ServerConnectCoordinator;
import com.yyba.cloud.discovery.ServerDiscoveryManager;

public class YybaApplication extends Application {

    private ServerDiscoveryManager discoveryManager;
    private ServerConnectCoordinator connectCoordinator;

    @Override
    public void onCreate() {
        super.onCreate();
        discoveryManager = new ServerDiscoveryManager(this);
        connectCoordinator = new ServerConnectCoordinator(this, discoveryManager);
    }

    public ServerDiscoveryManager getDiscoveryManager() {
        return discoveryManager;
    }

    public ServerConnectCoordinator getConnectCoordinator() {
        return connectCoordinator;
    }
}
