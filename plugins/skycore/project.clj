(defproject skycore "0.1.0"
  :description "Shared core for the game modules: config, messages, wrappers, SQLite, services"
  :repositories {"papermc" "https://repo.papermc.io/repository/maven-public/"}
  ;; Provided at runtime by the paperclj plugin / the server, so not bundled in this jar.
  ;; sqlite-jdbc is bundled with Paper itself (server/libraries), so it is not a dependency here.
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.12.6"]
                                       [paperclj "0.1.0"]
                                       [io.papermc.paper/paper-api "26.2.build.132-stable"]]}}
  :source-paths ["src"]
  :test-paths ["test"]
  :java-source-paths ["java"]
  :resource-paths ["resources"]
  :javac-options ["--release" "25"]
  :local-repo "../../.m2"
  :uberjar-name "skycore.jar"
  :target-path "target")
