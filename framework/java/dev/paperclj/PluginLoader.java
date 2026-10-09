package dev.paperclj;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

/**
 * Classloader used as Clojure's context loader for a dependent plugin.
 * Lookup order: the plugin's own loader (its sources, resources and Paper-visible
 * classes), then the shared paperclj loader (clojure.*, nrepl.*, paperclj.* files).
 * Paper's PluginClassLoader only exposes a dependency's classes, not its resources,
 * and Clojure resolves namespaces through resources, hence this bridge.
 */
final class PluginLoader extends ClassLoader {

    private final ClassLoader shared;

    PluginLoader(ClassLoader plugin, ClassLoader shared) {
        super(plugin);
        this.shared = shared;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return shared.loadClass(name);
    }

    @Override
    protected URL findResource(String name) {
        return shared.getResource(name);
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        return shared.getResources(name);
    }
}
