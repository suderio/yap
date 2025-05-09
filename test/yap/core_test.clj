(ns yap.core-test
  (:require [clojure.test :refer :all]
            [yap.core :refer :all])
  (:import [java.nio.file Files Paths]
           [java.nio.charset StandardCharsets]
           [java.io File]))

(defn tmp-path [& parts]
  (str (System/getProperty "java.io.tmpdir") "/" (clojure.string/join "-" parts)))

(defn write-tmp [name content]
  (let [path (tmp-path name)]
    (spit path content)
    path))

(deftest roundtrip-encryption-test
  (yap.core/init)
  (let [input-text "This is a secret message."
        input-path (write-tmp "plain.txt" input-text)
        ciphertext-path (tmp-path "cipher.enc")
        output-path (tmp-path "plain-out.txt")
        priv-path (tmp-path "key.priv.json")
        pub-path  (tmp-path "key.pub.json")
        keyfile   (str ciphertext-path ".key0")]

    ;; Generate keypair
    (yap.core/write-public-key! priv-path pub-path)

    ;; Encrypt
    (yap.core/encrypt-file input-path ciphertext-path [pub-path] false)

    ;; Decrypt
    (yap.core/decrypt-file ciphertext-path keyfile priv-path output-path)

    ;; Verify content
    (let [result (slurp output-path)]
      (is (= input-text result)))))
