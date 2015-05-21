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

(ns futura.stream.promise
  (:require [futura.atomic :as atomic]
            [futura.promise :as p]
            [futura.stream.common :as common])
  (:import org.reactivestreams.Subscriber
           futura.stream.common.Subscription
           futura.stream.common.Publisher
           java.util.Set))

(defmethod common/handle-subscribe ::promise
  [^Subscription sub]
  (let [^Subscriber subscriber (.-subscriber sub)
        ^Publisher publisher (.-publisher sub)
        canceled (.-canceled sub)]
    (when (not @canceled)
      (try
        (.onSubscribe subscriber sub)
        (catch Throwable t
          (common/terminate sub (IllegalStateException. "Violated the Reactive Streams rule 2.13")))))))

(defmethod common/handle-send ::promise
  [^Subscription sub]
  (let [^Subscriber subscriber (.-subscriber sub)
        ^Publisher publisher (.-publisher sub)
        canceled (.-canceled sub)
        source (.-source publisher)]
    (p/then source (fn [v]
                     (.onNext subscriber v)
                     (common/handle-cancel sub)
                     (.onComplete subscriber)))
    (p/catch source (fn [e]
                      (common/handle-cancel sub)
                      (.onError e)))))

(defn publisher
  "A publisher constructor with promise
  channel as its source. The returned publisher
  instance is of multicast type."
  ([source] (publisher source {}))
  ([source options]
   (common/publisher ::promise source options)))
