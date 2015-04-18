;; Copyright (c) 2015 Andrey Antukh
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
  (:refer-clojure :exclude [future promise])
  (:require [cats.core :as m]
            [cats.protocols :as proto])
  (:import java.util.concurrent.CompletableFuture
           java.util.concurrent.TimeoutException
           java.util.concurrent.ExecutionException
           java.util.concurrent.CompletionException
           java.util.concurrent.TimeUnit))

(defprotocol IPromise
  "A default abstraction for a promise."
  (^:private rejected* [_] "Returns true if a promise is rejected.")
  (^:private fulfilled* [_] "Returns true if a promise is fulfiled.")
  (^:private pending* [_] "Retutns true if a promise is stil pending.")
  (^:private then* [_ callback] "Chain a promise.")
  (^:private error* [_ callback] "Catch a error in a promise."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
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

(deftype Promise [^CompletableFuture cf]
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

  IPromise
  (rejected* [_]
    (.isCompletedExceptionally cf))

  (fulfilled* [_]
    (and (not (.isCompletedExceptionally cf))
         (not (.isCancelled cf))
         (.isDone cf)))

  (pending* [_]
    (not (.isDone cf)))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn future
  "Converts a promise in a JDK8 CompletableFuture instance."
  [^Promise p]
  (.-cf p))

(defn resolved
  "Return a resolved promise with provided value."
  [v]
  (-> (CompletableFuture/completedFuture v)
      (Promise.)))

(defn rejected
  "Return a rejected promise with provided reason."
  [v]
  (let [f (CompletableFuture.)]
    (.completeExceptionally f v)
    (Promise. f)))

(defn complete
  "Complete the promise."
  [p v]
  (if (instance? Throwable v)
    (.completeExceptionally p v)
    (.complete p v)))

(defmulti promise
  "A polymorphic constructor of promise."
  (fn [& [v & rest]]
    (if (nil? v)
      ::pending
      (class v))))

(defmethod promise clojure.lang.IFn
  [func & [xform]]
  (let [futura (CompletableFuture.)
        promise' (Promise. futura)
        complete #(complete futura %)
        callback (if xform
                   (xform complete)
                   complete)]
    (try
      (func callback)
      (catch Throwable e
        (.completeExceptionally futura e)))
    promise'))

(defmethod promise Throwable
  [e]
  (rejected e))

(defmethod promise CompletableFuture
  [cf]
  (Promise. cf))

(defmethod promise Promise
  [p]
  p)

(defmethod promise ::pending
  []
  (Promise. (CompletableFuture.)))

(defmethod promise :default
  [v]
  (resolved v))

(defn promise?
  "Returns true if `p` is a primise
  instance."
  [p]
  (instance? Promise p))

(defn fulfilled?
  "Returns true if promise `p` is
  already fulfilled."
  [p]
  (fulfilled* p))

(defn rejected?
  "Returns true if promise `p` is
  already rejected."
  [p]
  (rejected* p))

(defn pending?
  "Returns true if promise `p` is
  stil pending."
  [p]
  (pending* p))

(defn all
  "Given an array of promises, return a promise
  that is fulfilled  when all the items in the
  array are fulfilled."
  [promises]
  (let [xform (comp
               (map promise)
               (map future))]
    (->> (sequence xform promises)
         (into-array CompletableFuture)
         (CompletableFuture/allOf)
         (Promise.))))

(defn any
  "Given an array of promises, return a promise
  that is fulfilled when first one item in the
  array is fulfilled."
  [promises]
  (let [xform (comp
               (map promise)
               (map future))]
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
