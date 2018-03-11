# anomalies-tools [![Build Status](https://travis-ci.org/dawcs/anomalies-tools.svg?branch=master)](https://travis-ci.org/dawcs/anomalies-tools)

Utility functions and macros for https://github.com/cognitect-labs/anomalies

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

Let's write a function which will return anomaly in case of `deref` timeout:
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
```

Plain map syntax may look too verbose for someone, so there's handy construction helper:
```clojure
(at/anomaly ::a/busy "Connection timeout")
;; => #:cognitect.anomalies{:category :cognitect.anomalies/busy, :message "Connection timeout"}
```

And a short form alias for true 1-liner lovers:
```clojure
(!! ::a/busy "Connection timeout")
;; => #:cognitect.anomalies{:category :cognitect.anomalies/busy, :message "Connection timeout"}
```

There are also constructors for each category:
```clojure
(at/busy "Connection timeout")
;; => #:cognitect.anomalies{:category :cognitect.anomalies/busy, :message "Connection timeout"}
(at/not-found)
;; => #:cognitect.anomalies{:category :cognitect.anomalies/not-found}
(at/forbidden {:user-id 123})
;; => #:cognitect.anomalies{:category :cognitect.anomalies/forbidden :data {:user-id 123}}
```

Construction helper functions are very flexible regarding arguments:
```clojure
;; no need for message?
(at/busy)
;; => #:cognitect.anomalies{:category :cognitect.anomalies/busy}

;; need to store some additional data along with message?
(!! ::a/forbidden "Cannot perform operation" {:user-id 2128506})
;; => #:cognitect.anomalies{:category :cognitect.anomalies/forbidden, :message "Cannot perform operation", :data {:user-id 2128506}}

;; just data is enough?
(!! ::a/forbidden {:user-id 2128506})
;; => #:cognitect.anomalies{:category :cognitect.anomalies/forbidden, :data {:user-id 2128506}}
```

Default category is `:cognitect.anomalies/fault` (later we'll see how to change that)
```clojure
;; the smallest possible anomaly constructor
(!!)
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}

(!! "Cannot perform operation" {:user-id 2128506})
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :message "Cannot perform operation", :data {:user-id 2128506}}

(!! {:user-id 2128506})
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :data {:user-id 2128506}}
```

If we want other default category, wrapping code into `with-default-category` macro does the trick:
```clojure
(at/with-default-category
 ::a/conflict
 (!! "Something went wrong"))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/conflict, :message "Something went wrong"}
```

So, how we can handle anomalies? First, we can check if value is anomaly with `anomaly?` function:
```clojure
(at/anomaly? (!!)) ;; => true
(at/anomaly? {:user 1}) ;; => false
(at/anomaly? (Exception. "Bad stuff")) ;; => false
```

This can be useful for imperative style error checking:
```clojure
(let [result (do-stuff)]
  (if (at/anomaly? result)
    (say-oooops result)
    (say-hooray result)))
```

How about functional programming?
```cljoure
(inc 1) ;; => 2
(inc (!!)) ;; BOOOM!!! Unhandled java.lang.ClassCastException clojure.lang.PersistentArrayMap cannot be cast to java.lang.Number
```

How to make function aware of anomalies? `aware` to the rescue:
```clojure
(def ainc (at/aware inc))
(ainc 1) ;; => 2
(ainc (!!)) ;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}
```

`aware` accepts function as first argument which makes it perfect for `->>` macro
```clojure
(->> 1 (at/aware inc) (at/aware str))
;; => "2"
(->> (!!) (at/aware inc) (at/aware str))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}
```

Do you like `some->` and `some->>` power for dealing with `nil` values? There's analogs for anomalies:
```clojure
(at/aware-> 1 inc) ;; => 2
(at/aware-> (!!) inc) ;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}
(at/aware-> 1 (!!) inc) ;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :data 1}

(at/aware->> [1 2 3] (map inc)) ;; => (2 3 4)
(at/aware->> (!!) (map inc))   ;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}
(at/aware->> [1 2 3] (!! ::a/conflict "Ooops") (map inc))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/conflict, :message "Ooops", :data [1 2 3]}
```

When functional chains are required, but macros magic is not desired, `chain` function may fit the needs:
```clojure
(at/chain [1 2 3] (partial map inc)) ;; => (2 3 4)
(at/chain [1 2 3] (partial at/unsupported) (partial map inc))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/unsupported, :data [1 2 3]}
```

And of course there's opposite case, when we need to handle caught anomaly. `caught` does the job and makes sure that anomaly is returned from chain:
```clojure
(at/caught
  (at/forbidden "Bad password" {:user-id 2128506})
  (comp prn ::a/message) ;; prn returns nil, so initial anomaly is passed to next function in chain
  (comp prn ::a/category))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/forbidden, :message "Bad password", :data {:user-id 2128506}}
```

For non-anomaly value, chain in completely skipped and given value is returned immediately:
```clojure
(at/caught 1 (comp prn ::a/message)) ;; => 1
```

If some function in chain returns another anomaly, it's passed to next function in chain:
```clojure
(at/caught
  (at/conflict "Uh-oh")
  (fn [x] (at/busy x)) ;; producing new anomaly from given one
  (comp prn at/category)) ;; prints :busy
;; => #:cognitect.anomalies{:category :cognitect.anomalies/busy, :data #:cognitect.anomalies{:category :cognitect.anomalies/conflict, :message "Uh-oh"}}
```

`caught` and `chain` accept value as first argument so can be used together in `->` macro:
```clojure
(-> "hello"
     at/anomaly
     (at/chain clojure.string/upper-case)
     (at/caught (comp prn at/message))) ;; prints anomaly message to console
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :message "hello"}
```

Returning to imperative error handling example, we can rewrite it using functional chain:
```clojure
(-> (do-stuff)
    (at/chain say-hooray)
    (at/caught say-oooops))
```

Often we need to fallback to some default value. `either` can help in this case:
```clojure
(at/either (!!) 1) ;; => 1
(apply at/either [(at/busy) (at/fault) (at/conflict) (at/not-found) 1]) ;; => 1
```

If only anomaly values are given to `either`, then last given value is returned:
```clojure
(at/either (at/busy) (at/unsupported))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/unsupported}
```

So `either` is good companion for `chain` and `caught` in `->` macro:
```clojure
(-> "hello"
    at/anomaly
    (at/chain clojure.string/upper-case)
    (at/caught prn)
    (at/either "goodbye"))
;; => "goodbye"
```

By supporting multiple args, `either` can be also used on its own similarly to `or`:
```clojure
(defn load-from-db [id]
  (if (= id 1)
    {:role "user"}
    (at/not-found)))

(defn load-from-cache [id]
  (if (= id 2)
    {:role "admin"}
    (at/not-found)))

(def default-settings {:role "guest"})

(defn user-settings [id]
  (at/either
    (load-from-cache id)
    (load-from-db id)
    default-settings))

(user-settings 1) ;; => {:role "user"}
(user-settings 2) ;; => {:role "admin"}
(user-settings 3) ;; => {:role "guest"}
```

`alet` is anomalies aware version of `let` macro:
```clojure
(at/alet [a 1 b 2] (+ a b)) ;; => 3
(at/alet [a 1 b (!!)] (+ a b))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}
```

`alet` calculates bindings until anomaly is returned. In the following example exception is not thrown:
```clojure
(at/alet [a 1
          b (!!)
          c (throw (Exception.))]
  (+ a b))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault}
```

Sometimes we need to deal with Java exceptions. We can turn all caught exception into anomaly with `catch-all` macro:
```clojure
(at/catch-all (/ 1 0))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :message "Divide by zero", :data #error {...}
```

If we need to throw some exceptions and catch all others, `catch-except` fits for that purpose:
```clojure
(at/catch-except #{NullPointerException} (/ 1 0))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :message "Divide by zero", :data #error {...}
(at/catch-except #{NullPointerException} (/ 1 nil)) ;; throws java.lang.NullPointerException
```

Need to catch only certain exceptions and throw all others? `catch-only` does the job:
```clojure
(at/catch-only #{NullPointerException} (/ 1 0)) ;; throws java.lang.ArithmeticException
(at/catch-only #{NullPointerException} (/ 1 nil))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/fault, :data #error {...}
```
**WARNING!** at the moment, exceptions hierarchy doesn't affect processing, e.g. specifying Throwable will not catch any of it's descendants.

By default caught anomalies will be filled with default `:category`, `:message` extracted from exception and `:data` containing caught exception instance.
`catch-anomaly` macro gives full control for all that options along with list of exceptions to catch or throw:
```clojure
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

(at/with-default-category
  ::a/conflict
  (at/catch-all (/ 1 0)))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/conflict, :message "Divide by zero", :data #error {...}
```

Need to assign some category for certain class of exceptions? That's also possible with `with-exception-categories` macro:
```clojure
(at/with-exception-categories
  {NullPointerException ::a/unsupported}
  (at/catch-all (+ 1 nil)))
;; => #:cognitect.anomalies{:category :cognitect.anomalies/unsupported, :data #error {...}
```

## TODO

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
