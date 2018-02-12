(ns situated.core
  (:require [clojure.java.jdbc :as j]
            [clojure.set :as set]
            [stch.sql :as s]
            [stch.sql.format :as f])
  (:import org.postgresql.util.PGobject
           java.sql.Timestamp
           [java.time Instant LocalDate LocalDateTime]))

(defrecord VenueType [v]
  f/ToSQL
  (f/-to-sql [this]
    (doto (PGobject.)
      (.setType "venue_type")
      (.setValue v))))

(defn add-vals [con tbl vals]
  (j/execute! con
              (-> (s/insert-into tbl)
                  (s/values vals)
                  f/format)))

(defn select*
  ([con tbl]
   (select* con tbl identity))
  ([con tbl more]
   (-> (s/select :*)
       (s/from tbl)
       more
       f/format
       (->> (j/query con)))))

(defn select*-by-id [con tbl id]
  (-> (s/select :*)
      (s/from tbl)
      (s/where `(= :id ~id))
      f/format
      (->> (j/query con))
      first))

(defmulti fetch
  (fn [k params con] k))

(defmethod fetch :members
  [k _ con]
  (select* con k))

(defmethod fetch :member-by-id
  [k {id :id} con]
  (set/rename-keys (select*-by-id con :members id)
                   {:id :member-id}))

(def format-venue
  (let [address-keys [:postal_code :prefecture :city :street1 :street2]]
    (comp
     #(apply dissoc % address-keys)
     (fn [v]
       (let [address (-> (select-keys v address-keys)
                         (set/rename-keys {:street1 :address1
                                           :street2 :address2}))]
         (assoc v :address address)))
     #(set/rename-keys % {:id :venue-id
                          :name :venue-name}))))

(defn venue-query [gid]
  (-> (s/select :*)
      (s/from :venues)
      (s/where `(= :group_id ~gid))))

(defmethod fetch :venues
  [k {gid :group-id} con]
  (-> (venue-query gid)
      (s/where `(= :venue-type
                   ~(->VenueType "'physical'")))
      f/format
      (->> (j/query con)
           (map format-venue))))

(defmethod fetch :online-venues
  [k {gid :group-id} con]
  (-> (venue-query gid)
      (s/where `(= :venue-type
                   ~(->VenueType "'online'")))
      f/format
      (->> (j/query con)
           (map format-venue))))

(defmethod fetch :groups
  [k {gid :group-id} con]
  (let []
    (->> (select* con :groups)
         (map (comp
               #(dissoc % :created_at)
               #(set/rename-keys % {:id :group-id
                                    :name :group-name}))))))

(defmethod fetch :meetup-by-id
  [k {gid :group-id e :event-id} con]
  (let [m (select*-by-id con :meetups e)]
    (-> m
        (set/rename-keys {:id :event-id})
        (assoc :venue
               (format-venue (select*-by-id con :venues (:venue_id m))))
        (assoc :members
               (-> (s/select :*)
                   (s/from :meetups-members)
                   (s/where `(= meetup-id ~e))
                   (s/join :members
                           '(= :meetups-members.member-id :members.id))
                   f/format
                   (->> (j/query con)
                        (map #(dissoc % :meetup-id :id)))))
        (dissoc :venue_id))))

(defmethod fetch :meetups
  [k {gid :group-id} con]
  (->> (select* con :meetups)
       (map (fn [{:as m v :venue_id e :id}]
              (-> m
                  (set/rename-keys {:id :event-id})
                  (assoc :venue
                         (format-venue (select*-by-id con :venues v)))
                  (assoc :members
                         (-> (s/select :*)
                             (s/from :meetups-members)
                             (s/where `(= meetup-id ~e))
                             (s/join :members
                                     '(= :meetups-members.member-id :members.id))
                             f/format
                             (->> (j/query con)
                                  (map #(dissoc % :meetup-id :id)))))
                  (dissoc :venue_id))))))

(defmethod fetch :default
  [k _ _]
  (str k "not implemented"))

(defmulti store
  (fn [k params con] k))

(defmethod store :store-meetup
  [k {t :title g :group-id s :start-at e :end-at v :venue-id} con]
  (j/insert! con
             :meetups
             {:title t
              :group_id g
              :start_at (Timestamp/from (Instant/parse s))
              :end_at (Timestamp/from (Instant/parse e))
              :venue_id v}))

(defmethod store :store-member
  [k {f :first-name l :last-name e :email} con]
  (j/insert! con
             :members
             {:first_name f
              :last_name l
              :email e}))

(defmethod store :join-meetup
  [k {e :event-id id :id} con]
  (j/insert! con
             :meetups_members
             {:meetup_id e
              :member_id id}))

(defmethod store :join-group
  [k {g :group-id id :id admin? :admin} con]
  (j/insert! con
             :groups_members
             {:group_id g
              :member_id id
              :admin admin?}))

(defmethod store :store-venue
  [k {g :group-id
      vname :venue-name
      {pstl :postal-code prf :prefecture city :city
       a1 :address1 a2 :address2}
      :address}
   con]
  (j/insert! con
             :venues
             {:group_id g
              :name vname
              :postal_code pstl
              :prefecture prf
              :city city
              :street1 a1
              :street2 a2}))

(defmethod store :store-group
  [k {g :group-name
      admins :admin-member-ids} con]
  (let [[{group-id :id :as group}]
        (j/insert! con
                   :groups
                   {:name g
                    :created_at (Timestamp/from (Instant/now))})
        added-admins
        (doall (map #(-> (store :join-group
                                {:group-id group-id
                                 :id %
                                 :admin true}
                                con)
                         first
                         :member_id)
                    admins))]
    (->  group
         (set/rename-keys {:name :group-name
                           :id :group-id})
         (assoc :admin (->> added-admins
                            (map #(fetch :member-by-id {:id %} con))))
         (dissoc :created_at))))

(comment
  (def con
    {:dbtype "postgresql"
     :dbname "meetup"
     :host "localhost"
     :user "meetup"
     :password "password123"
     :ssl false})

  (fetch :venues nil con))
