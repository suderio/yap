(ns yap.tar-utils-test
  (:require [clojure.test :refer :all] [clojure.string]
            [yap.tar-utils :as tar])
  (:import [java.nio.file Files Paths]
           [java.io File]))

(defn tmp-path [& parts]
  (str (System/getProperty "java.io.tmpdir") "/" (clojure.string/join "-" parts)))

(defn write-tmp-file [filename content]
  (let [path (tmp-path filename)]
    (spit path content)
    path))

(deftest tar-utils-test-suite
  (let [tar-path (tmp-path "test.tar.gz")
        file-a (write-tmp-file "fileA.txt" "content-a")
        file-b (write-tmp-file "fileB.txt" "content-b")
        file-c (write-tmp-file "fileC.txt" "content-c")]

    ;; Clean up any previous test file
    (when (.exists (File. tar-path))
      (.delete (File. tar-path)))

    ;; Write file A
    (tar/write-file tar-path file-a "dir/fileA.txt")
    (is (= ["dir/fileA.txt"] (tar/list-files tar-path)))

    ;; Add file B
    (tar/write-file tar-path file-b "dir/fileB.txt")
    (is (= #{"dir/fileA.txt" "dir/fileB.txt"}
           (set (tar/list-files tar-path))))

    ;; Add file C in root
    (tar/write-file tar-path file-c "fileC.txt")
    (is (= #{"dir/fileA.txt" "dir/fileB.txt" "fileC.txt"}
           (set (tar/list-files tar-path))))

    ;; Read and validate fileC.txt
    (let [bytes (tar/read-file tar-path "fileC.txt")
          content (String. bytes)]
      (is (= "content-c" content)))

    ;; Delete dir/fileB.txt
    (tar/delete-files tar-path "*/fileB.txt")
    (is (= #{"dir/fileA.txt" "fileC.txt"}
           (set (tar/list-files tar-path))))

    ;; Delete all files
    (tar/delete-files tar-path "*")
    (is (empty? (tar/list-files tar-path)))))
