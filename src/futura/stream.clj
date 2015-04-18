(ns futura.stream
  (:refer-clojure :exclude [map])
  (:require [clojure.core.async :refer [put! take! <! >! go go-loop] :as async]
            [futura.atomic :as atomic]
            [futura.promise :as p])
  (:import org.reactivestreams.Publisher
           org.reactivestreams.Subscriber
           org.reactivestreams.Subscription))

(defn- chan->subscription
  [subscriber source]
  (let [completed (atomic/boolean false)
        requests (async/chan 8)]
    (letfn [(pipeline [n]
              (go-loop [n n]
                (when (and (pos? n) (not @completed))
                  (if-let [value (<! source)]
                    (do
                      (.onNext subscriber value)
                      (recur (dec n)))
                    (do
                      (.onComplete subscriber)
                      (atomic/set! completed true))))))]
      (go-loop []
        (when-let [n (<! requests)]
          (when-not @completed
            (<! (pipeline n))
            (recur))))
      (reify Subscription
        (^void request [_ ^long n]
          (when-not @completed
            (put! requests n)))
        (^void cancel [_]
          (atomic/set! completed true)
          (async/close! requests))))))

(defn- subscribe-once
  [^Publisher p callback]
  (let [subscription (atomic/ref nil)
        subscriber (reify Subscriber
                     (onSubscribe [_ s]
                       (atomic/set! subscription s))
                     (onNext [_ v]
                       (callback v)
                       (.cancel @subscription)
                       (atomic/set! subscription nil))
                     (onError [_ _]
                       (callback nil)
                       (atomic/set! subscription nil))
                     (onComplete [_]
                       (callback nil)
                       (atomic/set! subscription nil)))]
    (.subscribe p subscriber)))

(defn- subscribe-proxy
  [^Publisher p xform subscriber]
  (let [transform (fn
                    ([s] s)
                    ([s v] (.onNext s v)))
        rf (xform transform)]
    (reify Subscriber
      (onSubscribe [_ s]
        (.onSubscribe subscriber s))
      (onNext [_ v]
        (let [res (rf subscriber v)]
          (when (reduced? res)
            (.onComplete (rf subscriber)))))
      (onError [_ e]
        (.onError (rf subscriber) e))
      (onComplete [_]
        (.onComplete (rf subscriber))))))

(definterface IPushStream
  (push [_ v] "Push a value into stream.")
  (complete [_] "Mark publisher as complete."))

(defprotocol IPublisher
  (publisher* [source xform] "Create a publisher."))

;; Match all collections that can be converted into Seqs.

(extend-protocol IPublisher
  java.lang.Iterable
  (publisher* [source xform]
    (let [source' (async/chan 1 xform)
          _       (async/onto-chan source' (seq source))]
      (reify Publisher
        (^void subscribe [_ ^Subscriber subscriber]
          (let [subscription (chan->subscription subscriber source')]
            (.onSubscribe subscriber subscription))))))

  clojure.core.async.impl.channels.ManyToManyChannel
  (publisher* [source xform]
    (reify Publisher
      (^void subscribe [_ ^Subscriber subscriber]
        (let [subscription (chan->subscription subscriber source)]
          (.onSubscribe subscriber subscription)))))

  nil
  (publisher* [source xform]
    (publisher* 1 xform))

  Publisher
  (publisher* [source xform]
    (reify Publisher
      (^void subscribe [_ ^Subscriber subscriber]
        (let [subscriber (subscribe-proxy source xform subscriber)]
          (.subscribe source subscriber)))))

  ;; java.lang.Long
  ;; (publisher* [source xform]
  ;;   (let [input (async/chan)
  ;;         mult  (async/mult input)]
  ;;     (reify
  ;;       IPushStream
  ;;       (push [_ v]
  ;;         (p/promise (fn [complete]
  ;;                      (async/put! input v (fn [res]
  ;;                                            (if res
  ;;                                              (complete true)
  ;;                                              (complete false)))))))
  ;;       (complete [_]
  ;;         (async/close! input))

  ;;       Publisher
  ;;       (^void subscribe [_ ^Subscriber subscriber]
  ;;         (let [source' (async/chan (or source 1) xform)
  ;;               _       (async/tap mult source')
  ;;               subscription (chan->subscription subscriber source')]
  ;;           (.onSubscribe subscriber subscription)))))))
)

(defn publisher
  "A polymorphic publisher constructor."
  ([source] (publisher* source nil))
  ([xform source] (publisher* source xform)))

(defn publisher->chan
  "Helper that converts a publisher in a core.async
  channel. Serves for easy subscription handling in
  test code."
  {:no-doc true}
  [^Publisher p]
  (let [chan (async/chan)
        subscription (atomic/ref nil)
        subscriber (reify Subscriber
                     (onSubscribe [_ s]
                       (.request s 1)
                       (atomic/set! subscription s))
                     (onNext [_ v]
                       (put! chan v (fn [result]
                                      (if result
                                        (.request @subscription 1)
                                        (.cancel @subscription)))))
                     (onError [_ e]
                       (async/close! chan))
                     (onComplete [_]
                       (async/close! chan)))]
    (.subscribe p subscriber)
    chan))

(defn map
  [f ^Publisher source]
  (publisher* source (clojure.core/map f)))

;; (defn take!
;;   "Takes a value from a stream, returning a deferred that yields the value
;;   when it is available or nil if the take fails."
;;   [^Publisher p]
;;   (p/promise #(subscribe-once p %)))

;; (defn put!
;;   "Puts a value into a stream, returning a promise that yields true
;;   if it succeeds, and false if it fails."
;;   [^IPushStream p v]
;;   (assert (instance? IPushStream p))
;;   (.push p v))
