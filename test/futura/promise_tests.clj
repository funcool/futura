(ns futura.promise-tests
  (:require [clojure.core.async :refer [put! take! chan <! >! <!! close!] :as async]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [cats.core :as m]
            [futura.promise :as p]))

(deftest promise-constructors
  (let [p1 (p/promise 1)]
    (is (p/promise? p1))
    (is (p/fulfilled? p1))
    (is (not (p/rejected? p1)))
    (is (not (p/pending? p1))))
  (let [p1 (p/promise)]
    (is (p/promise? p1))
    (is (not (p/fulfilled? p1)))
    (is (not (p/rejected? p1)))
    (is (p/pending? p1)))
  (let [p1 (p/promise (ex-info "" {}))]
    (is (p/promise? p1))
    (is (not (p/fulfilled? p1)))
    (is (p/rejected? p1))
    (is (not (p/pending? p1))))
  (let [p1 (p/promise)
        p2 (p/promise p1)]
    (is (identical? p1 p2)))
)

(deftest promise-extract
  (let [p1 (p/promise 1)]
    (is (= 1 @p1)))
  (let [p1 (p/promise (ex-info "foobar" {:foo 1}))]
    (is (= "foobar" (.getMessage (m/extract p1))))
    (try
      @p1
      (catch Exception e
        (is (= {:foo 1} (.getData e))))))3
)

(deftest promise-chaining
  (let [p1 (p/promise (fn [complete]
                        (async/thread
                          (complete 2))))]
    (is (= 2 @p1)))
  (let [p1 (p/promise (fn [complete]
                        (async/thread
                          (Thread/sleep 200)
                          (complete 2))))
        p2 (p/then p1 inc)
        p3 (p/then p2 inc)]
    (is (= 4 @p3)))

  (let [p1 (p/promise (fn [complete]
                        (async/thread
                          (Thread/sleep 200)
                          (complete (ex-info "foobar" {})))))]
    (try
      @p1
      (catch clojure.lang.ExceptionInfo e
        (is (= "foobar" (.getMessage e))))))

  (let [p1 (p/promise (fn [complete]
                        (async/thread
                          (complete 1))))
        p2 (p/then p1 (fn [v]
                        (throw (ex-info "foobar" {:msg "foo"}))))
        p3 (p/catch p2 (fn [e]
                         (:msg (.getData e))))]
    (is (= "foo" @p3)))
)
