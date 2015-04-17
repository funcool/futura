(ns futura.stream
  (:require [clojure.core.async :refer [put! take! chan <! >! go close! go-loop onto-chan]]
            [futura.atomic :as atomic])
  (:import org.reactivestreams.Publisher
           org.reactivestreams.Subscriber
           org.reactivestreams.Subscription))

(defn- vec->subscription
  [subscriber source xform]
  (let [index (atomic/long 0)
        completed (atomic/boolean false)
        queue (agent nil)
        length (count source)]
    (letfn [(process [_ ^long n]
              (when-not @completed
                (loop [n n]
                  (when (and (pos? n) (not @completed))
                    (let [i (atomic/get-and-inc! index)]
                      (.onNext subscriber (get source i))
                      (when (= i (dec length))
                        (.onComplete subscriber)
                        (atomic/set! completed true)))
                    (recur (dec n))))))]
      (reify Subscription
        (^void request [_ ^long n]
          (when (atomic/compare-and-set! completed false false)
            (send queue process n)))
        (^void cancel [_]
          (atomic/set! completed true))))))

(defn- seq->subscription
  [subscriber source xform]
  (let [completed (atomic/boolean false)
        queue (agent source)
        length (count source)]
    (letfn [(process [state ^long n]
              (if @completed
                state
                (loop [n n state' state]
                  (if (and (pos? n) (not @completed))
                    (if-let [value (first state')]
                      (do
                        (.onNext subscriber value)
                        (recur (dec n)
                               (rest state')))
                      (do
                        (.onComplete subscriber)
                        nil))
                    state'))))]
      (reify Subscription
        (^void request [_ ^long n]
          (when-not @completed
            (send queue process n)))
        (^void cancel [_]
          (atomic/set! completed true))))))

(defn- chan->subscription
  [subscriber source xform]
  (let [completed (atomic/boolean false)
        requests (chan 256)]
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
          (close! requests))))))

(defmulti publisher
  "A polymorphic publisher constructor."
  (fn [source & [xform]]
    (class source)))

(defmethod publisher clojure.lang.IPersistentVector
  [source & [xform]]
  (reify Publisher
    (^void subscribe [_ ^Subscriber subscriber]
      (let [subscription (vec->subscription subscriber source xform)]
        (.onSubscribe subscriber subscription)))))

(defmethod publisher clojure.lang.ISeq
  [source & [xform]]
  (reify Publisher
    (^void subscribe [_ ^Subscriber subscriber]
      (let [subscription (seq->subscription subscriber source xform)]
        (.onSubscribe subscriber subscription)))))

(defmethod publisher clojure.core.async.impl.channels.ManyToManyChannel
  [source & [xform]]
  (reify Publisher
    (^void subscribe [_ ^Subscriber subscriber]
      (let [subscription (chan->subscription subscriber source xform)]
        (.onSubscribe subscriber subscription)))))
