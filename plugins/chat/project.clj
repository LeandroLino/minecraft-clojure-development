(defproject chat "0.1.0"
  :description "Formatted chat, tab list, sidebar scoreboard and welcome messages"
  :repositories {"papermc" "https://repo.papermc.io/repository/maven-public/"}
  ;; Provided at runtime by the paperclj plugin / the server, so not bundled in this jar.
  :profiles {:dev {:source-paths ["../skycore/src"]}
             :provided {:dependencies [[org.clojure/clojure "1.12.6"]
                                       [paperclj "0.1.0"]
                                       [io.papermc.paper/paper-api "26.2.build.132-stable"]]}}
  :source-paths ["src"]
  :test-paths ["test"]
  :java-source-paths ["java"]
  :resource-paths ["resources"]
  :javac-options ["--release" "25"]
  :local-repo "../../.m2"
  :uberjar-name "chat.jar"
  :target-path "target")
