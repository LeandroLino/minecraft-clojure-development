(defproject tasks-demo "0.1.0"
  :description "Example: sync and async scheduled tasks"
  :repositories {"papermc" "https://repo.papermc.io/repository/maven-public/"}
  ;; Provided at runtime by the paperclj plugin / the server, so not bundled in this jar.
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.12.6"]
                                       [paperclj "0.1.0"]
                                       [io.papermc.paper/paper-api "26.2.build.132-stable"]]}}
  :source-paths ["src"]
  :java-source-paths ["java"]
  :resource-paths ["resources"]
  :javac-options ["--release" "25"]
  :local-repo "../../.m2"
  :uberjar-name "tasks-demo.jar"
  :target-path "target")
