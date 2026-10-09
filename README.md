# Paper + Clojure plugin framework

Paper **26.2 (build 132, STABLE)** server, a shared Clojure runtime plugin (`paperclj`) and three example plugins.

## Architecture

Clojure, the DSL and the optional nREPL live **once**, in the `paperclj` plugin (about 5 MB). Every Clojure plugin lists `depend: [paperclj]`, and its jar holds only its own sources, config and an empty Java stub class (about 5 KB). All plugins run in one shared Clojure runtime.

```
server/      Paper jar, start.ps1 / start.bat, server.properties (offline mode, local testing only)
framework/   project.clj, java/dev/paperclj/{PaperCljPlugin,ClojurePlugin,PluginLoader}.java
             src/paperclj/{core,nrepl}.clj, resources/{plugin.yml,config.yml}   -> paperclj.jar
plugins/     hello/  join-greeter/  tasks-demo/   (each: project.clj, java/ stub, src/, resources/)
tools/       jdk25/   portable Temurin 25 (Paper 26.2 requires Java 25+)
build.ps1    builds paperclj + the 3 plugins and copies the jars to server\plugins
```

## Requirements

- Windows + PowerShell, Leiningen (`lein.bat` on PATH), internet for the first build.
- JDK 25: the scripts use `tools\jdk25\<jdk-folder>`. On a fresh checkout unzip Temurin 25 (https://adoptium.net, Windows x64 JDK zip) into `tools\jdk25\` so `tools\jdk25\jdk-25...\bin\java.exe` exists. Nothing is installed system-wide; `JAVA_HOME` is set only inside `build.ps1`'s process.
- Paper jar: download `paper-26.2-132.jar` from https://fill.papermc.io (project `paper`, version `26.2`, build `132`) into `server\` (sha256 `5ab560a769c1ab413cb7f637dd0dc697974571f2db0a667cfbac511422e51b26`).
- EULA: read https://aka.ms/MinecraftEULA and set `eula=true` in `server\eula.txt` yourself (first start generates it with `false`).

## Build and run

```powershell
.\build.ps1                 # paperclj + hello + join-greeter + tasks-demo -> server\plugins\*.jar
.\server\start.ps1          # or server\start.bat   (listens on port 25565)
```

`build.ps1` runs `lein install` for `framework/` first (plugins compile their stub against that artifact), then `lein uberjar` for each project. Maven dependencies are cached in `.m2\` (`:local-repo`).

Why Leiningen: it was already installed, resolves Maven deps and builds uberjars with no extra installation, and Java + Clojure sources compile together via `:java-source-paths`.

## Plugins

| Plugin | What it shows | Config (`server\plugins\<name>\config.yml`) |
|---|---|---|
| paperclj | shared runtime, nREPL | `nrepl.enabled`, `nrepl.port` (7888) |
| hello | `/hello [name...]` | `hello.message` (`{player}`) |
| join-greeter | `PlayerJoinEvent` greeting; per-player counter persisted in `plugins\join-greeter\joins.edn` | `join-greeter.message` (`{player}`, `{count}`) |
| tasks-demo | repeating main-thread announcement + async CPU job whose result returns to the main thread | `tasks-demo.*` |

## Writing a plugin

Copy `plugins/hello`. A plugin needs:

- `java/<pkg>/MyPlugin.java`: `public final class MyPlugin extends dev.paperclj.ClojurePlugin {}` (Paper requires the main class inside the plugin's own jar)
- `resources/plugin.yml` with `main: <pkg>.MyPlugin` and `depend: [paperclj]`
- `resources/paperclj-ns` containing the entry namespace (e.g. `hello.core`)
- `resources/config.yml`
- a `project.clj` like hello's (`paperclj`, Clojure and paper-api are `:provided`, so they are not bundled)

```clojure
(ns hello.core (:require [paperclj.core :as p]))

(p/defplugin
  (enable [plugin]
    (p/defcommand hello [sender args] (p/send-text sender "hi") true)
    (p/defevent on-join PlayerJoinEvent [^PlayerJoinEvent e] ...)
    (p/every :announce {:delay 200 :period 1200} (p/broadcast "ping")))
  (disable [plugin] ...))
```

DSL (`paperclj.core`): `defplugin`, `defcommand`, `defevent`, `every`, `run-sync`, `run-later`, `run-async`, `config`, `data-file`, `send-text`, `broadcast`, `log-info`, `with-plugin`.

Because the runtime is shared, "the current plugin" is the dynamic var `*plugin*`. It is bound during enable and conveyed into every command, listener and task the plugin registers. Registrations are keyed per plugin (command name / handler name / task key), so reloading replaces them, and everything is unregistered when the plugin is disabled. Namespaces must be unique across plugins.

**Threading rule:** Bukkit/Paper API only on the main thread. `run-async` bodies must not touch it; return results with `run-sync` (see `tasks-demo`).

## Hot reload with nREPL

The `paperclj` plugin runs one nREPL server for all plugins, on `127.0.0.1` only (`server\plugins\paperclj\config.yml`, `nrepl.enabled` / `nrepl.port`, default 7888).

1. Start the server; the log shows `[paperclj] nREPL listening on 127.0.0.1:7888`.
2. Connect any nREPL client to `localhost:7888` (e.g. `lein repl :connect 7888`, or your editor).
3. Edit `plugins\<name>\src\...\core.clj`, then `(load-file "C:/.../plugins/hello/src/hello/core.clj")`.
   (`require ... :reload` would re-read the copy inside the running jar, not your edited file.)
4. From the REPL, plugin-scoped functions need a plugin in scope: `(paperclj.core/with-plugin "hello" (paperclj.core/config "hello.message"))`.

Handlers call functions through vars, so redefinitions take effect on the next command/event.

## Classloader (the problem and the fix)

Clojure finds `clojure/core.clj` and every `require`d namespace through the **thread-context classloader**. On Paper's main thread that is the server loader, which cannot see classes inside a plugin jar (each plugin has its own `PluginClassLoader`). Without a fix the first `Clojure.var(...)` fails with `FileNotFoundException: Could not locate clojure/core__init.class`.

- `PaperCljPlugin` sets the context loader to its own loader before first touching `clojure.lang.RT`, so Clojure boots from `paperclj.jar`.
- With a shared runtime there is a second problem: Paper lets a plugin load a dependency's **classes** but not its **resources**, and Clojure resolves namespaces through resources. `ClojurePlugin` therefore installs a `PluginLoader` as context loader while a plugin enables or disables: it looks in the plugin's own jar first (its sources), then in paperclj's loader (`clojure.*`, `nrepl.*`, `paperclj.*`). The previous loader is restored in a `finally`.
- The nREPL is started inside `PaperCljPlugin`'s loader window, so its eval threads inherit the right loader.

Consequence: code running later on its own (an event handler) has the server's context loader, so do not `require` new namespaces lazily there; require them from `enable`.

## Known limitations

- Plugins share one Clojure runtime and namespace space (no per-plugin isolation, no per-plugin Clojure version).
- Handlers should not `require` lazily at runtime (see Classloader).
- `online-mode=false` in `server.properties` is for local testing only.
- `nrepl/nrepl` is only published on Clojars, not Maven Central.
- Old `nrepl:` keys may remain in `server\plugins\<name>\config.yml` files generated by earlier builds; they are ignored.
