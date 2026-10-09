package dev.paperclj;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The shared runtime plugin ("paperclj"). It boots Clojure once, under its own
 * classloader, and optionally runs the nREPL server (localhost only).
 */
public final class PaperCljPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        withOwnLoader(() -> {
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("paperclj.core"));
            if (getConfig().getBoolean("nrepl.enabled", false)) {
                require.invoke(Clojure.read("paperclj.nrepl"));
                IFn start = Clojure.var("paperclj.nrepl", "start!");
                start.invoke(getConfig().getInt("nrepl.port", 7888), this);
            }
        });
    }

    @Override
    public void onDisable() {
        withOwnLoader(() -> {
            IFn shutdown = Clojure.var("paperclj.core", "shutdown!");
            shutdown.invoke();
        });
    }

    private void withOwnLoader(Runnable body) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(getClassLoader());
        try {
            body.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
