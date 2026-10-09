# Minecraft + Clojure

Servidor **Paper 26.2** para escrever plugins de Minecraft em **Clojure**, com **hot reload**: você edita o código e ele vale no servidor que já está rodando, sem reiniciar.

## A ideia

- O plugin **`paperclj`** carrega o Clojure **uma vez só** e oferece uma pequena DSL (comandos, eventos, tarefas, config) e um servidor nREPL para o hot reload.
- Cada plugin seu é um jar minúsculo (cerca de 5 KB) que usa o `paperclj`. A lógica fica em Clojure.
- Três plugins de exemplo mostram como usar:

| Plugin | O que faz |
|---|---|
| `hello` | Comando `/hello [nome]`. A mensagem vem do `config.yml`. |
| `join-greeter` | Dá boas-vindas ao entrar e conta quantas vezes cada jogador entrou (salvo em arquivo). |
| `tasks-demo` | Anúncio periódico para todos e uma tarefa em outra thread que devolve o resultado para a thread principal. |

```
server/      Paper, scripts de início e server.properties
framework/   plugin paperclj (Java mínimo + DSL em Clojure + nREPL)
plugins/     hello, join-greeter, tasks-demo
build.ps1    compila tudo e copia os jars para server/plugins
```

## O que você precisa

- Windows com PowerShell e [Leiningen](https://leiningen.org) (`lein.bat` no PATH).
- **Java 25** (o Paper 26.2 exige). Baixe o ZIP "JDK, Windows x64" do Temurin 25 em [adoptium.net](https://adoptium.net) e extraia em `tools/jdk25/`, de modo que exista `tools/jdk25/<pasta-do-jdk>/bin/java.exe`. Nada é instalado no sistema.
- O jar do Paper: baixe o `paper-26.2-132.jar` em [fill.papermc.io](https://fill.papermc.io) (projeto `paper`, versão `26.2`, build `132`) e coloque em `server/`. O sha256 esperado é `5ab560a769c1ab413cb7f637dd0dc697974571f2db0a667cfbac511422e51b26`.

## Como rodar

```powershell
.\build.ps1               # gera paperclj.jar e os 3 plugins em server\plugins
.\server\start.ps1        # ou server\start.bat (porta 25565)
```

1. Na primeira vez, o servidor para dizendo que precisa do EULA. Leia https://aka.ms/MinecraftEULA, troque `eula=false` por `eula=true` em `server/eula.txt` e inicie de novo.
2. No log, procure `Enabling paperclj`, `Enabling hello`... e `Done`.
3. Entre no jogo em `localhost:25565` (Minecraft 26.2). O servidor está em modo offline (`online-mode=false`), então serve só para teste local.
4. Teste: `/hello`, `/hello Maria`, e veja a mensagem de boas-vindas ao entrar.

## Hot reload

O `paperclj` liga um nREPL em `127.0.0.1:7888` (só local; configurável em `server/plugins/paperclj/config.yml`).

1. Com o servidor rodando, conecte um cliente nREPL em `localhost:7888` (seu editor, ou `lein repl :connect 7888`).
2. Edite, por exemplo, `plugins/hello/src/hello/core.clj`.
3. Avalie no REPL:
   ```clojure
   (load-file "C:/caminho/do/projeto/plugins/hello/src/hello/core.clj")
   ```
4. Rode `/hello` de novo: já usa o código novo.

O que vale saber:

- A mudança fica só **na memória**. O servidor carrega o plugin do jar, então para fixar a mudança rode `.\build.ps1` e reinicie.
- Funciona para código Clojure. Mudanças em `config.yml`, `plugin.yml` e nos arquivos Java exigem build e reinício.
- O que está dentro do `enable` (como o `defcommand`) não roda de novo no reload. O corpo das funções recarrega.
- No REPL, funções que dependem do plugin precisam de contexto: `(paperclj.core/with-plugin "hello" (paperclj.core/config "hello.message"))`.

## Criar um plugin novo

Copie `plugins/hello` e ajuste. Um plugin tem:

- `java/<pacote>/MeuPlugin.java`: `public final class MeuPlugin extends dev.paperclj.ClojurePlugin {}`
- `resources/plugin.yml` com `main: <pacote>.MeuPlugin` e `depend: [paperclj]`
- `resources/paperclj-ns` com o namespace de entrada (ex.: `hello.core`)
- `resources/config.yml` e um `project.clj` como o do `hello`

Depois adicione o nome na lista do `build.ps1`.

```clojure
(ns hello.core (:require [paperclj.core :as p]))

(p/defplugin
  (enable [plugin]
    (p/defcommand hello [sender args] (p/send-text sender "oi") true)
    (p/defevent on-join PlayerJoinEvent [^PlayerJoinEvent e] ...)
    (p/every :aviso {:delay 200 :period 1200} (p/broadcast "ping")))
  (disable [plugin] ...))
```

Funções da DSL (`paperclj.core`): `defplugin`, `defcommand`, `defevent`, `every`, `run-sync`, `run-later`, `run-async`, `config`, `data-file`, `send-text`, `broadcast`, `log-info`, `with-plugin`.

**Regra de threads:** a API do Bukkit só pode ser usada na thread principal. O que roda em `run-async` não pode tocá-la; devolva o resultado com `run-sync` (veja o `tasks-demo`).

## Detalhe técnico: classloader

O Clojure acha seus arquivos pelo classloader da thread, e o Paper isola cada plugin no seu próprio loader. Sem tratamento, o Clojure não encontra `clojure/core` nem os namespaces do plugin. A solução está em `framework/java/dev/paperclj/`: o `PluginLoader` é instalado como loader da thread durante o enable e o disable, e procura primeiro no jar do plugin e depois no do `paperclj`. Consequência: código que roda depois, por conta própria (um handler de evento), não deve fazer `require` tardio; faça os `require` dentro do `enable`.

## Limitações

- Todos os plugins dividem um runtime e um espaço de namespaces do Clojure (sem isolamento entre eles).
- `online-mode=false` é só para teste local.
- O `nrepl` só é publicado no Clojars, não no Maven Central.
- Testado no Windows com Paper 26.2 build 132, Java 25.0.4 e Clojure 1.12.6.
