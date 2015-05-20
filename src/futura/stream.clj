(ns futura.stream
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as asyncp]
            [manifold.stream :as ms]
            [manifold.deferred :as md]
            [futura.atomic :as atomic]
            [futura.stream.common :as common]
            [futura.stream.promise :as promise]
            [futura.stream.channel :as channel]
            [futura.promise :as p])
  (:import java.util.concurrent.CompletableFuture
           clojure.lang.Seqable
           java.lang.AutoCloseable
           org.reactivestreams.Publisher
           org.reactivestreams.Subscriber
           org.reactivestreams.Subscription))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default Abstractions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (definterface IPushStream
;;   (push [v] "Push a value into stream.")
;;   (complete [] "Mark publisher as complete."))

(defprotocol IPublisher
  (publisher* [source xform] "Create a publisher."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- publisher->publisher
  "Create a publisher from an other publisher."
  [source xform]
  (reify
    Seqable
    (seq [p]
      (seq (common/subscribe p)))

    Publisher
    (^void subscribe [_ ^Subscriber subscriber]
      (let [subscriber (common/proxy-subscriber xform subscriber)]
        (.subscribe source subscriber)))))

;; (defn- empty->publisher
;;   "Creates an empty publisher that implements the
;;   push stream protocol."
;;   [bufflen]
;;   (let [source (async/chan bufflen)]
;;     (reify
;;       IPushStream
;;       (push [_ v]
;;         (let [p (p/promise)]
;;           (async/put! source v (fn [res]
;;                                  (if res
;;                                    (p/deliver p true)
;;                                    (p/deliver p false))))
;;           p))
;;       (complete [_]
;;         (async/close! source))

;;       Seqable
;;       (seq [p]
;;         (seq (subscribe p)))

;;       Publisher
;;       (^void subscribe [_ ^Subscriber subscriber]
;;         (let [subscription (chan->subscription subscriber source)]
;;           (.onSubscribe subscriber subscription))))))

(extend-protocol IPublisher
  ;; nil
  ;; (publisher* [source xform]
  ;;   (assert (nil? xform) "not supported operation.")
  ;;   (empty->publisher source xform))

  ;; java.lang.Long
  ;; (publisher* [source xform]
  ;;   (assert (nil? xform) "not supported operation.")
  ;;   (empty->publisher source))

  java.lang.Iterable
  (publisher* [source xform]
    (assert (nil? xform) "not supported operation.")
    (let [source' (async/chan)]
      (async/onto-chan source' (seq source))
      (channel/publisher source')))

  manifold.stream.default.Stream
  (publisher* [source xform]
    (assert (nil? xform) "not supported operation.")
    (let [source' (async/chan)]
      (ms/connect source source')
      (channel/publisher source')))

  clojure.core.async.impl.channels.ManyToManyChannel
  (publisher* [source xform]
    (assert (nil? xform) "not supported operation.")
    (channel/publisher source))

  manifold.deferred.IDeferred
  (publisher* [source xform]
    (publisher* (p/promise source) xform))

  CompletableFuture
  (publisher* [source xform]
    (publisher* (p/promise source) xform))

  futura.promise.Promise
  (publisher* [source xform]
    (assert (nil? xform) "not supported operation.")
    (promise/publisher source))

  Publisher
  (publisher* [source xform]
    (publisher->publisher source xform)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn publisher
  "A polymorphic publisher constructor."
  ([source] (publisher* source nil))
  ([xform source] (publisher* source xform)))

;; (defn put!
;;   "Puts a value into a stream, returning a promise that yields true
;;   if it succeeds, and false if it fails."
;;   [^IPushStream p v]
;;   (.push p v))
