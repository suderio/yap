(ns yap.tar-utils
  (:require [clojure.java.io :as io] [clojure.string :as str])
  (:import [java.io File FileInputStream FileOutputStream ByteArrayOutputStream]
           [java.util.zip GZIPOutputStream GZIPInputStream]
           [org.apache.commons.compress.archivers.tar TarArchiveInputStream
            TarArchiveOutputStream
            TarArchiveEntry]))

(defn gzip-extension? [path]
  (or (.endsWith path ".tar.gz") (.endsWith path ".tgz")))

(defn open-tar-output [tar-path]
  (let [fos (FileOutputStream. tar-path)
        out-stream (if (gzip-extension? tar-path)
                     (GZIPOutputStream. fos)
                     fos)]
    (TarArchiveOutputStream. out-stream)))

(defn open-tar-input [tar-path]
  (let [fis (FileInputStream. tar-path)
        in-stream (if (gzip-extension? tar-path)
                    (GZIPInputStream. fis)
                    fis)]
    (TarArchiveInputStream. in-stream)))

(defn list-files
  "Lists all file entries in the tar archive matching optional glob pattern."
  ([tar-path] (list-files tar-path "*"))
  ([tar-path glob-pattern]
   (with-open [tar-in (open-tar-input tar-path)]
     (let [matcher (re-pattern (-> glob-pattern
                                   (str/replace "." "\\.")
                                   (str/replace "*" ".*")))]
       (loop [entries []]
         (let [entry (.getNextTarEntry tar-in)]
           (if entry
             (recur (if (re-matches matcher (.getName entry))
                      (conj entries (.getName entry))
                      entries))
             entries)))))))

(defn read-file
  "Reads a single file from the tar archive and returns its content as a byte array."
  [tar-path target-name]
  (with-open [tar-in (open-tar-input tar-path)
              bos (ByteArrayOutputStream.)]
    (loop []
      (let [entry (.getNextTarEntry tar-in)]
        (cond
          (nil? entry) nil
          (= (.getName entry) target-name)
          (do (io/copy tar-in bos)
              (.toByteArray bos))
          :else (recur))))))

(defn delete-files
  "Deletes files from the tar archive that match the glob pattern."
  [tar-path glob-pattern]
  (let [tmp-path (str tar-path ".tmp")
        matcher (re-pattern (-> glob-pattern
                                (str/replace "." "\\.")
                                (str/replace "*" ".*")))]
    (with-open [tar-in (open-tar-input tar-path)
                tar-out (open-tar-output tmp-path)]
      (loop []
        (let [entry (.getNextTarEntry tar-in)]
          (when entry
            (let [name (.getName entry)]
              (when-not (re-matches matcher name)
                (let [buf (byte-array (.getSize entry))]
                  (.read tar-in buf)
                  (let [new-entry (TarArchiveEntry. name)]
                    (.setSize new-entry (alength buf))
                    (.putArchiveEntry tar-out new-entry)
                    (.write tar-out buf)
                    (.closeArchiveEntry tar-out)))))
            (recur)))))
    (.delete (File. tar-path))
    (.renameTo (File. tmp-path) (File. tar-path))))

(defn write-file
  "Adds a new file to the tar archive. Overwrites if it exists."
  [tar-path file-path entry-name]
  (let [existing (when (.exists (File. tar-path))
                   (list-files tar-path))
        needs-overwrite (some #(= % entry-name) existing)
        tmp-path (str tar-path ".tmp")]
    (with-open [tar-out (open-tar-output tmp-path)]
      (when (.exists (File. tar-path))
        (with-open [tar-in (open-tar-input tar-path)]
          (loop []
            (let [entry (.getNextTarEntry tar-in)]
              (when entry
                (when-not (= (.getName entry) entry-name)
                  (let [buf (byte-array (.getSize entry))]
                    (.read tar-in buf)
                    (let [new-entry (TarArchiveEntry. (.getName entry))]
                      (.setSize new-entry (alength buf))
                      (.putArchiveEntry tar-out new-entry)
                      (.write tar-out buf)
                      (.closeArchiveEntry tar-out))))
                (recur)))))
      ;; add new file
        (let [file (File. file-path)
              entry (TarArchiveEntry. file entry-name)]
          (.putArchiveEntry tar-out entry)
          (io/copy (FileInputStream. file) tar-out)
          (.closeArchiveEntry tar-out)))
      (.delete (File. tar-path))
      (.renameTo (File. tmp-path) (File. tar-path)))))
