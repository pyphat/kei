package me.piyaphat.kei.discovery.impl;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresApi;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import me.piyaphat.kei.discovery.api.DiscoveredServer;
import me.piyaphat.kei.discovery.api.ServerDiscovery;

@Singleton
public final class NsdServerDiscovery implements ServerDiscovery {
    private final NsdManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, DiscoveredServer> found = new LinkedHashMap<>();
    private final Map<String, NsdManager.ServiceInfoCallback> infoCallbacks = new LinkedHashMap<>();
    private final Set<String> seen = new HashSet<>();
    private final Queue<NsdServiceInfo> pending = new ArrayDeque<>();
    private Listener listener;
    private boolean resolving;

    @Inject
    public NsdServerDiscovery(@ApplicationContext Context context) {
        manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        found.clear();
        seen.clear();
        manager.discoverServices("_kei._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    @Override
    public void stop() {
        try { manager.stopServiceDiscovery(discovery); } catch (IllegalArgumentException ignored) {}
        if (Build.VERSION.SDK_INT >= 34) unregisterInfoCallbacks();
        pending.clear();
        resolving = false;
        listener = null;
        found.clear();
        seen.clear();
    }

    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String type) {}
        @Override public void onServiceFound(NsdServiceInfo service) {
            if (!seen.add(service.getServiceName())) return;
            if (Build.VERSION.SDK_INT >= 34) registerInfo(service);
            else {
                pending.add(service);
                resolveNext();
            }
        }
        @Override public void onServiceLost(NsdServiceInfo service) {
            main.post(() -> {
                seen.remove(service.getServiceName());
                if (found.remove(service.getServiceName()) != null) publish();
            });
        }
        @Override public void onDiscoveryStopped(String type) {}
        @Override public void onStartDiscoveryFailed(String type, int error) { stop(); }
        @Override public void onStopDiscoveryFailed(String type, int error) { stop(); }
    };

    private void resolveNext() {
        if (resolving || pending.isEmpty()) return;
        resolving = true;
        manager.resolveService(pending.remove(), new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo service, int error) {
                resolving = false;
                resolveNext();
            }
            @Override public void onServiceResolved(NsdServiceInfo service) {
                add(service);
                resolving = false;
                resolveNext();
            }
        });
    }

    private void add(NsdServiceInfo service) {
        InetAddress host = service.getHost();
        if (host == null || service.getPort() <= 0) return;
        String address = host.getHostAddress();
        if (address.contains(":")) address = "[" + address + "]";
        String resolvedHost = address;
        main.post(() -> {
            if (listener != null) {
                found.put(service.getServiceName(), new DiscoveredServer(
                        service.getServiceName(), resolvedHost, service.getPort()));
                publish();
            }
        });
    }

    @RequiresApi(34)
    private void registerInfo(NsdServiceInfo service) {
        NsdManager.ServiceInfoCallback callback = new NsdManager.ServiceInfoCallback() {
            @Override public void onServiceUpdated(NsdServiceInfo info) { add(info); }
            @Override public void onServiceLost() {
                main.post(() -> {
                    if (found.remove(service.getServiceName()) != null) publish();
                });
            }
            @Override public void onServiceInfoCallbackRegistrationFailed(int error) {}
            @Override public void onServiceInfoCallbackUnregistered() {}
        };
        infoCallbacks.put(service.getServiceName(), callback);
        manager.registerServiceInfoCallback(service, command -> main.post(command), callback);
    }

    @RequiresApi(34)
    private void unregisterInfoCallbacks() {
        for (NsdManager.ServiceInfoCallback callback : infoCallbacks.values()) {
            manager.unregisterServiceInfoCallback(callback);
        }
        infoCallbacks.clear();
    }

    private void publish() {
        if (listener != null) listener.onServers(new ArrayList<>(found.values()));
    }
}
