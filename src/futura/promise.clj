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

(ns futura.promise
  "A promise implementation for Clojure that uses jdk8
  completable futures behind the scenes."
  (:refer-clojure :exclude [future promise deliver])
  (:require [cats.core :as m]
            [cats.protocols :as proto]
            [manifold.deferred :as md])
  (:import java.util.concurrent.CompletableFuture
           java.util.concurrent.CompletionStage
           java.util.concurrent.TimeoutException
           java.util.concurrent.ExecutionException
           java.util.concurrent.CompletionException
           java.util.concurrent.TimeUnit))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The main abstraction definition.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IPromiseState
  "Additional state related abstraction."
  (^:private rejected* [_] "Returns true if a promise is rejected.")
  (^:private resolved* [_] "Returns true if a promise is resolved.")
  (^:private done* [_] "Retutns true if a promise is already done.")
  (^:private pending* [_] "Retutns true if a promise is stil pending."))

(defprotocol IPromise
  "A basic promise abstraction."
  (^:private then* [_ callback] "Chain a promise.")
  (^:private error* [_ callback] "Catch a error in a promise."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Monad type implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare then)
(declare promise)

(def ^{:no-doc true}
  promise-monad
  (reify
    proto/Functor
    (fmap [mn f mv]
      (then mv f))

    proto/Monad
    (mreturn [_ v]
      (promise v))

    (mbind [mn mv f]
      (let [ctx m/*context*]
        (then mv (fn [v]
                    (m/with-monad ctx
                      (f v))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The promise type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype Promise [^CompletionStage cf]
  proto/Context
  (get-context [_] promise-monad)

  proto/Extract
  (extract [_]
    (try
      (.getNow cf nil)
      (catch ExecutionException e
        (.getCause e))
      (catch CompletionException e
        (.getCause e))))

  clojure.lang.IDeref
  (deref [_]
    (try
      (.get cf)
      (catch ExecutionException e
        (let [e' (.getCause e)]
          (.setStackTrace e' (.getStackTrace e))
          (throw e')))
      (catch CompletionException e
        (let [e' (.getCause e)]
          (.setStackTrace e' (.getStackTrace e))
          (throw e')))))

  clojure.lang.IPending
  (isRealized [_]
    (.isDone cf))

  clojure.lang.IBlockingDeref
  (deref [_ ^long ms defaultvalue]
    (try
      (.get cf ms TimeUnit/SECONDS)
      (catch TimeoutException e
        defaultvalue)
      (catch ExecutionException e
        (let [e' (.getCause e)]
          (.setStackTrace e' (.getStackTrace e))
          (throw e')))
      (catch CompletionException e
        (let [e' (.getCause e)]
          (.setStackTrace e' (.getStackTrace e))
          (throw e')))))

  IPromiseState
  (rejected* [_]
    (.isCompletedExceptionally cf))

  (resolved* [_]
    (and (not (.isCompletedExceptionally cf))
         (not (.isCancelled cf))
         (.isDone cf)))

  (done* [_]
    (.isDone cf))

  (pending* [_]
    (not (.isDone cf)))

  IPromise
  (then* [_ callback]
    (let [cf' (.thenApply cf (reify java.util.function.Function
                               (apply [_ v]
                                 (callback v))))]
      (Promise. cf')))

  (error* [_ callback]
    (let [cf' (.exceptionally cf (reify java.util.function.Function
                                   (apply [_ e]
                                     (callback (.getCause e)))))]
      (Promise. cf'))))

(alter-meta! #'->Promise assoc :private true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare deliver)
(declare resolved)

(defmulti ^:no-doc promise* class)

(defmethod promise* clojure.lang.IFn
  [factory]
  (let [futura (CompletableFuture.)
        promise (Promise. futura)]
    (try
      (factory #(deliver promise %))
      (catch Throwable e
        (.completeExceptionally futura e)))
    promise))

(defmethod promise* Throwable
  [e]
  (let [p (promise* nil)]
    (deliver p e)
    p))

(defmethod promise* CompletableFuture
  [cf]
  (Promise. cf))

(defmethod promise* Promise
  [p]
  p)

(defmethod promise* manifold.deferred.IDeferred
  [d]
  (let [pr (promise* nil)
        callback #(deliver pr %)]
    (md/on-realized d callback callback)
    pr))

(prefer-method promise* manifold.deferred.IDeferred clojure.lang.IFn)

(defmethod promise* nil
  [_]
  (Promise. (CompletableFuture.)))

(defmethod promise* :default
  [v]
  (resolved v))

(defn promise
  "A promise constructor.

  This is a polymorphic function and this is a list of
  possible arguments:

  - throwable
  - plain value
  - factory function

  In case of the initial value is instance of `Throwable`, rejected
  promise will be retrned. In case of a plain value (not throwable),
  a resolved promise will be returned. And finally, if a function
  or any callable is provided, that function will be executed with
  one argument as callback for mark the promise resolved or rejected.

      (promise (fn [deliver]
                 (future
                   (Thread/sleep 200)
                   (deliver 1))))

  The body of that function can be asynchronous and the promise can
  be freely resolved in other thread."
  ([] (promise* nil))
  ([v] (promise* v)))

(defn promise?
  "Returns true if `p` is a promise
  instance."
  [p]
  (instance? Promise p))

(defn done?
  "Returns true if promise `p` is
  done independently if successfully
  o exceptionally."
  [p]
  (done* p))

(defn rejected?
  "Returns true if promise `p` is
  completed exceptionally."
  [p]
  (rejected* p))

(defn resolved?
  "Returns true if promise `p` is
  completed successfully."
  [p]
  (resolved* p))

(defn pending?
  "Returns true if promise `p` is
  stil in pending state."
  [p]
  (pending* p))

(defn promise->future
  "Converts a promise in a CompletableFuture instance."
  [^Promise p]
  (.-cf p))

(defn resolved
  "Return a promise in a resolved state
  with given `v` value."
  [v]
  (-> (CompletableFuture/completedFuture v)
      (Promise.)))

(defn deliver
  "Mark the promise as completed or rejected with optional
  value.

  If value is not specified `nil` will be used. If the value
  is instance of `Throwable` the promise will be rejected."
  ([p] (deliver p nil))
  ([p v]
   {:pre [(promise? p)]}
   (let [f (promise->future p)]
     (if (instance? Throwable v)
       (.completeExceptionally f v)
       (.complete f v)))))

(defn all
  "Given an array of promises, return a promise
  that is resolved  when all the items in the
  array are resolved."
  [promises]
  (let [promises (map promise promises)
        futures (map promise->future promises)
        promise' (->> (into-array CompletableFuture futures)
                      (CompletableFuture/allOf)
                      (Promise.))]
    (then promise' (fn [_]
                     (mapv deref promises)))))

(defn any
  "Given an array of promises, return a promise
  that is resolved when first one item in the
  array is resolved."
  [promises]
  (let [xform (comp
               (map promise)
               (map promise->future))]
    (->> (sequence xform promises)
         (into-array CompletableFuture)
         (CompletableFuture/anyOf)
         (Promise.))))

(defn then
  "A chain helper for promises."
  [p callback]
  (then* p callback))

(defn catch
  "Catch all promise chain helper."
  [p callback]
  (error* p callback))

(defn reason
  "Get the rejection reason of this promise.
  Throws an error if the promise isn't rejected."
  [p]
  (let [e (proto/extract p)]
    (when (instance? Throwable e)
      e)))
