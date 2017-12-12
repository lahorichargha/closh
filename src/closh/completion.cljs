(ns closh.completion
  (:require [clojure.string]
            [lumo.repl]))

(def child-process (js/require "child_process"))

(defn stream-output [stream cb]
  (let [out #js[]]
    (doto stream
      (.on "data" #(.push out %))
      (.on "end" #(cb nil (.join out "")))
      (.on "error" #(cb % "")))))

(defn get-completions-spawn [cmd args]
  (js/Promise.
    (fn [resolve reject]
      (let [proc (child-process.spawn cmd args #js{:encoding "utf-8"})]
        (stream-output (.-stdout proc)
          (fn [_ stdout]
            (let [completions (if (clojure.string/blank? stdout)
                                #js[]
                                (apply array (clojure.string/split (clojure.string/trim stdout) #"\n")))]
              (resolve completions))))))))

(defn complete-fish [line]
  (-> (get-completions-spawn (str js/process.env.CLOSH_SOURCES_PATH "/scripts/completion/completion.fish") #js[line])
      (.then (fn [completions] (.map completions #(first (clojure.string/split % #"\t"))))))) ; discard the tab-separated description

(defn complete-bash [line]
  (get-completions-spawn (str js/process.env.CLOSH_SOURCES_PATH "/scripts/completion/completion.bash") #js[line]))

(defn complete-zsh [line]
  (get-completions-spawn (str js/process.env.CLOSH_SOURCES_PATH "/scripts/completion/completion.zsh") #js[line]))

(defn complete-lumo [line]
  (js/Promise.
    (fn [resolve reject]
      (try
        (lumo.repl/get-completions line resolve)
        (catch :default e (reject e))))))

(defn join-string [line s]
  (loop [i (count s)]
    (if (zero? i)
      (str line s)
      (let [sub (subs s 0 i)]
        (if (clojure.string/ends-with? line sub)
          (str line (subs s i))
          (recur (dec i)))))))

(defn process-completions [line completions]
  (->> completions
    (map #(join-string line %))
    (filter #(not= line %))))

(defn complete [line cb]
  (-> (js/Promise.all
       #js[(when (re-find #"\([^)]*$" line) ; only send exprs with unmatched paren to lumo
             (complete-lumo line))
           (-> (complete-fish line)
               (.then #(if (seq %) % (complete-bash line)))
               (.then #(if (seq %) % (complete-zsh line))))])
    (.then (fn [completions]
             (->> completions
               (map #(process-completions line %))
               (interpose [""])
               (apply concat)
               (apply array))))
    (.then #(cb nil #js[% line]))
    (.catch #(cb %))))
