;; Copyright (c) 2015 Andrey Antukh <niwi@niwi.nz>
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions
;; are met:
;;
;; 1. Redistributions of source code must retain the above copyright
;;    notice, this list of conditions and the following disclaimer.
;; 2. Redistributions in binary form must reproduce the above copyright
;;    notice, this list of conditions and the following disclaimer in the
;;    documentation and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
;; IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
;; OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns futura.stream.common
  "Defines a common subset of functions and hepers that
  works with all kind of subscription objects."
  (:require [futura.atomic :as atomic]
            [futura.promise :as p]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as asyncp])
  (:import clojure.lang.Seqable
           org.reactivestreams.Publisher
           org.reactivestreams.Subscriber
           java.lang.AutoCloseable
           java.util.Queue
           java.util.concurrent.ForkJoinPool
           java.util.concurrent.Executor
           java.util.concurrent.CountDownLatch
           java.util.concurrent.ConcurrentLinkedQueue))

(declare runnable)

(def ^:dynamic
  *executor* (ForkJoinPool/commonPool))

(defn terminate
  "Mark a subscrition as terminated
  with provided exception."
  [sub e]
  (let [canceled (:canceled sub)
        subscriber (:subscriber sub)]
    (atomic/set! canceled true)
    (try
      (.onError subscriber e)
      (catch Throwable t
        (IllegalStateException. "Violated the Reactive Streams rule 2.13")))))

(defn schedule
  "Schedule the subscrption to be executed
  in builtin scheduler executor."
  [sub]
  (let [active (:active sub)
        canceled (:canceled sub)
        queue (:queue sub)]
    (when (atomic/compare-and-set! active false true)
      (try
        (.execute *executor* (runnable sub))
        (catch Throwable t
          (when (not @canceled)
            (atomic/set! canceled true)
            (try
              (terminate sub (IllegalStateException. "Unavailable executor."))
              (finally
                (.clear queue)
                (atomic/set! active false)))))))))

(defn- signal
  "Notify the subscription about specific event."
  [sub m]
  (let [queue (:queue sub)]
    (when (.offer queue m)
      (schedule sub))))

(defn signal-request
  "Signal the request event."
  [sub n]
  (signal sub {:type ::request :number n}))

(defn signal-send
  "Signal the send event."
  [sub]
  (signal sub {:type ::send}))

(defn signal-subscribe
  "Signal the subscribe event."
  [sub]
  (signal sub {:type ::subscribe}))

(defn signal-cancel
  "Signal the cancel event."
  [sub]
  (signal sub {:type ::cancel}))

(defmulti handle-send
  "A polymorphic method for handle send signal."
  class)

(defmulti handle-subscribe
  "A polymorphic method for handle send signal."
  class)

(defn handle-request
  "A generic implementation for request events
  handling for any type of subscriptions."
  [sub n]
  (let [demand (:demand sub)]
    (cond
      (< n 1)
      (terminate sub (IllegalStateException. "violated the Reactive Streams rule 3.9"))

      (< (+ @demand n) 1)
      (do
        (atomic/set! demand Long/MAX_VALUE)
        (handle-send sub))

      :else
      (do
        (atomic/get-and-add! demand n)
        (handle-send sub)))))

(defn handle-cancel
  "A generic implementation for handle cancel events
  for all types of subscriptions."
  [sub]
  (let [canceled (:canceled sub)]
    (atomic/set! canceled true)))

(defn runnable
  "A runnable constructor for schedule the subscription
  to handle events in a executor/event-loop."
  [sub]
  (let [queue (:queue sub)
        active (:active sub)
        canceled (:canceled sub)]
    (reify Runnable
      (^void run [_]
        (try
          (let [signal (.poll queue)]
            (when (not @canceled)
              (case (:type signal)
                ::request (handle-request sub (:number signal))
                ::send (handle-send sub)
                ::cancel (handle-cancel sub)
                ::subscribe (handle-subscribe sub))))
          (finally
            (atomic/set! active false)
            (when (not (.isEmpty queue))
              (schedule sub))))))))

(definterface IPullStream
  (pull [] "Pull a value from the stream."))

(defn take!
  "Takes a value from a stream, returning a deferred that yields the value
  when it is available or nil if the take fails."
  [^IPullStream p]
  (.pull p))

(defn- publisher->seq
  "Coerce a publisher in a blocking seq."
  [s]
  (lazy-seq
   (let [v @(take! s)]
     (if v
       (cons v (lazy-seq (publisher->seq s)))
       (.close ^AutoCloseable s)))))

(defn subscribe
  "Create a subscription to the given publisher instance.

  The returned subscription does not consumes the publisher
  data until is requested."
  [^Publisher p]
  (let [sr (async/chan)
        lc (CountDownLatch. 1)
        ss (atomic/ref nil)
        sb (reify Subscriber
             (onSubscribe [_ s]
               (.countDown lc)
               (atomic/set! ss s))
             (onNext [_ v]
               (async/put! sr v))
             (onError [_ e]
               (async/close! sr))
             (onComplete [_]
               (async/close! sr)))]
    (.subscribe p sb)
    (reify
      AutoCloseable
      (close [_]
        (.await lc)
        (.cancel @ss))

      Seqable
      (seq [this]
        (.await lc)
        (publisher->seq this))

      IPullStream
      (pull [_]
        (.await lc)
        (let [p (p/promise)]
          (async/take! sr #(p/deliver p %))
          (.request @ss 1)
          p))

      asyncp/ReadPort
      (take! [_ handler]
        (asyncp/take! sr handler)))))

(defn proxy-subscriber
  "Create a proxy subscriber.

  The main purpose of this proxy is apply some
  kind of transformations to the proxied publisher
  using transducers."
  [xform subscriber]
  (let [rf (xform (fn
                    ([s] s)
                    ([s v] (.onNext s v))))
        completed (atomic/boolean false)
        subscription (atomic/ref nil)]
    (reify Subscriber
      (onSubscribe [_ s]
        (atomic/set! subscription s)
        (.onSubscribe subscriber s))
      (onNext [_ v]
        (when-not @completed
          (let [res (rf subscriber v)]
            (cond
              (identical? res subscriber)
              (.request @subscription 1)

              (reduced? res)
              (do
                (.cancel @subscription)
                (.onComplete (rf subscriber))
                (atomic/set! completed true))))))
      (onError [_ e]
        (atomic/set! completed true)
        (rf subscriber)
        (.onError subscriber e))
      (onComplete [_]
        (when-not @completed
          (atomic/set! completed true)
          (rf subscriber)
          (.onComplete subscriber))))))
