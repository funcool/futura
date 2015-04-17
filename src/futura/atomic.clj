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

(ns futura.atomic
  "A idiomatic wrapper for jdk atomic types."
  (:refer-clojure :exclude [set! get long ref boolean compare-and-set!]))

(defprotocol IAtomic
  (compare-and-set! [_ v v'] "Perform the CAS operation.")
  (get-and-set! [_ v] "Set a new value and return the previous one.")
  (eventually-set! [_ v] "Eventually set a new value.")
  (get [_] "Get the current value.")
  (set! [_ v] "Set a new value."))

(defprotocol IAtomicNumber
  (get-and-add! [_ v] "Adds a delta and return the previous value.")
  (get-and-dec! [_] "Decrements the value and return the previous one.")
  (get-and-inc! [_] "Increments the value and returns the previous one."))

(deftype AtomicLong [^java.util.concurrent.atomic.AtomicLong av]
  IAtomicNumber
  (get-and-add! [_ v]
    (.getAndAdd av v))
  (get-and-dec! [_]
    (.getAndDecrement av))
  (get-and-inc! [_]
    (.getAndIncrement av))

  IAtomic
  (compare-and-set! [_ expected update]
    (.compareAndSet av expected update))
  (get-and-set! [_ v]
    (.getAndSet av v))
  (eventually-set! [_ v]
    (.lazySet av v))
  (get [_]
    (.get av))
  (set! [_ v]
    (.set av v))

  clojure.lang.IDeref
  (deref [_]
    (.get av)))

(deftype AtomicRef [^java.util.concurrent.atomic.AtomicReference av]
  IAtomic
  ( compare-and-set! [_ expected update]
    (.compareAndSet av expected update))
  (get-and-set! [_ v]
    (.getAndSet av v))
  ( eventually-set! [_ v]
    (.lazySet av v))
  (get [_]
    (.get av))
  (set! [_ v]
    (.set av v))

  clojure.lang.IDeref
  (deref [_]
    (.get av)))

(deftype AtomicBoolean [^java.util.concurrent.atomic.AtomicBoolean av]
  IAtomic
  (compare-and-set! [_ expected update]
    (.compareAndSet av expected update))
  (get-and-set! [_ v]
    (.getAndSet av v))
  (eventually-set! [_ v]
    (.lazySet av v))
  (get [_]
    (.get av))
  (set! [_ v]
    (.set av v))

  clojure.lang.IDeref
  (deref [_]
    (.get av)))


(defn long
  "Create an instance of atomic long."
  ([] (long 0))
  ([n]
   (let [al (java.util.concurrent.atomic.AtomicLong. n)]
     (AtomicLong. al))))

(defn ref
  "Create an instance of atomic reference."
  [v]
  (let [ar (java.util.concurrent.atomic.AtomicReference. v)]
    (AtomicRef. ar)))

(defn boolean
  "Create an instance of atomic boolean."
  [v]
  (let [ar (java.util.concurrent.atomic.AtomicBoolean. v)]
    (AtomicBoolean. ar)))




