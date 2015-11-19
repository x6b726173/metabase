(ns metabase.driver.generic-sql
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [korma.core :as k]
            [korma.sql.utils :as utils]
            [metabase.driver :as driver]
            (metabase.driver.generic-sql [native :as native]
                                         [query-processor :as qp]
                                         [util :refer :all])
            [metabase.models.field :as field]
            [metabase.util :as u]))

(def ^:private ^:const field-values-lazy-seq-chunk-size
  "How many Field values should we fetch at a time for `field-values-lazy-seq`?"
  ;; Hopefully this is a good balance between
  ;; 1. Not doing too many DB calls
  ;; 2. Not running out of mem
  ;; 3. Not fetching too many results for things like mark-json-field! which will fail after the first result that isn't valid JSON
  500)

(defn- can-connect? [{:keys [connection-details->spec]} details]
  (let [connection (connection-details->spec details)]
    (= 1 (-> (k/exec-raw connection "SELECT 1" :results)
             first
             vals
             first))))

(defn- sync-in-context [_ database do-sync-fn]
  (with-jdbc-metadata [_ database]
    (do-sync-fn)))

(defn- active-tables [{:keys [excluded-schemas]} database]
  (with-jdbc-metadata [^java.sql.DatabaseMetaData md database]
    (set (for [table (filter #(not (contains? excluded-schemas (:table_schem %)))
                             (jdbc/result-set-seq (.getTables md nil nil nil (into-array String ["TABLE", "VIEW"]))))]
           {:name   (:table_name table)
            :schema (:table_schem table)}))))

(defn- active-column-names->type [{:keys [column->base-type]} table]
  (with-jdbc-metadata [^java.sql.DatabaseMetaData md @(:db table)]
    (into {} (for [{:keys [column_name type_name]} (jdbc/result-set-seq (.getColumns md nil (:schema table) (:name table) nil))]
               {column_name (or (column->base-type (keyword type_name))
                                (do (log/warn (format "Don't know how to map column type '%s' to a Field base_type, falling back to :UnknownField." type_name))
                                    :UnknownField))}))))

(defn- table-pks [_ table]
  (with-jdbc-metadata [^java.sql.DatabaseMetaData md @(:db table)]
    (->> (.getPrimaryKeys md nil nil (:name table))
         jdbc/result-set-seq
         (map :column_name)
         set)))

(defn- field-values-lazy-seq [_ {:keys [qualified-name-components table], :as field}]
  (assert (and (map? field)
               (delay? qualified-name-components)
               (delay? table))
    (format "Field is missing required information:\n%s" (u/pprint-to-str 'red field)))
  (let [table           @table
        name-components (rest @qualified-name-components)
        ;; This function returns a chunked lazy seq that will fetch some range of results, e.g. 0 - 500, then concat that chunk of results
        ;; with a recursive call to (lazily) fetch the next chunk of results, until we run out of results or hit the limit.
        fetch-chunk     (fn -fetch-chunk [start step limit]
                          (lazy-seq
                           (let [results (->> (k/select (korma-entity table)
                                                        (k/fields (:name field))
                                                        (k/offset start)
                                                        (k/limit (+ start step)))
                                              (map (keyword (:name field)))
                                              (map (if (contains? #{:TextField :CharField} (:base_type field)) u/jdbc-clob->str
                                                       identity)))]
                             (concat results (when (and (seq results)
                                                        (< (+ start step) limit)
                                                        (= (count results) step))
                                               (-fetch-chunk (+ start step) step limit))))))]
    (fetch-chunk 0 field-values-lazy-seq-chunk-size
                 driver/max-sync-lazy-seq-results)))

(defn- table-rows-seq [_ database table-name]
  (k/select (-> (k/create-entity table-name)
                (k/database (db->korma-db database)))))


(defn- table-fks [_ table]
  (with-jdbc-metadata [^java.sql.DatabaseMetaData md @(:db table)]
    (->> (.getImportedKeys md nil nil (:name table))
         jdbc/result-set-seq
         (map (fn [result]
                {:fk-column-name   (:fkcolumn_name result)
                 :dest-table-name  (:pktable_name result)
                 :dest-column-name (:pkcolumn_name result)}))
         set)))

(defn- field-avg-length [{:keys [string-length-fn]} field]
  (or (some-> (korma-entity @(:table field))
              (k/select (k/aggregate (avg (k/sqlfn* string-length-fn
                                                    (utils/func "CAST(%s AS CHAR)"
                                                                [(keyword (:name field))])))
                                     :len))
              first
              :len
              int)
      0))

(defn- field-percent-urls [_ field]
  (or (let [korma-table (korma-entity @(:table field))]
        (when-let [total-non-null-count (:count (first (k/select korma-table
                                                                 (k/aggregate (count (k/raw "*")) :count)
                                                                 (k/where {(keyword (:name field)) [not= nil]}))))]
          (when (> total-non-null-count 0)
            (when-let [url-count (:count (first (k/select korma-table
                                                          (k/aggregate (count (k/raw "*")) :count)
                                                          (k/where {(keyword (:name field)) [like "http%://_%.__%"]}))))]
              (float (/ url-count total-non-null-count))))))
      0.0))

(def ^:private ^:const required-fns
  "Functions that concrete SQL drivers must define."
  #{:connection-details->spec
    :unix-timestamp->timestamp
    :date
    :date-interval})

(defn- verify-sql-driver [{:keys [column->base-type string-length-fn], :as driver}]
  ;; Check the :column->base-type map
  (assert column->base-type
    "SQL drivers must define :column->base-type.")

  ;; Check :string-length-fn
  (assert string-length-fn
    "SQL drivers must define :string-length-fn.")
  (assert (keyword? string-length-fn)
    ":string-length-fn must be a keyword.")

  ;; Check required fns
  (doseq [f required-fns]
    (assert (f driver)
      (format "SQL drivers must define %s." f))
    (assert (fn? (f driver))
      (format "%s must be a fn." f))))

(defn features [driver]
  (set (cond-> [:foreign-keys
                :standard-deviation-aggregations]
         (:set-timezone-sql driver) (conj :set-timezone))))

;; TODO - just define this method directly
(defn- date-interval [driver unit amount]
  ((:date-interval driver) unit amount))

(def IDriverSQLDefaultsMixin
  "Default implementations of methods in `IDriver` for SQL drivers."
  (merge driver/IDriverDefaultsMixin
         {:active-column-names->type active-column-names->type
          :active-tables             active-tables
          :can-connect?              can-connect?
          :date-interval             date-interval
          :features                  features
          :field-avg-length          field-avg-length
          :field-percent-urls        field-percent-urls
          :field-values-lazy-seq     field-values-lazy-seq
          :process-native            (fn [_ query]
                                       (native/process-and-run query))
          :process-structured        (fn [_ query]
                                       (qp/process-structured query))
          :sync-in-context           sync-in-context
          :table-fks                 table-fks
          :table-pks                 table-pks
          :table-rows-seq            table-rows-seq}))

(def ^:private sql-driver-defaults
  "Default implementations of SQL driver internal methods."
  {:qp-clause->handler  qp/clause->handler
   :stddev-fn           :STDDEV
   :current-datetime-fn (k/sqlfn* :NOW)})

(defn sql-driver
  "Create a Metabase DB driver using the Generic SQL functions.

   A SQL driver must define the following properties / functions:

   *  `column->base-type`

      A map of native DB column types (as keywords) to the `Field` `base-types` they map to.

   *  `string-length-fn`

      Keyword name of the SQL function that should be used to get the length of a string, e.g. `:LENGTH`.

   *  `stddev-fn` *(OPTIONAL)*

      Keyword name of the SQL function that should be used to get the length of a string. Defaults to `:STDDEV`.

   *  `current-datetime-fn` *(OPTIONAL)*

      Korma form that should be used to get the current `DATETIME` (or equivalent).  Defaults to `(k/sqlfn* :NOW)`.

   *  `(connection-details->spec [details-map])`

      Given a `Database` DETAILS-MAP, return a JDBC connection spec.

   *  `(unix-timestamp->timestamp [seconds-or-milliseconds field-or-value])`

      Return a korma form appropriate for converting a Unix timestamp integer field or value to an proper SQL `Timestamp`.
      SECONDS-OR-MILLISECONDS refers to the resolution of the int in question and with be either `:seconds` or `:milliseconds`.

   *  `set-timezone-sql` *(OPTIONAL)*

      This should be a prepared JDBC SQL statement string to be used to set the timezone for the current transaction.

          \"SET @@session.timezone = ?;\"

   *  `(date [this ^Keyword unit field-or-value])`

      Return a korma form for truncating a date or timestamp field or value to a given resolution, or extracting a
      date component.

   *  `excluded-schemas` *(OPTIONAL)*

      Set of string names of schemas to skip syncing tables from.

   * `qp-clause->handler` *(OPTIONAL)*

     A map of query processor clause keywords to functions of the form `(fn [korma-query query-map])` that are used apply them.
     By default, its value is `metabase.driver.generic-sql.query-processor/clause->handler`. These functions are exposed in this way so drivers
     can override default clause application behavior where appropriate -- for example, SQL Server needs to override the function used to apply the
     `:limit` clause, since T-SQL uses `TOP` rather than `LIMIT`."
  [driver]
  ;; Verify the driver
  (verify-sql-driver driver)
  (merge sql-driver-defaults driver))
