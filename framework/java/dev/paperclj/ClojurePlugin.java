package dev.paperclj;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Base class of every Clojure plugin. A plugin jar contains only an empty subclass
 * (Paper needs the main class inside the plugin's own jar), its Clojure sources and a
 * "paperclj-ns" resource naming the entry namespace. The shared Clojure runtime lives in
 * the paperclj plugin, which every Clojure plugin must list under "depend" in plugin.yml.
 *
 * Classloader fix: Clojure resolves namespaces through the thread-context classloader.
 * On Paper's main thread that is the server loader, which sees no plugin jar. While
 * enabling/disabling we install a {@link PluginLoader} (plugin jar first, then paperclj)
 * and restore the previous loader afterwards. See README, "Classloader".
 */
public abstract class ClojurePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        String entryNamespace = readEntryNamespace();
        withPluginLoader(() -> {
            IFn enable = Clojure.var("paperclj.core", "enable!");
            enable.invoke(this, entryNamespace);
        });
    }

    @Override
    public void onDisable() {
        withPluginLoader(() -> {
            IFn disable = Clojure.var("paperclj.core", "disable!");
            disable.invoke(this);
        });
    }

    private String readEntryNamespace() {
        try (InputStream in = getClassLoader().getResourceAsStream("paperclj-ns")) {
            if (in == null) {
                throw new IllegalStateException("Missing resource 'paperclj-ns' in " + getName());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read 'paperclj-ns'", e);
        }
    }

    private void withPluginLoader(Runnable body) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(new PluginLoader(getClassLoader(), ClojurePlugin.class.getClassLoader()));
        try {
            body.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
