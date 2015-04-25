(ns futura.stream-tests
  (:require [clojure.core.async :refer [put! take! chan <! >! <!! close!] :as async]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [manifold.stream :as ms]
            [futura.stream :as stream]
            [futura.promise :as p])
  (:import org.reactivestreams.Publisher
           org.reactivestreams.Subscriber
           org.reactivestreams.Subscription))

(deftest vector-as-publisher
  (let [state (atom [])
        subscription (atom nil)
        nexts (chan)
        completes (chan)
        errors (chan)
        subscriber (reify Subscriber
                     (^void onSubscribe [_ ^Subscription s]
                       (reset! subscription s))
                     (^void onNext [_ v]
                       (put! nexts v))
                     (^void onComplete [_]
                       (put! completes true)
                       (close! nexts)
                       (close! completes)
                       (close! errors))
                     (^void onError [_ ^Throwable e]
                       (put! errors e)
                       (close! nexts)
                       (close! completes)
                       (close! errors)))

        publisher (stream/publisher [100 200 300 400])]

    (.subscribe publisher subscriber)
    (.request @subscription 100)
    (is (= 100 (<!! nexts)))
    (is (= 200 (<!! nexts)))
    (is (= 300 (<!! nexts)))
    (is (= 400 (<!! nexts)))
    (is (<!! completes)))
)

(deftest chan-as-publisher
  (let [state (atom [])
        subscription (atom nil)
        nexts (chan)
        completes (chan)
        errors (chan)
        source (chan 10)
        subscriber (reify Subscriber
                     (^void onSubscribe [_ ^Subscription s]
                       (reset! subscription s))
                     (^void onNext [_ v]
                       (put! nexts v))
                     (^void onComplete [_]
                       (put! completes true)
                       (close! nexts)
                       (close! completes)
                       (close! errors))
                     (^void onError [_ ^Throwable e]
                       (put! errors e)
                       (close! nexts)
                       (close! completes)
                       (close! errors)))

        publisher (stream/publisher source)]
    (put! source 100)
    (put! source 200)
    (close! source)

    (is (instance? Publisher publisher))
    (is (instance? Subscriber subscriber))
    (is (nil? @subscription))

    (.subscribe publisher subscriber)
    (is (instance? Subscription @subscription))

    (.request @subscription 100)
    (is (= 100 (<!! nexts)))
    (is (= 200 (<!! nexts)))
    (is (<!! completes))

    (.request @subscription 1)
    (is (nil? (<!! nexts))))
)

(deftest publisher-from-promise
  (let [p (stream/publisher (p/promise 1))
        c (stream/publisher->chan p)]
    (is (= [1] (<!! (async/into [] c))))))

(deftest publisher-composition
  (let [p (->> (stream/publisher [1 2 3 4 5 6])
               (stream/publisher (map inc)))
        c (stream/publisher->chan p)]
    (is (= [2 3 4 5 6 7] (<!! (async/into [] c)))))

  (let [p (->> (stream/publisher [1 2 3 4])
               (stream/publisher (take 2))
               (stream/publisher (map inc)))

        c (stream/publisher->chan p)]
    (is (= [2 3] (<!! (async/into [] c)))))

  (let [p (->> (stream/publisher [1 2 3 4 5 6])
               (stream/publisher (take 5))
               (stream/publisher (map inc))
               (stream/publisher (partition-all 2)))
        c (stream/publisher->chan p)]
    (is (= [[2 3] [4 5] [6]] (<!! (async/into [] c)))))
)

(deftest publisher-seqable
  (let [p (stream/publisher [1 2 3 4])
        v (into [] p)]
    (is (= v [1 2 3 4])))

  (let [source (async/chan)
        p (stream/publisher source)]
    (async/go-loop [n 10]
      (if (pos? n)
        (do
          (async/>! source (inc n))
          (recur (dec n)))
        (async/close! source)))
    (let [v (into [] p)]
      (is (= [11 10 9 8 7 6 5 4 3 2] v))))
)

(deftest push-stream
  (let [p (stream/publisher 2)]
    (stream/put! p 1)
    (stream/put! p 2)
    (is (= @(stream/take! p) 1))
    (is (= @(stream/take! p) 2))))

(deftest push-stream-manifold
  (let [mst (ms/stream)
        p (stream/publisher mst)]
    (ms/put! mst 1)
    (ms/put! mst 2)
    (is (= @(stream/take! p) 1))
    (is (= @(stream/take! p) 2)))

  (let [mst (ms/stream)
        p (stream/publisher mst)]
    (stream/put! p 1)
    (stream/put! p 2)
    (is (= @(stream/take! p) 1))
    (is (= @(stream/take! p) 2)))
)
