(ns me.untethr.nostr.metadata
  (:require [me.untethr.nostr.cache :as cache]
            [me.untethr.nostr.store :as store]
            [clojure.tools.logging :as log])
  (:import (me.untethr.nostr.domain ParsedMetadata)))

(def ^:private cache-spec
  "initialCapacity=500,maximumSize=1000")

(defn create-cache
  [db]
  (cache/build-loading cache-spec
    (fn [k]
      (get (store/load-metadata db [k]) k ::missing))))

(defn update!
  [cache pubkey ^ParsedMetadata parsed-metadata]
  (cache/put! cache pubkey parsed-metadata))

(defn get*
  ^ParsedMetadata [cache pubkey]
  (let [rv (cache/get* cache pubkey)]
    (when-not (identical? rv ::missing)
      rv)))
