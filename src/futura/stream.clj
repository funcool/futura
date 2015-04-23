(ns futura.stream
  (:refer-clojure :exclude [map filter])
  (:require [clojure.core.async :refer [<! >! go go-loop] :as async]
            [futura.atomic :as atomic]
            [futura.promise :as p])
  (:import java.util.concurrent.CompletableFuture
           org.reactivestreams.Publisher
           org.reactivestreams.Subscriber
           org.reactivestreams.Subscription))

(defn- chan->subscription
  [subscriber source xform]
  (let [completed (atomic/boolean false)
        requests (async/chan)
        xform (or xform (clojure.core/map identity))
        rf (xform (fn
                    ([s] s)
                    ([s v] (.onNext s v))))]
    (letfn [(pipeline [n]
              (go-loop [n n]
                (when (and (pos? n) (not @completed))
                  (if-let [value (<! source)]
                    (let [res (rf subscriber value)]
                      (if (reduced? res)
                        (do
                          (.onComplete (rf subscriber))
                          (async/close! requests)
                          (atomic/compare-and-set! completed false true))
                        (recur (dec n))))
                    (do
                      (.onComplete subscriber)
                      (async/close! requests)
                      (atomic/compare-and-set! completed false true))))))]
      (go-loop []
        (when-let [n (<! requests)]
          (when-not @completed
            (<! (pipeline n))
            (recur))))
      (reify Subscription
        (^void request [_ ^long n]
          (when-not @completed
            (async/put! requests n)))
        (^void cancel [_]
          (atomic/set! completed true)
          (async/close! requests))))))

(defn- promise->subscription
  [subscriber source]
  (let [canceled (atomic/boolean false)
        completed (atomic/boolean false)]
    (reify Subscription
      (^void request [_ ^long n]
        (when (atomic/compare-and-set! completed false true)
          (p/then source (fn [v]
                           (when-not @canceled
                             (.onNext subscriber v)
                             (.onComplete subscriber))))))
      (^void cancel [_]
        (atomic/set! completed true)
        (atomic/set! canceled true)))))

(defn- proxy-subscriber
  [^Publisher p xform subscriber]
  (let [transform (fn
                    ([s] s)
                    ([s v] (.onNext s v)))
        rf (xform transform)
        completed (atomic/boolean false)
        subscription (atomic/ref nil)]
    (reify Subscriber
      (onSubscribe [_ s]
        (atomic/set! subscription s)
        (.onSubscribe subscriber s))
      (onNext [_ v]
        (when-not @completed
          (let [res (rf subscriber v)]
            (when (reduced? res)
              (.cancel @subscription)
              (.onComplete (rf subscriber))
              (atomic/set! completed true)))))
      (onError [_ e]
        (atomic/set! completed true)
        (.onError (rf subscriber) e))
      (onComplete [_]
        (when-not @completed
          (atomic/set! completed true)
          (.onComplete (rf subscriber)))))))

(defn- subscribe-once
  [^Publisher p callback]
  (let [subscription (atomic/ref nil)
        completed (atomic/boolean false)
        subscriber (reify Subscriber
                     (onSubscribe [_ s]
                       (atomic/set! subscription s)
                       (.request s 1))
                     (onNext [_ v]
                       (when-not @completed
                         (callback v)
                         (.cancel @subscription)
                         (atomic/compare-and-set! completed false true)))
                     (onError [_ e]
                       (when-not @completed
                         (callback nil)
                         (atomic/compare-and-set! completed false true)))
                     (onComplete [_]
                       (when-not @completed
                         (callback nil)
                         (atomic/compare-and-set! completed false true))))]
    (.subscribe p subscriber)))

(definterface IPushStream
  (push [v] "Push a value into stream.")
  (complete [] "Mark publisher as complete."))

(definterface IPullStream
  (pull [] "Pull a value from the stream."))

(defprotocol IPublisher
  (publisher* [source xform] "Create a publisher."))

;; Match all collections that can be converted into Seqs.

(extend-protocol IPublisher
  java.lang.Long
  (publisher* [bufflen xform]
    (let [source (async/chan bufflen)]
      (reify
        IPushStream
        (push [_ v]
          (p/promise (fn [complete]
                       (async/put! source v (fn [res]
                                              (if res
                                                (complete true)
                                                (complete false)))))))
        (complete [_]
          (async/close! source))

        IPullStream
        (pull [p]
          (p/promise #(subscribe-once p %)))

        Publisher
        (^void subscribe [_ ^Subscriber subscriber]
          (let [subscription (chan->subscription subscriber source xform)]
            (.onSubscribe subscriber subscription))))))

  java.lang.Iterable
  (publisher* [source xform]
    (let [source' (async/chan)
          _       (async/onto-chan source' (seq source))]
      (reify
        IPullStream
        (pull [p]
          (p/promise #(subscribe-once p %)))

        Publisher
        (^void subscribe [_ ^Subscriber subscriber]
          (let [subscription (chan->subscription subscriber source' xform)]
            (.onSubscribe subscriber subscription))))))

  clojure.core.async.impl.channels.ManyToManyChannel
  (publisher* [source xform]
    (reify Publisher
      (^void subscribe [_ ^Subscriber subscriber]
        (let [subscription (chan->subscription subscriber source xform)]
          (.onSubscribe subscriber subscription)))))

  futura.promise.Promise
  (publisher* [source xform]
    (reify Publisher
      (^void subscribe [_ ^Subscriber subscriber]
        (let [subscription (promise->subscription subscriber source)]
          (.onSubscribe subscriber subscription)))))

  CompletableFuture
  (publisher* [source xform]
    (publisher* (p/promise source) xform))

  nil
  (publisher* [source xform]
    (publisher* 1 xform))

  Publisher
  (publisher* [source xform]
    (reify Publisher
      (^void subscribe [_ ^Subscriber subscriber]
        (let [subscriber (proxy-subscriber source xform subscriber)]
          (.subscribe source subscriber))))))

(defn publisher
  "A polymorphic publisher constructor."
  ([source] (publisher* source nil))
  ([xform source] (publisher* source xform)))

(defn publisher->chan
  "Helper that converts a publisher in a core.async channel."
  ([^Publisher p] (publisher->chan p (async/chan)))
  ([^Publisher p chan]
   (let [subscription (atomic/ref nil)
         subscriber (reify Subscriber
                      (onSubscribe [_ s]
                        (.request s 1)
                        (atomic/set! subscription s))
                      (onNext [_ v]
                        (async/put! chan v (fn [result]
                                             (if result
                                               (.request @subscription 1)
                                               (.cancel @subscription)))))
                      (onError [_ e]
                        (async/close! chan))
                      (onComplete [_]
                        (async/close! chan)))]
     (.subscribe p subscriber)
     chan)))

(defn map
  [f ^Publisher source]
  (publisher* source (clojure.core/map f)))

(defn filter
  [f ^Publisher source]
  (publisher* source (clojure.core/filter f)))


(defn take!
  "Takes a value from a stream, returning a deferred that yields the value
  when it is available or nil if the take fails."
  [^IPullStream p]
  (.pull p))

(defn put!
  "Puts a value into a stream, returning a promise that yields true
  if it succeeds, and false if it fails."
  [^IPushStream p v]
  (.push p v))
