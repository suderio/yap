(defproject yap "0.1.0-SNAPSHOT"
  :description "POC for Tink-based multi-recipient file encryption using Clojure"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [com.google.crypto.tink/tink "1.11.0"]
                 [org.clojure/tools.cli "1.0.219"]]
  :main yap.core
  :target-path "target/%s"
  :java-source-paths ["src/java"]
  :resource-paths ["resources"]
  :aot [yap.core]
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}}
  :test-paths ["test"])
