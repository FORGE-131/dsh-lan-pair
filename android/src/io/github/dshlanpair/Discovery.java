package io.github.dshlanpair;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Thin wrapper over NsdManager for the `_dshpair._tcp` service.
 *
 * Android's resolver handles the mDNS wire protocol, so this app only has to
 * name the service type. Resolutions are serialized because NsdManager rejects
 * concurrent resolveService calls on most platform versions.
 */
final class Discovery {

    private static final String TAG = "DshLanPair";
    private static final String SERVICE_TYPE = "_dshpair._tcp.";

    /** One discovered desktop. */
    static final class Device {
        final String name;
        String host;
        int port;
        boolean resolved;

        Device(String name) {
            this.name = name;
        }

        String label() {
            return resolved ? name + "  " + host + ":" + port : name + "  resolving...";
        }
    }

    /** Callbacks are delivered on the main thread by the caller's handler. */
    interface Listener {
        void onDevicesChanged(List<Device> devices);

        void onDiscoveryFailed(String reason);
    }

    private final Context context;
    private final NsdManager nsd;
    private final List<Device> devices = new ArrayList<>();
    private final Queue<NsdServiceInfo> pending = new ArrayDeque<>();
    private Listener listener;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean resolving;
    private boolean running;

    Discovery(Context context) {
        this.context = context;
        this.nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    List<Device> devices() {
        return devices;
    }

    void start(Listener target) {
        if (running) {
            return;
        }
        listener = target;
        devices.clear();
        pending.clear();
        resolving = false;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                running = false;
                fail("cannot start discovery (code " + errorCode + ")");
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                running = false;
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                running = true;
                publish();
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                running = false;
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (!SERVICE_TYPE.equals(info.getServiceType())) {
                    return;
                }
                for (Device device : devices) {
                    if (device.name.equals(info.getServiceName())) {
                        return;
                    }
                }
                Device device = new Device(info.getServiceName());
                devices.add(device);
                pending.add(info);
                publish();
                pump();
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                devices.removeIf(device -> device.name.equals(info.getServiceName()));
                publish();
            }
        };

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException error) {
            fail("discovery unavailable: " + error.getMessage());
        }
    }

    /** Resolve the next queued service, one at a time. */
    private void pump() {
        if (resolving || pending.isEmpty()) {
            return;
        }
        final NsdServiceInfo info = pending.poll();
        resolving = true;
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo failed, int errorCode) {
                    Log.w(TAG, "resolve failed for " + failed.getServiceName() + ": " + errorCode);
                    resolving = false;
                    pump();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    String host = resolved.getHost() == null ? null : resolved.getHost().getHostAddress();
                    for (Device device : devices) {
                        if (device.name.equals(resolved.getServiceName())) {
                            device.host = host;
                            device.port = resolved.getPort();
                            device.resolved = host != null && resolved.getPort() > 0;
                            break;
                        }
                    }
                    publish();
                    resolving = false;
                    pump();
                }
            });
        } catch (RuntimeException error) {
            resolving = false;
            Log.w(TAG, "resolve threw: " + error.getMessage());
        }
    }

    private void publish() {
        if (listener != null) {
            listener.onDevicesChanged(new ArrayList<>(devices));
        }
    }

    private void fail(String reason) {
        if (listener != null) {
            listener.onDiscoveryFailed(reason);
        }
    }

    void stop() {
        running = false;
        if (discoveryListener != null) {
            try {
                nsd.stopServiceDiscovery(discoveryListener);
            } catch (RuntimeException ignored) {
                // already stopped
            }
            discoveryListener = null;
        }
        listener = null;
    }
}
