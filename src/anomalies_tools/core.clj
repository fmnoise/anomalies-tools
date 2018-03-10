(ns anomalies-tools.core
  (:require [cognitect.anomalies :as a]
            [clojure.spec.alpha :as s]))

(def ^:dynamic *default-category*
  "Default category for new anomaly created with constructor
  or produced with catch-anomaly and its variations if no category specified"
  ::a/fault)

(def ^:dynamic *exception-categories*
  "Map containing Java exception classes and corresponding categories.
  Used to determine category in catch-anomaly and its variations if no category specified"
  {java.lang.Exception ::a/fault})

(defn valid-category?
  "Checks if given category exists in the list of categories"
  {:added "0.1.0"}
  [cat]
  (s/valid? ::a/category cat))

(defmacro with-exception-categories
  "Sets given map as *exception-categories* while executing body"
  {:added "0.1.0"}
  [exception-categories & body]
  `(binding [*exception-categories* ~exception-categories]
     (do ~@body)))

(defmacro with-default-category
  "Sets given category as *default-category* while executing body.
  Given category should be valid one"
  {:added "0.1.0"}
  [default-category & body]
  `(binding [*default-category* ~default-category]
     (do ~@body)))

(defn anomaly?
  "Checks if given value is anomaly"
  {:added "0.1.0"}
  [x]
  (s/valid? ::a/anomaly x))

(defn anomaly
  "Creates new anomally with given category(defaults to ::fault) message(optional) and data(optional)"
  {:added "0.1.0"}
  ([] (anomaly *default-category* nil nil))
  ([cat-msg-data]
   (cond
     (valid-category? cat-msg-data) (anomaly cat-msg-data nil nil)
     (string? cat-msg-data) (anomaly *default-category* cat-msg-data nil)
     :else (anomaly *default-category* nil cat-msg-data)))
  ([cat-msg msg-data]
   (cond
     (and (valid-category? cat-msg) (string? msg-data)) (anomaly cat-msg msg-data nil)
     (valid-category? cat-msg) (anomaly cat-msg nil msg-data)
     (string? cat-msg) (anomaly *default-category* cat-msg msg-data)
     :else (anomaly *default-category* cat-msg msg-data)))
  ([cat msg data]
   {:pre [(valid-category? cat)
          (or (nil? msg) (string? msg))]}
   (cond-> {::a/category cat}
     (some? msg) (assoc ::a/message msg)
     (some? data) (assoc ::a/data data))))

(defn aware
  "Creates anomaly-aware wrapper for given function(1).
  Wrapper calls function if no anomaly argument given and returns argument otherwise."
  {:added "0.1.0"}
  ([f] (fn [x] (if (anomaly? x) x (f x))))
  ([f v] ((aware f) v)))

(defn either
  "If any of given arguments (min 2) is not anomaly, returns it,
  otherwise returns last given argument"
  {:added "0.1.0"}
  [v other & others]
  (cond
    (not (anomaly? v)) v
    (not (anomaly? other)) other
    (= 0 (count others)) other
    (= 1 (count others)) (first others)
    :else (apply either others)))

(defn chain
  "Creates anomaly-aware function call chain for given value.
  If given value is anomally or any function in chain returns anomaly,
  it's returned immediately, rest of the chain is skipped"
  {:added "0.1.0"}
  [v & f]
  (if-let [step (first f)]
    (aware #(apply chain % (rest f)) (aware step v))
    v))

(defn caught
  "Creates anomaly-centric function call chain for given value.
  If given value is not anomally, it's returned immediately and chain is skipped.
  If some function in chain returns anomaly, then new anomaly comes to next function in chain,
  otherwise initial anomaly is passed to next function"
  {:added "0.1.0"}
  [v & f]
  (if (anomaly? v)
    (if-let [step (first f)]
      (let [res (step v)] (apply caught (if (anomaly? res) res v) (rest f)))
      v)
    v))

;; ??? check exceptions inheritance using isa?
(defmacro catch-anomaly
  "Wraps given body to try/catch, turning thrown exceptions into anomalies.
  First argument should be map of settings or nil(each setting is optional)
  :category - assigns given category to produced anomaly (*default-category* is used if nothing specied)
  :message - assigns given message to produced anomaly (exception message when present is used if nothing specified)
  :data - assigns given value as data of produced anomaly (exception itself is used if nothing specified)
  :only - set of exceptions which will be turned into anomalies(other exceptions will be thrown)
  :except - set of exceptions which will be thrown(other exceptions will be turned into anomalies)
  :only and :except are mutually exclusive"
  {:added "0.1.0"}
  [{:keys [category message data only except]} & body]
  {:pre [(or (nil? category) (valid-category? category))
         (or (nil? message) (string? message))
         (or (nil? only) (set? only))
         (or (nil? except) (set? except))
         (or (empty? only) (empty? except))]}
  `(try
     (do ~@body)
     (catch Throwable t#
       (let [klass# (.getClass t#)
             category# (or ~category (get *exception-categories* klass# *default-category*))
             msg# (or ~message (.getMessage t#))
             data# (or ~data t#)]
         (if (and (nil? (get ~except klass#))
                  (or (empty? ~only) (some? (get ~only klass#))))
           (anomaly category# msg# data#)
           (throw t#))))))

(defmacro catch-only
  "Turns exceptions of given classes set into anomalies, other exceptions are thrown"
  {:added "0.1.0"}
  [ex & body]
  `(catch-anomaly {:only ~ex} ~@body))

(defmacro catch-except
  "Throws exceptions of given classes, other exceptions are turned into anomalies"
  {:added "0.1.0"}
  [ex & body]
  `(catch-anomaly {:except ~ex} ~@body))

(defmacro catch-all
  "Turns all exceptions into anomalies"
  {:added "0.1.0"}
  [& body]
  `(catch-anomaly nil ~@body))

(defmacro -alet-impl
  [bindings & body]
  (if-let [[name expression] (first bindings)]
    `(let [result# ~expression]
       (aware
         (fn [~name] (-alet-impl ~(rest bindings) ~@body))
         result#))
    `(do ~@body)))

(defmacro alet
  "Anomaly aware version of let, if any of given bindings is evaluated to anomaly, it's returned immediately,
  otherwise body evaluation result is returned"
  {:added "0.1.0"}
  [bindings & body]
  `(-alet-impl ~(partition 2 bindings) ~@body))

(defmacro aware->
  "When expr is not anomaly, threads it into the first form (via ->),
  and when that result is not anomaly, through the next etc"
  {:added "0.1.0"}
  [expr & forms]
  (let [g (gensym)
        steps (map (fn [step] `(if (anomaly? ~g) ~g (-> ~g ~step)))
                   forms)]
    `(let [~g ~expr
           ~@(interleave (repeat g) (butlast steps))]
       ~(if (empty? steps)
          g
          (last steps)))))

(defmacro aware->>
  "When expr is not anomaly, threads it into the first form (via ->>),
  and when that result is not anomaly, through the next etc"
  {:added "0.1.0"}
  [expr & forms]
  (let [g (gensym)
        steps (map (fn [step] `(if (anomaly? ~g) ~g (->> ~g ~step)))
                   forms)]
    `(let [~g ~expr
           ~@(interleave (repeat g) (butlast steps))]
       ~(if (empty? steps)
          g
          (last steps)))))

;; aliases

(def busy (partial anomaly ::a/busy))
(def conflict (partial anomaly ::a/conflict))
(def fault (partial anomaly ::a/fault))
(def forbidden (partial anomaly ::a/forbidden))
(def incorrect (partial anomaly ::a/incorrect))
(def interrupted (partial anomaly ::a/interrupted))
(def not-found (partial anomaly ::a/not-found))
(def unavailable (partial anomaly ::a/unavailable))
(def unsupported (partial anomaly ::a/unsupported))

(def message ::a/message)
(def data ::a/data)
(def category ::a/category)

(def !! anomaly)
