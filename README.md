# anomalies [![Build Status](https://travis-ci.org/dawcs/anomalies-tools.svg?branch=master)](https://travis-ci.org/dawcs/anomalies-tols)

Functional programming tools for https://github.com/cognitect-labs/anomalies

Library is currenty in alpha, so anything may be changed in future versions.

## Usage

Leiningen dependency information:
```
[dawcs/anomalies-tools "0.1.2"]
```

Maven dependency information:
```
<dependency>
  <groupId>dawcs</groupId>
  <artifactId>anomalies-tools</artifactId>
  <version>0.1.2</version>
</dependency>
```

```clojure
(require '[anomalies-tools.core :as at :refer [!!]])
(require '[cognitect.anomalies :as a])

(defn get-value-with-fake-connection
  [value connection-timeout deref-timeout]
  (-> (Thread/sleep connection-timeout)
      (future value)
      (deref deref-timeout {::a/category ::a/busy
                            ::a/message "Connection timeout"})))

(get-value-with-fake-connection "hello" 1 100) ;; => "hello"
(get-value-with-fake-connection "hello" 100 1)
;; => #:cognitect.anomalies{:category :cognitect.anomalies/busy, :message "Connection timeout"}

;; is map syntax too verbose?
(at/anomaly ::a/busy "Connection timeout")
;; => #:cognitect.anomalies{:category :cognitect.anomalies/busy, :message "Connection timeout"}

;; still too much typing? there's short form
(!! ::a/busy "Connection timeout")
;; => #:cognitect.anomalies{:category :cognitect.anomalies/busy, :message "Connection timeout"}

;; want even more succinct form?
(at/busy "Connection timeout")
;; => #:cognitect.anomalies{:category :cognitect.anomalies/busy, :message "Connection timeout"}

;; no need for message?
(at/busy)
;; => #:cognitect.anomalies{:category :cognitect.anomalies/busy}

;; happy with one category to cover them all? :fault is default one
(!!)
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}

;; need to store some additional data along with message?
(!! ::a/forbidden "Cannot perform operation" {:user-id 2128506})
;; => #:cognitect.anomalies{:category :cognitect.anomalies/forbidden, :message "Cannot perform operation", :data {:user-id 2128506}}
(!! "Cannot perform operation" {:user-id 2128506})
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :message "Cannot perform operation", :data {:user-id 2128506}}

;; prefer just data instead of message?
(!! ::a/forbidden {:user-id 2128506})
;; => #:cognitect.anomalies{:category :cognitect.anomalies/forbidden, :data {:user-id 2128506}}
(!! {:user-id 2128506})
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :data {:user-id 2128506}}

;; so what we can do with anomaly?
;; first, we can check if value is anomaly
(at/anomaly? (!!)) ;; => true
(at/anomaly? {:user 1}) ;; => false
(at/anomaly? (Exception. "Bad stuff")) ;; => false

;; do you prefer imperative error checking?
(let [result (do-stuff)]
  (if (at/anomaly? result)
    (say-oooops result)
    (say-hooray result)))

;; that's fine but also there are much better functional tools
;; let's start from functions
(inc 1) ;; => 2
(inc (!!)) ;; BOOOM!!! Unhandled java.lang.ClassCastException clojure.lang.PersistentArrayMap cannot be cast to java.lang.Number

;; let's make it aware of anomalies
(def ainc (at/aware inc))
(ainc 1) ;; => 2
(ainc (!!)) ;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}

;; `aware` accepts function as first argument which makes it perfect for `->>` macro
(->> 1 (at/aware inc) (at/aware str)) ;; "2"
(->> (!!) (at/aware inc) (at/aware str))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}

;; anomalies aware chain can be also done using macros similar to `some->` and `some->>`
(at/aware-> 1 inc) ;; => 2
(at/aware-> (!!) inc) ;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}
(at/aware-> 1 (!!) inc) ;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :data 1}

(at/aware->> [1 2 3] (map inc)) ;; => (2 3 4)
(at/aware->> (!!) (map inc))   ;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}
(at/aware->> [1 2 3] (!! ::a/conflict "Ooops") (map inc))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/conflict, :message "Ooops", :data [1 2 3]}

;; there's also functional version of `chain` if macros magic is not desired
(at/chain [1 2 3] (partial map inc)) ;; => (2 3 4)
(at/chain [1 2 3] (partial at/unsupported) (partial map inc))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/unsupported, :data [1 2 3]}

;; `caught` can help in the case if we want to handle anomaly somehow and then return it
;; the following code prints anomaly message and category and returns given anomaly
(at/caught
  (at/forbidden "Bad password" {:user-id 2128506})
  (comp prn ::a/message)
  (comp prn ::a/category))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/forbidden, :message "Bad password", :data {:user-id 2128506}}

;; if given value is not anomaly, then chain is skipped and this value is returned
(at/caught 1 (comp prn ::a/message)) ;; => 1

;; if some chain function returns another anomaly, it's passed to next function in chain
(at/caught
  (at/conflict "Uh-oh")
  (fn [x] (at/busy x)) ;; producing new anomaly from given one
  (comp prn at/category)) ;; prints :busy
;; => #:cognitect.anomalies{:category :cognitect.anomalies/busy, :data #:cognitect.anomalies{:category :cognitect.anomalies/conflict, :message "Uh-oh"}}

;; `caught` and `chain` accepts value as first argument so can be used together in `->` macro
(-> "hello"
     at/anomaly
     (at/chain clojure.string/upper-case)
     (at/caught (comp prn at/message))) ;; prints anomaly message to console
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :message "hello"}

;; returning to imperative error handling example, we can rewrite it using functional chain
(-> (do-stuff)
    (at/chain say-hooray)
    (at/caught say-oooops))

;; what about fallback to default?
;; `either` allows to choose among anomaly and some default non-anomaly value
(at/either (!!) 1) ;; => 1
(apply at/either [(at/busy) (at/fault) (at/conflict) (at/not-found) 1]) ;; => 1

;; if only anomaly values given, `either` returns last given value
(at/either (at/busy) (at/unsupported))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/unsupported}

;; and it's also very useful in `->` marco chain
(-> "hello"
    at/anomaly
    (at/chain clojure.string/upper-case)
    (at/caught prn) ;; prints anomaly to console
    (at/either "goodbye"))
;; => "goodbye"

;; `alet` is anomalies aware version of `let` macro
(at/alet [a 1 b 2] (+ a b)) ;; => 3
(at/alet [a 1 b (!!)] (+ a b))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}

;; and sometimes you need to deal with Java exceptions
(at/catch-all (/ 1 0))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :message "Divide by zero", :data #error {...}

;; need to throw some exceptions and catch other ones?
(at/catch-except #{NullPointerException} (/ 1 0))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :message "Divide by zero", :data #error {...}
(at/catch-except #{NullPointerException} (/ 1 nil)) ;; throws java.lang.NullPointerException

;; catching only certain exceptions required?
(at/catch-only #{NullPointerException} (/ 1 0)) ;; throws java.lang.ArithmeticException
(at/catch-only #{NullPointerException} (/ 1 nil))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :data #error {...}

;; and full control for setting category, message and data of produced anomaly is also possible
(at/catch-anomaly
 {:category ::a/conflict
  :message "Uh-oh"
  :data (atom 1)}
 (/ 1 0))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/conflict, :message "Uh-oh", :data #atom[1 0x1ef83f75]}

(at/catch-anomaly
 {:message "Uh-oh"
  :only #{ArithmeticException}}
 (/ 1 0))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :message "Uh-oh", :data #error {...}

(at/catch-anomaly
 {:except #{ArithmeticException}}
 (/ 1 0)) ;; throws java.lang.ArithmeticException

;; WARNING! at the moment, exceptions hierarchy doesn't affect processing, e.g. specifying Throwable doesn't will not catch any of it's descendants

(at/with-default-category
  ::a/conflict
  (at/catch-all (/ 1 0)))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/conflict, :message "Divide by zero", :data #error {...}

;; need to assign some category for certain class of exceptions? that's also possible
(at/with-exception-categories
  {NullPointerException ::a/unsupported}
  (at/catch-all (+ 1 nil)))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/unsupported, :data #error {...}
```

## TODO

- Make documentation more readable
- ClojureScript support

## License

Copyright © 2017 Cognitect, Inc. All rights reserved.
Copyright © 2018 DAWCS

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
