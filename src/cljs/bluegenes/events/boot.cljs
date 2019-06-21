(ns bluegenes.events.boot
  (:require [re-frame.core :refer [reg-event-db reg-event-fx subscribe]]
            [bluegenes.db :as db]
            [imcljs.fetch :as fetch]
            [bluegenes.persistence :as persistence]
            [bluegenes.events.webproperties]
            [bluegenes.events.registry :as registry]))

(defn boot-flow
  "Produces a set of re-frame instructions that load all of InterMine's assets into BlueGenes
  See https://github.com/Day8/re-frame-async-flow-fx
  The idea is that any URL routing (such as entering BlueGenes at the home page or a subsection)
  is queued until all of the assets (data model, lists, templates etc) are fetched.
  When finished, an event called :finished-loading-assets is dispatch which tells BlueGenes
  it can continue routing."
  [db ident current-mine]
  ; First things first...
  {:first-dispatch
   (if ident
     ; If we have an identity (and therefore a token) then store it
     [:authentication/store-token current-mine (:token ident)]
     ; Otherwise go fetch an anonymous token
     [:authentication/fetch-anonymous-token current-mine])
   :rules [; When the store-token event has been dispatched then fetch the assets.
           ; We wait for the token because some assets need a token for private data (lists, queries)
           {:when :seen?
            :events :authentication/store-token
            :dispatch-n
            [[:assets/fetch-web-properties]
             [:assets/fetch-model]
             [:assets/fetch-lists]
             [:assets/fetch-class-keys]
             [:assets/fetch-templates]
             [:assets/fetch-widgets]
             [:assets/fetch-summary-fields]
             [:assets/fetch-intermine-version]
             [:assets/fetch-web-service-version]
                         ; If we have an identity then fetch the MyMine tags
                         ; TODO - remove tags
             #_(when ident [:bluegenes.pages.mymine.events/fetch-tree])]}
           ; When we've seen all of the events that indicating our assets have been fetched successfully...
           {:when :seen-all-of?
            :events [:assets/success-fetch-model
                     :assets/success-fetch-web-properties
                     :assets/success-fetch-lists
                     :assets/success-fetch-class-keys
                     :assets/success-fetch-templates
                     :assets/success-fetch-summary-fields
                     :assets/success-fetch-widgets
                     :assets/success-fetch-intermine-version
                     :assets/success-fetch-web-service-version]
            ; Then finished setting up BlueGenes
            :dispatch-n [; Verify InterMine web service version
                         [:verify-web-service-version]
                         ; Start Google Analytics
                         [:start-analytics]
                         ; Set a flag that all assets are fetched (unqueues URL routing)
                         [:finished-loading-assets]
                         ; use the registry to fetch other InterMines
                         [::registry/load-other-mines]
                         ; Save the current state to local storage
                         [:save-state]]
            :halt? true}]})

(defn im-tables-events-forwarder
  "Creates instructions for listening in on im-tables events.
  Why? im-tables is its own re-frame application and it can save query results.
  When its save-list-success event is seen, fire a BlueGenes event to re-fetch lists"
  []
  {:register :im-tables-events ;;  <-- used
   :events #{:imt.io/save-list-success}
   :dispatch-to [:intercept-save-list]})

; When a list is saved from im-tables, intercept the message
; and show an alert while also refreshing the user's lists
(reg-event-fx
 :intercept-save-list
 (fn [{db :db} [_ [_ {:keys [listName listSize] :as evt}]]]
   {:db (update db :messages conj)
    :dispatch-n
    [[:assets/fetch-lists]
     [:messages/add
      {:markup [:span (str "Saved list to My Data: " listName)]
       :style "success"}]]}))

(defn init-mine-defaults
  "If this bluegenes instance is coupled with InterMine, load the intermine's
  config directly from env variables passed to bluegenes. Otherwise, create a
  default mine config."
  []
  (let [{:keys [serviceRoot mineName] :as serverVars}
        (:intermineDefaults (js->clj js/serverVars :keywordize-keys true))]
    (if (seq serverVars)
      {:id :default
       :name mineName
       :service {:root serviceRoot}}
      {:id :default
       :name nil
       :service {:root "http://www.flymine.org/flymine"
                 :token nil}})))

;; Boot the application.
(reg-event-fx
 :boot
 (fn [_world [_ provided-identity]]
   (let [db
         (-> db/default-db
             ;; Add default mine, either as is configured when attached to an
             ;; InterMine instance, or as an empty placeholder.
             (assoc-in [:mines :default] (init-mine-defaults))
             ;; Store the user's identity map provided by the server
             ;; via the client constructor
             (update :auth
                     assoc
                     :thinking? false
                     :identity provided-identity
                     :message nil
                     :error? false))
         ;; Get data previously persisted to local storage.
         {:keys [current-mine mines assets version] :as state}
         (persistence/get-state!)
         ;; We always want `init-mine-defaults` to override the :default mine
         ;; saved in local storage, as a coupled intermine instance should
         ;; always take priority.
         updated-mines (assoc mines :default (init-mine-defaults))]
     {:db (cond-> db
            ;; Only use data from local storage if it's non-empty and the
            ;; client version matches.
            (and (seq state)
                 (= bluegenes.core/version version))
            (assoc :current-mine current-mine
                   :mines updated-mines
                   :assets assets
                   ;; we had assets in localstorage.
                   ;; We'll still load the fresh ones in the background in case they
                   ;; changed, but we can make do with these for now.
                   :fetching-assets? false))
      ;; Boot the application asynchronously
      :async-flow (boot-flow db provided-identity current-mine)
      ;; Register an event sniffer for im-tables
      :forward-events (im-tables-events-forwarder)})))

(defn remove-stateful-keys-from-db
  "Any tools / components that have mine-specific state should lose that
   state if we switch mines. For example, in list upload (ID Resolver),
   drosophila IDs are no longer valid when using humanmine."
  [db]
  (dissoc db :regions :idresolver :results :qb))

(reg-event-fx
 :reboot
 (fn [{db :db}]
   {:db (remove-stateful-keys-from-db db)
    :async-flow (boot-flow db nil (get db :current-mine))}))

(reg-event-fx
 :finished-loading-assets
 (fn [{db :db}]
   {:db (assoc db :fetching-assets? false)
    ;; fetch-organisms doesn't always load before it is needed.
    ;; for example on a fresh load of the id resolver, I sometimes end up with
    ;; no organisms when I initialise the component. I have a workaround
    ;; so it doesn't matter in this case, but it is something to be aware of.
    :dispatch-n [[:cache/fetch-organisms]
                 [:regions/select-all-feature-types]]}))

(reg-event-fx
 :verify-web-service-version
 (fn [{db :db}]
   (let [mine (get db :current-mine)
         version (js/Number (get-in db [:assets :web-service-version mine]))]
     (when (and (< version 26)
                (not (zero? version)))
                 ;; In case the web-service-version is an empty string
       (js/alert
        (str "You are using an outdated InterMine WebService version: "
             version
             ". Unexpected behaviour may occur. We recommend updating to version 26 or above."))))
   {:db db}))

(reg-event-fx
 :start-analytics
 (fn [{db :db}]
   (let [analytics-id (:googleAnalytics
                       (js->clj js/serverVars :keywordize-keys true))
         analytics-enabled? (not (clojure.string/blank? analytics-id))]
     (if analytics-enabled?
        ;;set tracker up if we have a tracking id
       (do
         (js/ga "create" analytics-id "auto")
         (js/ga "send" "pageview")
         (.info js/console
                "Google Analytics enabled. Tracking ID:"
                analytics-id))
        ;;inobtrusive console message if there's no id
       (.info js/console "Google Analytics disabled. No tracking ID."))
     {:db (assoc db :google-analytics
                 {:enabled? analytics-enabled?
                  :analytics-id analytics-id})})))

; Store an authentication token for a given mine
(reg-event-db
 :authentication/store-token
 (fn [db [_ mine-kw token]]
   (assoc-in db [:mines mine-kw :service :token] token)))

; Fetch an anonymous token for a given mine
(reg-event-fx
 :authentication/fetch-anonymous-token
 (fn [{db :db} [_ mine-kw]]
    ;;re-use mine-kw if the mine exists, otherwise use default mine
   (let [mine-name (if (contains? (get db :mines) mine-kw) mine-kw :default)
         mine (dissoc (get-in db [:mines mine-name :service]) :token)]
     {:db db
      :im-chan {:on-success [:authentication/store-token mine-name]
                :chan (fetch/session mine)}})))

; Fetch model
(def preferred-tag "im:preferredBagType")
(defn preferred-fields
  "extricate preferred fields (e.g. default field types for dropdowns, usually protein and gene) from the model"
  [model]
  (keys (filter (comp #(contains? % preferred-tag)
                      set :tags second) (:classes model))))

(reg-event-db
 :assets/success-fetch-model
 (fn [db [_ mine-kw model]]
   (-> db
       (assoc-in [:mines mine-kw :service :model] model)
       (assoc-in [:mines mine-kw :default-object-types]
                 (sort (preferred-fields model))))))

(reg-event-fx
 :assets/fetch-model
 (fn [{db :db}]
   {:db db
    :im-chan {:chan (fetch/model (get-in db [:mines (:current-mine db) :service]))
              :on-success [:assets/success-fetch-model (:current-mine db)]}}))

; Fetch lists

(reg-event-db
 :assets/success-fetch-lists
 (fn [db [_ mine-kw lists]]
   (assoc-in db [:assets :lists mine-kw] lists)))

(reg-event-fx
 :assets/fetch-lists
 (fn [{db :db}]
   {:db db
    :im-chan
    {:chan (fetch/lists
            (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-lists (:current-mine db)]}}))

; Fetch class keys

(reg-event-db
 :assets/success-fetch-class-keys
 (fn [db [_ mine-kw class-keys]]
   (assoc-in db [:mines mine-kw :class-keys] class-keys)))

(reg-event-fx
 :assets/fetch-class-keys
 (fn [{db :db}]
   {:db db
    :im-chan
    {:chan (fetch/class-keys
            (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-class-keys (:current-mine db)]}}))

; Fetch templates

(reg-event-db
 :assets/success-fetch-templates
 (fn [db [_ mine-kw lists]]
   (assoc-in db [:assets :templates mine-kw] lists)))

(reg-event-fx
 :assets/fetch-templates
 (fn [{db :db}]
   {:db db
    :im-chan
    {:chan (fetch/templates (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-templates (:current-mine db)]}}))

; Fetch summary fields

(reg-event-db
 :assets/success-fetch-summary-fields
 (fn [db [_ mine-kw lists]]
   (assoc-in db [:assets :summary-fields mine-kw] lists)))

(reg-event-fx
 :assets/fetch-summary-fields
 (fn [{db :db}]
   {:db db
    :im-chan
    {:chan (fetch/summary-fields
            (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-summary-fields (:current-mine db)]}}))

(reg-event-fx
 :assets/fetch-widgets
  ;;fetches all enrichment widgets. afaik the non-enrichment widgets are InterMine 1.x UI specific so are filtered out upon success
 (fn [{db :db}]
   {:im-chan
    {:chan (fetch/widgets (get-in db [:mines (:current-mine db) :service]))
     :on-success [:assets/success-fetch-widgets (:current-mine db)]}}))

(reg-event-db
 :assets/success-fetch-widgets
 (fn [db [_ mine-kw widgets]]
   (let [widget-type "enrichment"
         filtered-widgets
         (doall (filter (fn [widget]
                          (= widget-type (:widgetType widget))) widgets))]
     (assoc-in db [:assets :widgets mine-kw] filtered-widgets))))

(reg-event-fx
 :assets/fetch-intermine-version
  ;;fetches all enrichment widgets. afaik the non-enrichment widgets
  ;;are InterMine 1.x UI specific so are filtered out upon success
 (fn [{db :db}]
   {:im-chan
    {:chan (fetch/version-intermine
            (get-in db [:mines (:current-mine db) :service]))
     :on-success
     [:assets/success-fetch-intermine-version (:current-mine db)]}}))

(reg-event-db
 :assets/success-fetch-intermine-version
 (fn [db [_ mine-kw version]]
   (assoc-in db [:assets :intermine-version mine-kw] version)))

(reg-event-fx
 :assets/fetch-web-service-version
 (fn [{db :db}]
   {:im-chan
    {:chan (fetch/version-web-service
            (get-in db [:mines (:current-mine db) :service]))
     :on-success
     [:assets/success-fetch-web-service-version (:current-mine db)]}}))

(reg-event-db
 :assets/success-fetch-web-service-version
 (fn [db [_ mine-kw version]]
   (assoc-in db [:assets :web-service-version mine-kw] version)))
