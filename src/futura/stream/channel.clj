(ns futura.stream.channel
  (:require [futura.atomic :as atomic]
            [futura.stream.common :as common]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as asyncp])
  (:import clojure.lang.Seqable
           org.reactivestreams.Publisher
           org.reactivestreams.Subscriber
           java.util.concurrent.ConcurrentLinkedQueue))

(defrecord Subscription [canceled active demand queue source subscriber]
  org.reactivestreams.Subscription
  (^void cancel [this]
    (common/signal-cancel this))

  (^void request [this ^long n]
    (common/signal-request this n)))

(defmethod common/handle-subscribe Subscription
  [sub]
  (let [canceled (:canceled sub)
        subscriber (:subscriber sub)
        source (:source sub)]
    (when (not @canceled)
      (try
        (.onSubscribe subscriber sub)
        (catch Throwable t
          (common/terminate sub (IllegalStateException. "Violated the Reactive Streams rule 2.13"))))
      (when (asyncp/closed? source)
        (try
          (atomic/set! canceled true)
          (.onComplete subscriber)
          (catch Throwable t
            ;; (IllegalStateException. "Violated the Reactive Streams rule 2.13")
            ))))))

(defmethod common/handle-send Subscription
  [sub]
  (let [source (:source sub)
        subscriber (:subscriber sub)
        canceled (:canceled sub)]
    (async/take! source (fn [value]
                          (try
                            (if (nil? value)
                              (do
                                (atomic/set! canceled true)
                                (.onComplete subscriber))
                              (let [demand (atomic/dec-and-get! (:demand sub))]
                                (.onNext subscriber value)
                                (when (and (not @canceled) (pos? demand))
                                  (common/signal-send sub))))
                            (catch Throwable t
                              (common/handle-cancel sub)))))))

(defn publisher
  "A publisher constructor with core.async
  channel as its source. The returned publisher
  instance is of unicast type."
  [source]
  (reify
    Seqable
    (seq [p]
      (seq (common/subscribe p)))

    Publisher
    (^void subscribe [_ ^Subscriber subscriber]
      (let [sub (Subscription. (atomic/boolean false)
                               (atomic/boolean false)
                               (atomic/long 0)
                               (ConcurrentLinkedQueue.)
                               source
                               subscriber)]
        (common/signal-subscribe sub)
        sub))))
