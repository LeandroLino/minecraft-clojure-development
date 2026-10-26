# Minecraft + Clojure

Servidor **Paper 26.2** para escrever plugins de Minecraft em **Clojure**, com **hot reload**: você edita o código e ele vale no servidor que já está rodando, sem reiniciar.

## A ideia

- O plugin **`paperclj`** carrega o Clojure **uma vez só** e oferece uma pequena DSL (comandos, eventos, tarefas, config) e um servidor nREPL para o hot reload.
- Cada plugin seu é um jar minúsculo (cerca de 5 KB) que usa o `paperclj`. A lógica fica em Clojure.
- Três plugins de exemplo mostram como usar, e sete **módulos reutilizáveis** (veja mais abaixo) servem de base para um servidor de verdade:

| Plugin | O que faz |
|---|---|
| `hello` | Comando `/hello [nome]`. A mensagem vem do `config.yml`. |
| `join-greeter` | Dá boas-vindas ao entrar e conta quantas vezes cada jogador entrou (salvo em arquivo). |
| `tasks-demo` | Anúncio periódico para todos e uma tarefa em outra thread que devolve o resultado para a thread principal. |

```
server/      Paper, scripts de início e server.properties
framework/   plugin paperclj (Java mínimo + DSL em Clojure + nREPL)
plugins/     hello, join-greeter, tasks-demo e os módulos skycore, auth, economy, ranks, teleport, chat, admin
build.ps1    compila tudo e copia os jars para server/plugins (-Test roda os testes antes)
```

## O que você precisa

- Windows com PowerShell e [Leiningen](https://leiningen.org) (`lein.bat` no PATH).
- **Java 25** (o Paper 26.2 exige). Baixe o ZIP "JDK, Windows x64" do Temurin 25 em [adoptium.net](https://adoptium.net) e extraia em `tools/jdk25/`, de modo que exista `tools/jdk25/<pasta-do-jdk>/bin/java.exe`. Nada é instalado no sistema.
- O jar do Paper: baixe o `paper-26.2-132.jar` em [fill.papermc.io](https://fill.papermc.io) (projeto `paper`, versão `26.2`, build `132`) e coloque em `server/`. O sha256 esperado é `5ab560a769c1ab413cb7f637dd0dc697974571f2db0a667cfbac511422e51b26`.

## Como rodar

```powershell
.\build.ps1               # gera paperclj.jar e todos os plugins em server\plugins
.\build.ps1 -Test         # idem, rodando antes os testes de cada módulo
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

## Módulos reutilizáveis

Sete plugins prontos para qualquer servidor Paper. Todos usam o `paperclj` e, exceto o `skycore`, também o `skycore`. As regras de jogo (preços, textos, limites) ficam nos `config.yml` e nos `lang/pt-BR.yml` de cada módulo, copiados para `server/plugins/<módulo>/` na primeira execução; os valores abaixo são os padrões.

| Módulo | Depende de | O que faz |
|---|---|---|
| `skycore` | `paperclj` | Biblioteca dos outros módulos: configuração com padrões do jar, mensagens em MiniMessage (`lang/pt-BR.yml`), comandos e eventos com menos cerimônia (`cmd/defcmd`, `events/listen!`), SQLite assíncrono, cache por jogador e serviços entre plugins. Não tem comandos. |
| `auth` | `skycore` | Login e registro por senha. |
| `economy` | `skycore` | Moeda, saldos e pagamentos. |
| `ranks` | `skycore`, `economy` | Escada de ranks comprados com a moeda; cada rank dá prefixo, permissões e limites. |
| `teleport` | `skycore` (opcional: `ranks`, `auth`) | Homes, warps e pedidos de teleporte entre jogadores. |
| `chat` | `skycore` (opcional: `ranks`, `economy`, `admin`, `auth`) | Formato do chat, entrada e saída, boas-vindas, tab, scoreboard e mensagens de morte. Não tem comandos. |
| `admin` | `skycore` (opcional: `auth`) | Ferramentas de administração em `/sky`: status, backup, anúncio, voo, cura, invisibilidade e silenciar. |

Os módulos opcionais só mudam o comportamento quando estão instalados: sem `ranks`, o limite de homes vem de `homes.default-limit`; sem `economy`, o saldo do scoreboard aparece como `-`; sem `auth`, ninguém precisa entrar na conta.

### Comandos

| Comando | Para que serve | Permissão | Padrão |
|---|---|---|---|
| `/register <senha> <senha>` | cria a conta (6 a 64 caracteres) | — | todos |
| `/login <senha>` | entra na conta | — | todos |
| `/balance [jogador]` (`/bal`, `/money`) | saldo; o de outro jogador exige `economy.balance.others` | `economy.balance` | todos |
| `/pay <jogador> <valor>` | envia moeda a um jogador online | `economy.pay` | todos |
| `/eco <give\|take\|set> <jogador> <valor>` | administra saldos | `economy.admin` | op |
| `/rank` | seu rank e o preço do próximo | `ranks.use` | todos |
| `/rank list` | todos os ranks e preços | `ranks.use` | todos |
| `/rank up` (`/rankup`) | compra o próximo rank | `ranks.use` | todos |
| `/rank set <jogador> <rank>` | define o rank sem cobrar | `ranks.admin` | op |
| `/sethome [nome]`, `/home [nome]`, `/delhome <nome>`, `/homes` | homes | `teleport.home` | todos |
| `/warp <nome>`, `/warps` | warps | `teleport.warp` | todos |
| `/setwarp <nome>`, `/delwarp <nome>` | cria e apaga warps | `teleport.warp.set` | op |
| `/tpa <jogador>`, `/tpahere <jogador>` | pede para ir até o jogador, ou para ele vir | `teleport.tpa` | todos |
| `/tpaccept [jogador]` (`/tpyes`), `/tpdeny [jogador]` (`/tpno`), `/tpcancel` | responde ou cancela pedidos | `teleport.tpa` | todos |
| `/sky status` | TPS, memória, jogadores, mundos, último backup | `admin.status` | op |
| `/sky backup now`, `/sky backup list` | faz e lista backups | `admin.backup` | op |
| `/sky broadcast <mensagem>` | anúncio para todos | `admin.broadcast` | op |
| `/sky fly [jogador]` | liga ou desliga o voo | `admin.fly` | op |
| `/sky heal [jogador]` | enche vida e fome, apaga fogo e efeitos | `admin.heal` | op |
| `/sky vanish [jogador]` | liga ou desliga a invisibilidade (quem tem `admin.vanish.see` ainda vê) | `admin.vanish` | op |
| `/sky mute <jogador> [minutos]`, `/sky unmute <jogador>` | silencia no chat; sem minutos, até `unmute` | `admin.mute` | op |
| `/sky senha <jogador> <nova>` | troca a senha de um jogador (precisa do `auth`) | `admin.senha` | op |

### Regras que valem saber

- **Login (`auth`):** quem entra sem estar na conta fica parado, sem falar, sem mexer e sem comandos além de `/login` e `/register`. São 60 s para entrar e 3 senhas erradas expulsam. A senha nunca é guardada: só um hash PBKDF2-HMAC-SHA256 com sal (`plugins/auth/auth.db`). `Ana` e `ana` contam como o mesmo nome. Quem sai logado e volta do mesmo IP em até 5 minutos entra sem senha. Um filtro tenta esconder as senhas do log do servidor (melhor esforço: se não puder ser instalado, o servidor avisa com um `WARN`). Tudo em `auth/config.yml`.
- **Economia:** saldo inicial 100, teto de 1 trilhão, `/pay` de no mínimo 1 e só para jogador online. Outros plugins usam a moeda pelo serviço `:economy`.
- **Ranks:** comprados em sequência com a moeda. O padrão tem cinco (Novato 0, Aventureiro 500, Veterano 2.500, Lenda 10.000, Ultimate 100.000); cada um tem prefixo, permissões e `limits` (o `teleport` lê `homes`). Os valores de um rank herdam do anterior.
- **Teleporte:** o limite de homes vem do rank (padrão 1 sem o `ranks`); nomes de 1 a 16 letras, números, `-` ou `_`. Pedidos de tpa expiram em 60 s.
- **Admin:** o backup agendado vem **ligado** (a cada 30 minutos, mantendo 24), com cópia consistente dos bancos SQLite; `/sky backup now` funciona sempre. O silêncio fica salvo em `plugins/admin/admin.db`.
- **Chat:** mensagens de morte em 13 categorias, com variações sorteadas, todas editáveis em `chat/config.yml` (`death.messages`).

## Criar um plugin novo

Copie `plugins/hello` e ajuste. Um plugin tem:

- `java/<pacote>/MeuPlugin.java`: `public final class MeuPlugin extends dev.paperclj.ClojurePlugin {}`
- `resources/plugin.yml` com `main: <pacote>.MeuPlugin` e `depend: [paperclj]`
- `resources/paperclj-ns` com o namespace de entrada (ex.: `hello.core`)
- `resources/config.yml` e um `project.clj` como o do `hello`

Depois adicione o nome na lista do `build.ps1`. Para usar a biblioteca dos módulos, liste `skycore` em `depend` e copie o perfil `:dev` do `ranks/project.clj` (ele põe o código do `skycore` no caminho dos testes).

```clojure
(ns hello.core (:require [paperclj.core :as p]))

(p/defplugin
  (enable [plugin]
    (p/defcommand hello [sender args] (p/send-text sender "oi") true)
    (p/defevent on-join PlayerJoinEvent [^PlayerJoinEvent e] ...)
    (p/every :aviso {:delay 200 :period 1200} (p/broadcast "ping")))
  (disable [plugin] ...))
```

Funções da DSL (`paperclj.core`): `defplugin`, `defcommand` (aceita um mapa de opções antes dos argumentos: `:permission`, `:aliases`, `:description`, `:usage`, `:complete`), `defevent`, `every`, `run-sync`, `run-later`, `run-async`, `config`, `data-file`, `send-text`, `broadcast`, `log-info`, `with-plugin`.

**Regra de threads:** a API do Bukkit só pode ser usada na thread principal. O que roda em `run-async` não pode tocá-la; devolva o resultado com `run-sync` (veja o `tasks-demo`).

## Detalhe técnico: classloader

O Clojure acha seus arquivos pelo classloader da thread, e o Paper isola cada plugin no seu próprio loader. Sem tratamento, o Clojure não encontra `clojure/core` nem os namespaces do plugin. A solução está em `framework/java/dev/paperclj/`: o `PluginLoader` é instalado como loader da thread durante o enable e o disable, e procura primeiro no jar do plugin e depois no do `paperclj`. Os plugins listados em `depend` (menos o próprio `paperclj`) entram na busca depois do jar do plugin, e é isso que deixa um módulo carregar os namespaces do `skycore`. Consequência: código que roda depois, por conta própria (um handler de evento), não deve fazer `require` tardio; faça os `require` dentro do `enable`.

## Limitações

- Todos os plugins dividem um runtime e um espaço de namespaces do Clojure (sem isolamento entre eles).
- `online-mode=false` é só para teste local.
- O `nrepl` só é publicado no Clojars, não no Maven Central.
- Testado no Windows com Paper 26.2 build 132, Java 25.0.4 e Clojure 1.12.6.
