; -*- mode: clojure; -*-
; vim: filetype=clojure
(require '[clj-http.client :as client]
         '[cheshire.core :as json]
         '[riemann.query :as query])

(def hostname (.getHostName (java.net.InetAddress/getLocalHost)))

;(include "alerta.clj")

(logging/init {:file "/var/log/riemann/riemann.log"})

; configure the various servers that we listen on
(tcp-server :host "0.0.0.0")
(udp-server :host "0.0.0.0")
(ws-server :host "0.0.0.0")
(repl-server)

(graphite-server :host "0.0.0.0"
                 :protocol :tcp
                 :parser-fn (fn [{:keys [service] :as event}]
                              (if-let [[proj service host resource metric]
                                       (clojure.string/split service #"\." 5)]
                                              {:project proj
                                              :host host
                                              :service service
                                              :metric (:metric event)
                                              :origin "Fastly"
                                              :environment "PROD"
                                              :resource resource
                                              :type "graphiteAlert"
                                              :time (:time event)
                                              ;:tags [source]
                                              :ttl 600  ; metrics gathered every 5 minutes
;                                              :state "normal"
                                              })))


(defn parse-stream
  [& children]
  (fn [e] (let [new-event (assoc e
                            :host (str (:ip e) ":" (:host e))
;                            :resource (:host e)
  ;                          :type "gangliaAlert"
  )]
            (call-rescue new-event children))))

(defn log-info
  [e]
  (info e))


; reap expired events every 10 seconds
(periodically-expire 10 {:keep-keys [:host :service :environment :resource :grid :cluster :ip :tags :metric :index-time]})

; some helpful functions
(defn now []
		(Math/floor (unix-time)))

(defn switch-epoch-to-elapsed
  [& children]
  (fn [e] ((apply with {:metric (- (now) (:metric e))} children) e)))


(defn event-to-grid-event
  [& children]
  (fn [e] ((apply with {:service "grid heartbeat"
                        :host (str (:environment e) ":" (:grid e))
                        :resource (:grid e)
                        :cluster "n/a"} children) e)))

(defn lookup-metric
  [metricname & children]
  (let [metricsymbol (keyword metricname)]
    (fn [e]
      (let [metricevent (.lookup (:index @core) (:host e) metricname)]
        (if-let [metricvalue (:metric metricevent)]
          (call-rescue (assoc e metricsymbol metricvalue) children))))))

; set of severity functions
(defn severity
  [severity message & children]
  (fn [e] ((apply with {:state severity :description message} children) e)))

(def informational (partial severity "informational"))
(def normal (partial severity "normal"))
(def warning (partial severity "warning"))
(def minor (partial severity "minor"))
(def major (partial severity "major"))
(def critical (partial severity "critical"))

(defn edge-detection
  [samples & children]
  (let [detector (by [:host :service] (runs samples :state (apply changed :state {:init "normal"} children)))]
    (fn [e] (detector e))))

(defn set-resource-from-cluster [e] (assoc e :resource (:cluster e)))

(defn proportion
  [events]
  (when-let [event (first events)]
    (try
      (riemann.folds/fold-all (fn [a b] (/ a (+ a b))) events)
      (catch NullPointerException ex
        (merge event :metric nil)))))

; thresholding
(let [index (default :ttl 900 (index))
;      alert (async-queue! :alerta {:queue-size 10000}
;                          (alerta {}))
      alert #(info "ALERT" %)
      dedup-alert (edge-detection 1 index alert)
      dedup-2-alert (edge-detection 2 log-info alert)
      dedup-4-alert (edge-detection 4 log-info alert)
]

   (streams (parse-stream
             (let [fs-util
                   (match :service #"^fs_util-"
                          (with {:event "FsUtil" :group "OS"}
                                (splitp < metric
                                        95 (major "File system utilisation is very high" dedup-alert)
                                        90 (minor "File system utilisation is high" dedup-alert)
                                        (normal "File system utilisation is OK" dedup-alert))))

                   inode-util
                   (match :service #"^inode_util-"
                          (with {:event "InodeUtil" :group "OS"}
                                (splitp < metric
                                        95 (major "File system inode utilisation is very high" dedup-alert)
                                        90 (minor "File system inode utilisation is high" dedup-alert)
                                        (normal "File system inode utilisation is OK" dedup-alert))))
                   swap-util
                   (match :resource "swap_util"
                          (with {:event "SwapUtil" :group "OS"}
                                (splitp < metric
                                        90 (minor "Swap utilisation is very high" dedup-alert)
                                        (normal "Swap utilisation is OK" dedup-alert))))

                   cpu-load-five
                   (by [:host]
                       (match :service "load_five"
                              (with {:event "SystemLoad" :group "OS"}
                                    (lookup-metric "cpu_num"
                                                   (split*
                                                     (fn [e] (< (* 10 (:cpu_num e)) (:metric e))) (minor "System 5-minute load average is very high" dedup-alert)
                                                     (fn [e] (< (* 6 (:cpu_num e)) (:metric e))) (warning "System 5-minute load average is high" dedup-alert)
                                                     (normal "System 5-minute load average is OK" dedup-alert))))))

                   disk-io-util
                   (match :service #"^diskio_util-"
                          (by [:host :service]
                              (stable 600 :metric
                                      (with {:event "DiskIOUtil" :group "OS"}
                                            (splitp < metric
                                                    99 (major "Disk IO utilisation is very high" dedup-alert)
                                                    95 (minor "Disk IO utilisation is high" dedup-alert)
                                                    (normal "Disk IO utilisation is OK" dedup-alert))))))]
               (where (not (state "expired"))
                      ; prn
                      fs-util
                      inode-util
                      swap-util
                      cpu-load-five
                      disk-io-util
                      ))))
    

     ;(streams (match :type "graphiteAlert" #(info %)))
)
