package dev.paperclj;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Classloader used as Clojure's context loader for a dependent plugin.
 * Lookup order: the plugin's own loader (its sources, resources and Paper-visible
 * classes), then the loaders of the plugins it lists under "depend" (so a module can
 * require the Clojure namespaces of a library plugin such as skycore), then the shared
 * paperclj loader (clojure.*, nrepl.*, paperclj.* files).
 * Paper's PluginClassLoader only exposes a dependency's classes, not its resources,
 * and Clojure resolves namespaces through resources, hence this bridge.
 */
final class PluginLoader extends ClassLoader {

    private final List<ClassLoader> dependencies;
    private final ClassLoader shared;

    PluginLoader(ClassLoader plugin, List<ClassLoader> dependencies, ClassLoader shared) {
        super(plugin);
        this.dependencies = dependencies;
        this.shared = shared;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        for (ClassLoader dependency : dependencies) {
            try {
                return dependency.loadClass(name);
            } catch (ClassNotFoundException ignored) {
                // try the next loader
            }
        }
        return shared.loadClass(name);
    }

    @Override
    protected URL findResource(String name) {
        for (ClassLoader dependency : dependencies) {
            URL url = dependency.getResource(name);
            if (url != null) {
                return url;
            }
        }
        return shared.getResource(name);
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        List<URL> found = new ArrayList<>();
        for (ClassLoader dependency : dependencies) {
            found.addAll(Collections.list(dependency.getResources(name)));
        }
        found.addAll(Collections.list(shared.getResources(name)));
        return Collections.enumeration(found);
    }
}
