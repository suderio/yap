(ns yap.core
  (:gen-class)
  (:require [clojure.tools.cli :refer [parse-opts]] [clojure.string])
  (:import [java.nio.file Files Paths StandardOpenOption]
           [java.io File ByteArrayInputStream ByteArrayOutputStream FileWriter]
           [java.util Base64]
           [com.google.crypto.tink.aead AeadConfig AeadFactory AeadKeyTemplates]
           [com.google.crypto.tink.hybrid HybridConfig HybridEncryptFactory HybridDecryptFactory HybridKeyTemplates]
           [com.google.crypto.tink KeysetHandle CleartextKeysetHandle JsonKeysetReader JsonKeysetWriter]))

;; Static associated data for Tink encryption/decryption context.
(def context-bytes (.getBytes "file-encryption"))

;; CLI option definitions for mode selection and file input/output.
(def cli-options
  [["-m" "Mode: genkeys, encrypt, decrypt, adduser"]
   ["-i" "Input file path"]
   ["-o" "Output file path"]
   ["--pub" "Public key files (comma-separated)" :parse-fn #(clojure.string/split % #",")]
   ["--key" "Encrypted key file"]
   ["--priv" "Private key file"]
   ["--b64" "Output encrypted keys in base64 format"]])

;; Initialize Tink configuration for AEAD and Hybrid encryption.
(defn init []
  (AeadConfig/register)
  (HybridConfig/register))

;; Generates a private/public keypair and saves both as JSON keysets.
(defn write-public-key! [priv-path pub-path]
  (let [priv-kh (KeysetHandle/generateNew (HybridKeyTemplates/ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM))
        pub-kh (.getPublicKeysetHandle priv-kh)]
    (CleartextKeysetHandle/write priv-kh (JsonKeysetWriter/withFile (File. priv-path)))
    (CleartextKeysetHandle/write pub-kh (JsonKeysetWriter/withFile (File. pub-path)))))

;; Outputs a static reflection config file for GraalVM native-image builds.
(defn generate-reflection-config! [out-file]
  (let [entries [{"name" "com.google.crypto.tink.aead.AeadConfig", "allDeclaredMethods" true, "allDeclaredConstructors" true}
                 {"name" "com.google.crypto.tink.hybrid.HybridConfig", "allDeclaredMethods" true, "allDeclaredConstructors" true}
                 {"name" "com.google.crypto.tink.KeysetHandle", "allDeclaredMethods" true}
                 {"name" "com.google.crypto.tink.CleartextKeysetHandle", "allDeclaredMethods" true}
                 {"name" "com.google.crypto.tink.JsonKeysetReader", "allDeclaredMethods" true}
                 {"name" "com.google.crypto.tink.JsonKeysetWriter", "allDeclaredMethods" true}]]
    (with-open [w (FileWriter. out-file)]
      (.write w (pr-str entries)))))

;; Encrypts a byte array AES key using a public hybrid key handle.
(defn encrypt-symmetric-key [aes-key-bytes public-kh]
  (let [encryptor (HybridEncryptFactory/getPrimitive public-kh)]
    (.encrypt encryptor aes-key-bytes context-bytes)))

;; Encrypts a file symmetrically with AES and encrypts that AES key for each recipient.
;; Produces ciphertext and multiple encrypted key files (.key0, .key1, ...) or base64 outputs.
(defn encrypt-file [input-path out-path pub-files base64-output?]
  (let [plaintext (Files/readAllBytes (Paths/get input-path (make-array String 0)))
        aead-kh (KeysetHandle/generateNew (AeadKeyTemplates/AES128_GCM))
        aead (AeadFactory/getPrimitive aead-kh)
        ciphertext (.encrypt aead plaintext context-bytes)
        aes-raw (let [bos (ByteArrayOutputStream.)]
                  (CleartextKeysetHandle/write aead-kh (JsonKeysetWriter/withOutputStream bos))
                  (.toByteArray bos))]
    (Files/write (Paths/get out-path (make-array String 0)) ciphertext  (into-array java.nio.file.OpenOption
                                                                                    [StandardOpenOption/CREATE
                                                                                     StandardOpenOption/WRITE]))
    (doseq [[pub-file idx] (map vector pub-files (range))]
      (let [pub-kh (CleartextKeysetHandle/read (JsonKeysetReader/withFile (File. pub-file)))
            enc-key (encrypt-symmetric-key aes-raw pub-kh)
            out-path-key (str out-path ".key" idx)]
        (if base64-output?
          (spit out-path-key (.encodeToString (Base64/getEncoder) enc-key))
          (Files/write (Paths/get out-path-key (make-array String 0)) enc-key (into-array java.nio.file.OpenOption
                                                                                    [StandardOpenOption/CREATE
                                                                                     StandardOpenOption/WRITE])))))))

;; Adds a new encrypted key for a new recipient without touching the ciphertext.
(defn add-user [encrypted-key-template new-pub-file idx base64-output?]
  (let [aes-key-raw (Files/readAllBytes (Paths/get encrypted-key-template (make-array String 0)))
        pub-kh (CleartextKeysetHandle/read (JsonKeysetReader/withFile (File. new-pub-file)))
        enc-key (encrypt-symmetric-key aes-key-raw pub-kh)
        out-path-key (str encrypted-key-template ".key" idx)]
    (if base64-output?
      (spit out-path-key (.encodeToString (Base64/getEncoder) enc-key))
      (Files/write (Paths/get out-path-key (make-array String 0)) enc-key (into-array java.nio.file.OpenOption
                                                                                    [StandardOpenOption/CREATE
                                                                                     StandardOpenOption/WRITE])))))

;; Decrypts a file by reading a base64 or binary-wrapped AES key, decrypting it, and using it to decrypt the ciphertext.
(defn decrypt-file [ciphertext-path key-path private-path out-path]
  (let [ciphertext (Files/readAllBytes (Paths/get ciphertext-path (make-array String 0)))
        raw-key (slurp key-path)
        enc-key (try (.decode (Base64/getDecoder) raw-key)
                     (catch Exception _ (Files/readAllBytes (Paths/get key-path (make-array String 0)))))
        priv-kh (CleartextKeysetHandle/read (JsonKeysetReader/withFile (File. private-path)))
        hybrid-decryptor (HybridDecryptFactory/getPrimitive priv-kh)
        aes-key-raw (.decrypt hybrid-decryptor enc-key context-bytes)
        aes-kh (CleartextKeysetHandle/read (JsonKeysetReader/withInputStream (ByteArrayInputStream. aes-key-raw)))
        aead (AeadFactory/getPrimitive aes-kh)
        plaintext (.decrypt aead ciphertext context-bytes)]
    (Files/write (Paths/get out-path (make-array String 0)) plaintext (into-array java.nio.file.OpenOption
                                                                                    [StandardOpenOption/CREATE
                                                                                     StandardOpenOption/WRITE]))))

;; Outputs simple CLI usage instructions for end-users.
(defn usage []
  (println "\nUsage:")
  (println "  -m genkeys -o base-name")
  (println "  -m encrypt -i input -o output --pub pub1.json,pub2.json,... [--b64]")
  (println "  -m decrypt -i encrypted-file -o output --key keyfile --priv private-key.json")
  (println "  -m adduser -i aes-key-template --pub new-pub.json -o idx [--b64]")
  (println "  -m reflect -o reflect-config.json"))

;; Entry point for CLI logic: handles different modes and dispatches to appropriate actions.
(defn -main [& args]
  (init)
  (let [{:keys [options errors]} (parse-opts args cli-options)]
    (cond
      (:help options) (usage)

      (= (:m options) "genkeys")
      (do (write-public-key! (str (:o options) "_priv1.json") (str (:o options) "_pub1.json"))
          (write-public-key! (str (:o options) "_priv2.json") (str (:o options) "_pub2.json"))
          (println "Keys generated."))

      (= (:m options) "encrypt")
      (encrypt-file (:i options) (:o options) (:pub options) (:b64 options))

      (= (:m options) "decrypt")
      (decrypt-file (:i options) (:key options) (:priv options) (:o options))

      (= (:m options) "adduser")
      (add-user (:i options) (first (:pub options)) (:o options) (:b64 options))

      (= (:m options) "reflect")
      (do (generate-reflection-config! (:o options))
          (println "Reflection config written."))

      :else (usage))))
