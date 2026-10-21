package art.arcane.volmlib.projection;

import org.bukkit.Server;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

public class ProjectionViewAccessTest {
    @Test
    public void separatelyLoadedContractsAreDiscoveredAndRetiredByServiceEvents() throws Exception {
        UUID viewer = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getServicesManager()).thenReturn(services);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(services.getKnownServices()).thenReturn(List.of());
        ProjectionViewAccess access = new ProjectionViewAccess(plugin);
        assertTrue(access.viewers(world, 0, 0, 0, 10).isEmpty());
        assertEquals(Double.POSITIVE_INFINITY, access.distanceSquared(viewer, world, 0, 0, 0), 0.0D);
        String name = ProjectionViewProvider.class.getName();
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String requested) throws ClassNotFoundException {
                if (!requested.equals(name)) {
                    throw new ClassNotFoundException(requested);
                }
                try (InputStream input = ProjectionViewProvider.class.getResourceAsStream("ProjectionViewProvider.class")) {
                    byte[] bytes = input.readAllBytes();
                    return defineClass(requested, bytes, 0, bytes.length);
                } catch (Exception failure) {
                    throw new ClassNotFoundException(requested, failure);
                }
            }
        };
        Class<?> contract = loader.loadClass(name);
        Object provider = Proxy.newProxyInstance(loader, new Class<?>[] {contract}, (proxy, method, args) ->
            method.getName().equals("viewers") ? Map.of(viewer, 25.0D) : 25.0D);
        assertFalse(provider instanceof ProjectionViewProvider);
        RegisteredServiceProvider<?> registration = registration(contract, provider, plugin);
        ServiceRegisterEvent registered = mock(ServiceRegisterEvent.class);
        doReturn(registration).when(registered).getProvider();
        ServiceUnregisterEvent unregistered = mock(ServiceUnregisterEvent.class);
        doReturn(registration).when(unregistered).getProvider();
        access.registered(registered);
        assertEquals(Map.of(viewer, 25.0D), access.viewers(world, 0, 0, 0, 10));
        assertEquals(25.0D, access.distanceSquared(viewer, world, 0, 0, 0), 0.0D);
        access.unregistered(unregistered);
        assertTrue(access.viewers(world, 0, 0, 0, 10).isEmpty());
        access.close();
        access.registered(registered);
        assertTrue(access.viewers(world, 0, 0, 0, 10).isEmpty());
    }

    private static <T> RegisteredServiceProvider<T> registration(Class<T> contract, Object provider, Plugin plugin) {
        return new RegisteredServiceProvider<>(contract, contract.cast(provider), ServicePriority.Normal, plugin);
    }
}
