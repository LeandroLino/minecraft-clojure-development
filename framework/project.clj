(defproject paperclj "0.1.0"
  :description "Shared Clojure runtime + DSL for Paper plugins"
  :dependencies [[org.clojure/clojure "1.12.6"]
                 [nrepl/nrepl "1.8.0"]]
  :repositories {"papermc" "https://repo.papermc.io/repository/maven-public/"}
  :profiles {:provided {:dependencies [[io.papermc.paper/paper-api "26.2.build.132-stable"]]}}
  :source-paths ["src"]
  :java-source-paths ["java"]
  :resource-paths ["resources"]
  :javac-options ["--release" "25"]
  :local-repo "../.m2"
  :uberjar-name "paperclj.jar"
  :target-path "target")
