(ns mem-tree
  (:use [datomic.api :only (q db) :as d]
        quil.core)
  (:require [clojure.java.io :as io]
            [meridian.datomic-rtree.rtree :as rtree]
            [meridian.datomic-rtree.bbox :as bbox]
            [meridian.datomic-rtree.hilbert :as hilbert]
            [meridian.datomic-rtree.bulk :as bulk])
  (:import datomic.Util))

(def uri "datomic:mem://rtrees")

(defn find-tree [db]
  (->> (d/q '[:find ?e :where [?e :rtree/root]] db)
       ffirst (d/entity db)))

(defn create-and-connect-db [uri schema]
  (d/delete-database uri)
  (d/create-database uri)
  (let [conn (d/connect uri)]
    (->> (read-string (slurp schema))
         (d/transact conn))
    conn))

(defn rand-entries []
  (let [hilbert-index (hilbert/index-fn 28 [0.0 600.0])]
    (->> (repeatedly #(bbox/bbox (rand 540) (rand 540) (+ 10 (rand 50)) (+ 10 (rand 50))))
         (map (fn [box]
                (merge box
                       {:node/entry (str (char (+ (rand-int 25) 65)))
                        :node/hilbert-val (hilbert-index (bbox/centre box))}))))))

(defn install-rand-data [conn num-entries]
  (let [test-data (take num-entries (rand-entries))]
    (time (doseq [entry test-data]
            @(d/transact conn [[:rtree/insert-entry (find-tree (d/db conn)) entry]])))
    :done))

(defn install-rand-ents [conn num-entries]
  (let [test-data (mapv #(assoc % :db/id (d/tempid :db.part/user))
                        (take num-entries (rand-entries)))]
    (time @(d/transact conn test-data))
    :done))

(defn create-tree-and-install-rand-data [conn num-entries max-children min-children]
  @(d/transact conn (rtree/create-tree-tx max-children min-children))
  (install-rand-data conn num-entries))

(defn install-and-bulk-load [conn num-entries max-children min-children]
  (install-rand-ents conn num-entries)
  (let [ents (rtree/hilbert-ents (d/db conn))]
    @(d/transact conn
                 (bulk/bulk-tx ents #db/id[:db.part/user]
                               max-children min-children bbox/area))
    :done))

(defn print-tree [conn]
  (let [root (:rtree/root (find-tree (d/db conn)))]
    ((fn walk [n indent]
       (println (str indent (:db/id n) " " (:node/entry n)))
       (doseq [c (:node/children n)]
         (walk c (str indent "---"))))
     root "")))

(defn install-and-print-tree [conn num-entries]
  (install-rand-data conn num-entries)
  (print-tree conn))

(defn all-entries [db]
  (map #(d/entity db (first %))
       (d/q '[:find ?e :where [?e :node/entry]] db)))


(defn naive-intersecting [entries search-box]
  (into [] (filter #(bbox/intersects? search-box %) entries)))

(defn ent-intersects? [box ent db]
  (bbox/intersects? box (d/entity db ent)))

(def search-rules
  '[[(intersecting ?ancestor ?descendant ?search-box)
     [(mem-tree/ent-intersects? ?search-box ?descendant $)]
     [?ancestor :node/children ?descendant]]
    [(intersecting ?ancestor ?descendant ?search-box)
     [?ancestor :node/children ?child]
     [intersecting ?child ?descendant ?search-box]]])

(def intersecting-q
  '[:find ?e
    :in $ % ?root ?bbox
    :where
    [intersecting ?root ?e ?bbox]
    [?e :node/entry]])

(comment
  (def conn (create-and-connect-db uri "resources/datomic/schema.edn"))
  (install-rand-data conn 1000)
  (install-rand-ents conn 100)
  (create-tree-and-install-rand-data conn 40 6 3)
  (def search-box (bbox/extents 0.0 0.0 10.0 10.0))
  (def root (:rtree/root (find-tree (d/db conn))))
  (time (count (naive-intersecting (all-entries (d/db conn)) search-box)))
  (time (count (rtree/intersecting root search-box)))
  (time (count (d/q intersecting-q (d/db conn) search-rules (:db/id root) search-box)))
  (time (install-and-bulk-load conn 10000 6 3))
  )

;;;;;; draw tree with quill ;;;;;;

(defn all-bbox [db]
  (map #(d/entity db (first %))
       (d/q '[:find ?e :where [?e :bbox/min-x]] db)))

(defn key-press []
  (let [conn (state :conn)]
    (install-and-print-tree conn 1)
    (reset! (state :rects) (all-bbox (d/db conn)))))

(defn setup-sketch []
  (frame-rate 30)
  (smooth)
  (let [conn (create-and-connect-db uri "resources/datomic/schema.edn")]
    ;(install-and-bulk-load conn 30 6 3)
    (create-tree-and-install-rand-data conn 1 6 3)
    (set-state! :conn conn
                :rects (atom (all-bbox (d/db conn))))))

(defn draw-sketch []
  (stroke 255)
  (fill 255)
  (rect 0 0 600 600)

  (doseq [r (sort-by :node/is-leaf? @(state :rects))]
    (no-fill)
    (stroke-weight 1)
    (cond
     (:node/entry r) (stroke 0 0 255)
     (:node/is-leaf? r) (do (stroke-weight 2) (stroke 255 0 0))
     :else (stroke 50 255 225))
    (rect (:bbox/min-x r) (:bbox/min-y r)
          (- (:bbox/max-x r) (:bbox/min-x r)) (- (:bbox/max-y r) (:bbox/min-y r)))))

(defn tree-sketch []
  (sketch :title "R-tree"
          :setup setup-sketch
          :draw draw-sketch
          :key-typed key-press
          :size [600 600]))
