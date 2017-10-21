(ns tensors.examples.bilstm-tag
  (:gen-class)
  (:require [tensors.node :as node]
            [tensors.rnn :as rnn]
            [tensors.compute :as compute]
            [tensors.embeddings :as embeddings]
            [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]]
            [tensors.neanderthal-ops :as no]
            [tensors.model :as model]
            [tensors.report :as report]
            [tensors.computation-graph :as cg]
            [tensors.train :as train]
            [tensors.module :as module]))

(defn build-graph [words embed rnn-cell])

(def cli-options
  ;; An option with a required argument
  [["-d" "--train-file PATH" "path to data"
    :default "data/sentiment-10k.txt"]
   ["-e" "--embed-file PATH" "path to data"
    :default "data/small-glove.50d.txt"]
   ["-n" "--num-classes PATH" "path to data"
    :default 2
    :parse-fn #(Integer/parseInt ^String %)]
   ["-s" "--emb-size NUM" "size of embedding data"
    :default 50
    :parse-fn #(Integer/parseInt ^String %)]
   ["-l" "--lstm-size NUM" "lstm size"
    :default 25
    :parse-fn #(Integer/parseInt ^String %)]
   ["-x" "--num-data DATA"
    :default 10000
    :parse-fn #(Integer/parseInt ^String %)]])

(defn load-embeddings [opts]
  (let [factory (no/factory)]
    (embeddings/fixed-embedding
     (no/factory)
     (:emb-size opts)
     (-> opts :embed-file io/reader embeddings/read-text-embedding-pairs))))

(defn lstm-sent-classifier [model word-emb lstm-size num-classes]
  (let [emb-size (embeddings/embedding-size word-emb)
        cell (node/with-scope "forward"
               (rnn/lstm-cell model emb-size lstm-size))
        rev-cell (node/with-scope "reverse"
                   (rnn/lstm-cell model emb-size lstm-size))
        factory (model/tensor-factory model)
        hidden-size (* 2 lstm-size)
        hidden->logits (node/with-scope "hidden->logits"
                         (module/affine model num-classes [hidden-size]))]
    (reify
      module/Module
      ;; build logits
      (graph [this sent]
        (when-let [inputs (seq (embeddings/sent-nodes factory word-emb sent))]
          (let [[fwd-outputs _] (rnn/build-seq cell inputs)
                [rev-outputs _] (rnn/build-seq rev-cell (reverse inputs))
                concat-hidden (cg/concat 0 (last fwd-outputs) (last rev-outputs))]
            (module/graph hidden->logits concat-hidden))))
      ;; build loss node
      (graph [this sent label]
        (when-let [logits (module/graph this sent)]
          (let [label-node (node/constant "label" factory [label])]
            (cg/cross-entropy-loss logits label-node)))))))

(defn load-data [path]
  (for [line (line-seq (io/reader path))
        :let [[tag & sent] (.split (.trim ^String line) " ")]]
    [sent (double (Integer/parseInt tag))]))

(defn train [opts]
  (let [train-data (take (:num-data opts) (load-data (:train-file opts)))
        test-data (take (:num-data opts) (load-data (:test-file opts)))
        emb (load-embeddings opts)
        gen-batches #(partition-all 1000 train-data)
        factory (no/factory)
        m (model/simple-param-collection factory)
        ;; need to provide forward-computed graph for loss
        classifier (lstm-sent-classifier m emb (:lstm-size opts) (:num-classes opts))
        gb (fn [[sent tag]]
             (module/forward! factory classifier sent tag))
        train-opts {:num-iters 100
                    :iter-reporter (report/test-accuracy
                                    (constantly train-data)
                                    (fn [sent]
                                      (module/predict factory classifier sent)))
                    :learning-rate 0.01}]
    (println "Params " (map first (seq m)))
    (println "Total " (model/total-num-params m))
    (train/train! m gb gen-batches train-opts)))

(comment
  (do
    (def opts {:embed-file "data/small-glove.50d.txt"
               :lstm-size 10
               :num-classes 2
               :num-data 100
               :train-file "data/sentiment-train10k.txt"
               :test-file "data/sentiment-test10k.txt"
               :emb-size 50})
    (def factory (no/factory))
    (def emb (load-embeddings opts))
    (def model (model/simple-param-collection factory))
    (def cell (rnn/lstm-cell model (:emb-size opts) (:lstm-size opts)))
    (def sent-words (gen-sentence 5))
    (def sent-nodes (mapv (fn [w]
                            (node/constant factory (embeddings/lookup emb w)))
                          sent-words))
    (def hiddens (rnn/build-seq cell sent-nodes))
    (def gb (graph-builder model emb (:lstm-size opts) (:num-classes opts)))
    ))

(defn -main [& args]
  (let [parse (parse-opts args cli-options)]
    (train (:options parse))))