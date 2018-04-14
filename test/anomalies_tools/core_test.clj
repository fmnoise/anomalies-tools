(ns anomalies-tools.core-test
  (:require [clojure.test :refer :all]
            [cognitect.anomalies :as a]
            [anomalies-tools.core :refer :all]))

(deftest anomaly-constructor-test
  (let [cat ::a/busy msg "Oops" data {:user 1}]
    (is (= (anomaly cat)
           {::a/category cat}))
    (is (= (anomaly msg)
           {::a/category *default-category*
            ::a/message msg}))
    (is (= (anomaly data)
           {::a/category *default-category*
            ::a/data data}))
    (is (= (anomaly cat msg data)
           {::a/category cat
            ::a/message msg
            ::a/data data}))
    (is (= (anomaly cat msg)
           {::a/category cat
            ::a/message msg}))
    (is (= (anomaly cat data)
           {::a/category cat
            ::a/data data}))
    (is (= (anomaly msg data)
           {::a/category *default-category*
            ::a/message msg
            ::a/data data}))
    (is (= (anomaly msg cat)
           {::a/category *default-category*
            ::a/message msg
            ::a/data cat}))
    (is (= (anomaly msg msg)
           {::a/category *default-category*
            ::a/message msg
            ::a/data msg}))
    (is (= (anomaly cat cat)
           {::a/category cat
            ::a/data cat}))
    (is (thrown? AssertionError (anomaly data msg)))
    (is (thrown? AssertionError (anomaly data data)))
    (is (thrown? AssertionError (anomaly data cat)))
    (is (thrown? AssertionError (anomaly data cat msg)))
    (is (thrown? AssertionError (anomaly data msg cat)))
    (is (thrown? AssertionError (anomaly msg data cat)))
    (is (thrown? AssertionError (anomaly msg cat data)))
    (is (thrown? AssertionError (anomaly cat data msg)))
    (is (thrown? AssertionError (anomaly :wrong-cat msg)))
    (is (thrown? AssertionError (anomaly :wrong-cat data)))
    (is (thrown? AssertionError (anomaly :wrong-cat msg data)))
    (is (thrown? AssertionError (anomaly :wrong-cat :wrong-cat)))
    (is (= (busy)
           {::a/category ::a/busy}))
    (is (= (busy msg)
           {::a/category ::a/busy
            ::a/message msg}))
    (is (= (busy data)
           {::a/category ::a/busy
            ::a/data data}))
    (is (= (busy msg data)
           {::a/category ::a/busy
            ::a/message msg
            ::a/data data}))
    (is (thrown? AssertionError (busy data msg)))))

(deftest anomaly-detector-test
  (is (anomaly? (anomaly)))
  (is (anomaly? (-> (anomaly) (assoc :attr "value"))))
  (is (not (anomaly? (-> (anomaly) (dissoc ::a/category)))))
  (is (not (anomaly? (-> (anomaly) (assoc ::a/category :wrong-category)))))
  (is (not (anomaly? {:category :busy :message "Oops" :data 1}))))

(deftest aware-test
  (let [ainc (aware inc)
        anom (anomaly)]
    (is (= (ainc 1) 2))
    (is (= (ainc anom) anom))
    (is (= (->> 1 anomaly (aware inc))
           (anomaly 1)))))

(deftest rescue-test
  (let [handle (rescue data)
        anom (anomaly -1)]
    (is (= (handle 1) 1))
    (is (= (handle anom) -1))))

(deftest caught-test
  (let [msg "Oops"
        anom (anomaly msg)]
    (is (= (caught anom message category)
           anom))
    (is (= (caught anom prn)
           anom))
    (is (= (caught anom #(-> % message busy))
           (busy msg)))))

(deftest either-test
  (is (= (either (anomaly) (anomaly) (anomaly) (anomaly) (anomaly) 1)
         1))
  (is (= (either (anomaly 1) (anomaly 2) (anomaly 3) (anomaly 4) (anomaly 5))
         (anomaly 5)))
  (is (= (either (anomaly 1) (anomaly 2))
         (anomaly 2))))

(deftest aware-threading-macro-test
  (is (= (aware-> 1 inc inc) 3))
  (is (= (aware-> 1 anomaly inc)
         (anomaly 1)))
  (is (= (aware->> [1 2 3] (map inc))
         '(2 3 4)))
  (is (= (aware->> [1 2 3] anomaly (map inc))
         (anomaly [1 2 3]))))

(deftest chain-test
  (is (= (chain [1 2 3] (partial map inc))
         '(2 3 4)))
  (is (= (chain [1 2 3] anomaly (partial map inc))
         (anomaly [1 2 3]))))

(deftest alet-test
  (is (= (alet [a 1 b 2] (+ a b)) ;; => 3
         3))
  (is (= (alet [a 1
                  b (anomaly)
                   _ (throw (Exception.))]
           (+ a b))
         (anomaly)))
  (is (= (alet [a 1
                  b (anomaly)]
           (throw (Exception.)))
         (anomaly))))

(deftest exceptions-catch-test
  (let [anom (catch-all (/ 1 0))]
    (is (= (message anom) "Divide by zero"))
    (is (= (category anom) *default-category*))
    (is (= (-> anom data class) ArithmeticException)))

  (let [anom (catch-except #{NullPointerException} (/ 1 0))]
    (is (= (message anom) "Divide by zero"))
    (is (= (category anom) *default-category*))
    (is (= (-> anom data class) ArithmeticException)))

  (let [anom (catch-only #{ArithmeticException} (/ 1 0))]
    (is (= (message anom) "Divide by zero"))
    (is (= (category anom) *default-category*))
    (is (= (-> anom data class) ArithmeticException)))

  (let [anom (catch-anomaly
              {:category ::a/conflict
               :message "Oops"
               :data (atom 1)}
              (/ 1 0))]
    (is (= (message anom) "Oops"))
    (is (= (category anom) ::a/conflict))
    (is (= (-> anom data deref) 1)))

  (let [anom (catch-anomaly
              {:category ::a/conflict}
              (/ 1 0))]
    (is (= (message anom) "Divide by zero"))
    (is (= (category anom) ::a/conflict))
    (is (= (-> anom data class) ArithmeticException)))

  (let [anom (catch-anomaly
              {:only #{ArithmeticException}}
              (/ 1 0))]
    (is (= (message anom) "Divide by zero"))
    (is (= (category anom) *default-category*))
    (is (= (-> anom data class) ArithmeticException)))

  (let [anom (catch-anomaly
              {:except #{NullPointerException}}
              (/ 1 0))]
    (is (= (message anom) "Divide by zero"))
    (is (= (category anom) *default-category*))
    (is (= (-> anom data class) ArithmeticException)))

  (is (= (catch-all (+ 1 2)) 3))

  (is (thrown?
       NullPointerException
       (catch-except #{NullPointerException} (/ 1 nil))))
  (is (thrown?
       NullPointerException
       (catch-only #{ArithmeticException} (/ 1 nil))))
  (is (thrown?
       NullPointerException
       (catch-only #{Exception} (/ 1 nil))))
  (is (thrown?
       NullPointerException
       (catch-anomaly {:except #{NullPointerException}} (/ 1 nil))))
  (is (thrown?
       NullPointerException
       (catch-anomaly {:only #{ArithmeticException}} (/ 1 nil)))))

(deftest defaults-test
  (let [anom (with-exception-categories
               {NullPointerException ::a/unsupported}
               (catch-all (/ 1 nil)))]
    (is (= (category anom)
           ::a/unsupported)))

  (let [anom (with-default-category ::a/conflict (catch-all (/ 1 0)))]
    (is (= (category anom)
           ::a/conflict)))

  (is (= (with-default-category ::a/conflict (anomaly))
         (conflict))))
